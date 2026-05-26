package com.example.romenlogger.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.romenlogger.MainActivity
import com.example.romenlogger.R
import com.example.romenlogger.data.AccelSample
import com.example.romenlogger.data.Recording
import com.example.romenlogger.data.RecordingRepository
import com.example.romenlogger.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 記録開始/停止を担当するForegroundService。
 * 位置情報は Android の [LocationManager] のみを使用（Google Play 開発者サービス不要）。
 * 加速度センサーと同時に記録し、CSV に書き出す。
 */
class RecordingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var repository: RecordingRepository
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentRecording: Recording? = null
    private var gpsWriter: BufferedWriter? = null
    private var accelWriter: BufferedWriter? = null
    private val gpsCount = AtomicInteger(0)
    private val accelCount = AtomicInteger(0)
    private var flushJob: Job? = null

    private val _state = MutableStateFlow(RecordingState.Idle)
    val state: StateFlow<RecordingState> get() = _state

    /** 記録中の UI 用プレビュー（最大約3秒分 @50Hz を想定） */
    private val _accelPreview = MutableStateFlow<List<AccelSample>>(emptyList())
    val accelPreview: StateFlow<List<AccelSample>> get() = _accelPreview

    private val previewBuffer = ArrayDeque<AccelSample>(PREVIEW_MAX + 16)
    private val previewLock = Any()
    @Volatile
    private var lastPreviewEmitElapsedMs = 0L

    /** 記録中に書き込んだ GPS 行数（UI 表示用） */
    private val _gpsPointCount = MutableStateFlow(0)
    val gpsPointCount: StateFlow<Int> get() = _gpsPointCount

    /** 最新の位置（記録中・非記録中を問わず UI から参照可能） */
    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> get() = _lastLocation

    private var locationUpdatesRegistered = false
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (_state.value == RecordingState.Recording) writeLocation(loc)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            AppLog.i("Location onStatusChanged provider=$provider status=$status")
        }

        override fun onProviderEnabled(provider: String) {
            AppLog.i("Location provider 有効: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            AppLog.w("Location provider 無効: $provider（端末の位置情報設定を確認）")
        }
    }

    inner class LocalBinder : android.os.Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        repository = RecordingRepository(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (_state.value == RecordingState.Recording) {
            Log.w(TAG, "already recording")
            return
        }
        if (!hasRequiredPermissions()) {
            AppLog.e("権限不足のため記録を開始できません（ACCESS_FINE_LOCATION）")
            Log.e(TAG, "missing permissions; abort")
            return
        }

        val startedAtMs = System.currentTimeMillis()
        val recording = repository.createNew(startedAtMs)
        currentRecording = recording
        gpsCount.set(0)
        _gpsPointCount.value = 0
        accelCount.set(0)
        gpsWriter = BufferedWriter(FileWriter(recording.gpsFile, true))
        accelWriter = BufferedWriter(FileWriter(recording.accelFile, true))
        clearAccelPreview()

        startForegroundCompat(recording)
        acquireWakeLock()
        registerSensor()
        if (accelerometer != null) {
            AppLog.i("加速度センサー: name=${accelerometer?.name} maxRange=${accelerometer?.maximumRange}")
        } else {
            AppLog.w("加速度センサーが取得できません")
        }
        requestLocationUpdates()
        startPeriodicFlush()

        _state.value = RecordingState.Recording
        AppLog.i("記録開始 id=${recording.id} accelCsv=${recording.accelFile.name} gpsCsv=${recording.gpsFile.name}")
        Log.i(TAG, "recording started: ${recording.id}")
    }

    fun stopRecording() {
        if (_state.value != RecordingState.Recording) return

        try { sensorManager.unregisterListener(sensorListener) } catch (_: Throwable) {}
        removeAllLocationUpdates()

        flushJob?.cancel()
        flushJob = null

        try { gpsWriter?.flush(); gpsWriter?.close() } catch (_: Throwable) {}
        try { accelWriter?.flush(); accelWriter?.close() } catch (_: Throwable) {}
        gpsWriter = null
        accelWriter = null

        currentRecording?.let { rec ->
            val finished = rec.copy(
                endedAtMs = System.currentTimeMillis(),
                gpsCount = gpsCount.get(),
                accelCount = accelCount.get()
            )
            repository.writeMeta(finished)
            currentRecording = finished
            Log.i(TAG, "recording stopped: ${finished.id} gps=${finished.gpsCount} accel=${finished.accelCount}")
        }

        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        clearAccelPreview()
        _gpsPointCount.value = 0
        _state.value = RecordingState.Idle
    }

    override fun onDestroy() {
        stopRecording()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- sensor ----
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val tsMs = System.currentTimeMillis()
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val line = String.format(Locale.US, "%d,%.6f,%.6f,%.6f\n", tsMs, ax, ay, az)
            try {
                accelWriter?.write(line)
                accelCount.incrementAndGet()
            } catch (t: Throwable) {
                Log.w(TAG, "accel write failed", t)
            }
            pushAccelPreview(tsMs, ax, ay, az)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun clearAccelPreview() {
        synchronized(previewLock) { previewBuffer.clear() }
        _accelPreview.value = emptyList()
        lastPreviewEmitElapsedMs = 0L
    }

    private fun pushAccelPreview(tsMs: Long, ax: Float, ay: Float, az: Float) {
        if (_state.value != RecordingState.Recording) return
        val sample = AccelSample(tsMs, ax, ay, az)
        synchronized(previewLock) {
            previewBuffer.addLast(sample)
            while (previewBuffer.size > PREVIEW_MAX) {
                previewBuffer.removeFirst()
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPreviewEmitElapsedMs < PREVIEW_EMIT_MS) return
        lastPreviewEmitElapsedMs = now
        val snap = synchronized(previewLock) { previewBuffer.toList() }
        _accelPreview.value = snap
    }

    private fun registerSensor() {
        val sensor = accelerometer ?: run {
            Log.w(TAG, "accelerometer not available")
            return
        }
        sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    // ---- location (framework only, no Play services) ----

    private fun writeLocation(loc: Location) {
        val tsMs = System.currentTimeMillis()
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else -1f
        val alt = if (loc.hasAltitude()) loc.altitude else 0.0
        _lastLocation.value = loc
        val line = String.format(
            Locale.US,
            "%d,%.7f,%.7f,%.3f,%.3f,%.2f\n",
            tsMs, loc.latitude, loc.longitude, alt, speed, accuracy
        )
        try {
            gpsWriter?.write(line)
            val n = gpsCount.incrementAndGet()
            _gpsPointCount.value = n
            if (n <= 20) {
                val age = tsMs - loc.time
                AppLog.i(
                    "GPS行#$n provider=${loc.provider} lat=${loc.latitude} lon=${loc.longitude} " +
                        "accuracy_m=$accuracy age_ms=$age (古いほどキャッシュ寄り)"
                )
            }
        } catch (t: Throwable) {
            AppLog.w("gps write failed", t)
            Log.w(TAG, "gps write failed", t)
        }
    }

    private fun removeAllLocationUpdates() {
        if (!locationUpdatesRegistered) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Throwable) {}
        locationUpdatesRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        AppLog.i(
            "Location 診断: allProviders=${locationManager.allProviders} " +
                "gpsEnabled=${locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)} " +
                "netEnabled=${locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AppLog.i("Location 診断: isLocationEnabled(OS全体)=${locationManager.isLocationEnabled}")
        }
        primeLastKnownLocations()
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_MIN_TIME_MS,
                    GPS_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
                locationUpdatesRegistered = true
                AppLog.i("requestLocationUpdates GPS 登録 OK (minTime=${GPS_MIN_TIME_MS}ms)")
            } else {
                AppLog.w("GPS プロバイダが無効（衛星が使えません。屋外でのテスト推奨）")
                Log.w(TAG, "GPS provider disabled")
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    NETWORK_MIN_TIME_MS,
                    NETWORK_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
                locationUpdatesRegistered = true
                AppLog.i("requestLocationUpdates NETWORK 登録 OK (minTime=${NETWORK_MIN_TIME_MS}ms)")
            } else {
                AppLog.w("NETWORK 位置プロバイダが無効（基地局Wi-Fi推定に必要）")
                Log.w(TAG, "Network location provider disabled")
            }
        } catch (e: SecurityException) {
            AppLog.e("requestLocationUpdates SecurityException（権限）", e)
            Log.e(TAG, "location SecurityException", e)
        } catch (e: IllegalArgumentException) {
            AppLog.w("requestLocationUpdates IllegalArgumentException", e)
            Log.w(TAG, "location IllegalArgumentException", e)
        }
        if (!locationUpdatesRegistered) {
            AppLog.e("位置プロバイダが1つも登録できませんでした。OSの位置情報ON・権限を確認してください。")
            Log.e(TAG, "no location provider registered; check OS location settings")
        }
    }

    /** キャッシュされている直近位置があれば先に1行書く */
    @SuppressLint("MissingPermission")
    private fun primeLastKnownLocations() {
        var best: Location? = null
        for (p in locationProvidersToTry()) {
            try {
                val l = locationManager.getLastKnownLocation(p)
                if (l == null) {
                    AppLog.i("getLastKnownLocation($p) → null")
                    continue
                }
                val ageMs = System.currentTimeMillis() - l.time
                AppLog.i(
                    "getLastKnownLocation($p) → lat=${l.latitude} lon=${l.longitude} " +
                        "accuracy=${if (l.hasAccuracy()) l.accuracy else -1f} age_ms=$ageMs"
                )
                if (best == null || l.time > best.time) best = l
            } catch (e: SecurityException) {
                AppLog.e("getLastKnownLocation($p) SecurityException", e)
            }
        }
        if (best != null && _state.value == RecordingState.Recording) {
            AppLog.i("直近キャッシュのうち最新を1行書き込み (provider=${best.provider})")
            writeLocation(best)
        } else {
            AppLog.i("getLastKnownLocation はすべて null または未取得（この後 onLocationChanged を待ちます）")
        }
    }

    private fun locationProvidersToTry(): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                add(LocationManager.FUSED_PROVIDER)
            } catch (_: Throwable) {
            }
        }
    }

    // ---- helpers ----
    private fun startPeriodicFlush() {
        flushJob = serviceScope.launch {
            while (true) {
                try {
                    gpsWriter?.flush()
                    accelWriter?.flush()
                    currentRecording?.let { rec ->
                        repository.writeMeta(
                            rec.copy(
                                gpsCount = gpsCount.get(),
                                accelCount = accelCount.get()
                            )
                        )
                    }
                } catch (_: Throwable) {}
                kotlinx.coroutines.delay(5_000L)
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RomenLogger::Recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L /* 12h */)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun hasRequiredPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine
    }

    private fun startForegroundCompat(recording: Recording) {
        val notif = buildNotification(recording)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildNotification(recording: Recording): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("路面記録中")
            .setContentText("位置情報 + 加速度センサーを記録しています")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "路面記録",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "位置情報 / 加速度センサーを記録中に表示されます"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    enum class RecordingState { Idle, Recording }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val GPS_MIN_TIME_MS = 500L
        private const val GPS_MIN_DISTANCE_M = 0f
        private const val NETWORK_MIN_TIME_MS = 2000L
        private const val NETWORK_MIN_DISTANCE_M = 0f

        private const val PREVIEW_MAX = 400
        private const val PREVIEW_EMIT_MS = 50L

        const val ACTION_START = "com.example.romenlogger.action.START"
        const val ACTION_STOP = "com.example.romenlogger.action.STOP"

        fun startIntent(ctx: Context): Intent =
            Intent(ctx, RecordingService::class.java).apply { action = ACTION_START }

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, RecordingService::class.java).apply { action = ACTION_STOP }
    }
}
