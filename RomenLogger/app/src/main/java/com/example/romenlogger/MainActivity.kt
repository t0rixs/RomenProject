package com.example.romenlogger

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.romenlogger.data.AccelSample
import com.example.romenlogger.data.Recording
import com.example.romenlogger.data.RecordingRepository
import com.example.romenlogger.device.PhotoCapture
import com.example.romenlogger.device.VoiceAnnouncer
import com.example.romenlogger.log.AppLog
import com.example.romenlogger.network.Uploader
import com.example.romenlogger.service.RecordingService
import com.example.romenlogger.sync.BluetoothSyncServerService
import com.example.romenlogger.ui.MainScreen
import com.example.romenlogger.ui.UploadStatus
import com.example.romenlogger.ui.theme.RomenLoggerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private var boundService: RecordingService? = null
    private val boundFlow = MutableStateFlow<RecordingService?>(null)
    private lateinit var voice: VoiceAnnouncer
    private lateinit var photoCapture: PhotoCapture
    private var pendingAction = PendingAction.None
    private var pendingLauncherAction: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RecordingService.LocalBinder ?: return
            boundService = binder.service
            boundFlow.value = binder.service
            pendingLauncherAction?.let {
                pendingLauncherAction = null
                handleLauncherAction(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            boundFlow.value = null
        }
    }

    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            AppLog.i("runtime permission: $perm → ${if (granted) "許可" else "拒否"}")
        }
        if (bluetoothPermissionsGranted()) {
            BluetoothSyncServerService.start(this)
        } else {
            AppLog.w("Bluetooth権限がないためスマホ同期サーバーは起動しません")
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            AppLog.i("runtime permission: $perm → ${if (granted) "許可" else "拒否"}")
        }
        when (pendingAction) {
            PendingAction.Start -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    startRecordingService()
                    voice.speak("記録を開始します")
                } else AppLog.w("位置情報が許可されていないため記録サービスは起動しません")
            }
            PendingAction.Photo -> {
                if (photoPermissionsGranted()) {
                    capturePhoto()
                } else voice.speak("カメラを使用できません")
            }
            PendingAction.None -> Unit
        }
        pendingAction = PendingAction.None
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voice = VoiceAnnouncer(this)
        photoCapture = PhotoCapture(this)
        requestBluetoothSyncPermission()

        val repo = RecordingRepository(this)

        setContent {
            RomenLoggerTheme {
                val service by boundFlow.collectAsState()
                val state by (service?.state
                    ?: MutableStateFlow(RecordingService.RecordingState.Idle))
                    .collectAsState(initial = RecordingService.RecordingState.Idle)

                val accelSamples by (service?.accelPreview
                    ?: MutableStateFlow<List<AccelSample>>(emptyList()))
                    .collectAsState(initial = emptyList())

                val gpsPoints by (service?.gpsPointCount ?: MutableStateFlow(0))
                    .collectAsState(initial = 0)

                val lastLocation by (service?.lastLocation
                    ?: MutableStateFlow<android.location.Location?>(null))
                    .collectAsState(initial = null)

                var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
                var uploadStatuses by remember {
                    mutableStateOf<Map<String, UploadStatus>>(emptyMap())
                }
                val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

                LaunchedEffect(state) {
                    // 状態変化時 + 記録中は定期的に履歴をリロード
                    while (true) {
                        recordings = repo.listAll()
                        if (state == RecordingService.RecordingState.Recording) {
                            delay(3_000L)
                        } else {
                            delay(30_000L)
                        }
                    }
                }

                MainScreen(
                    isRecording = state == RecordingService.RecordingState.Recording,
                    isPaused = state == RecordingService.RecordingState.Paused,
                    accelSamples = accelSamples,
                    gpsPointCount = gpsPoints,
                    currentLocation = lastLocation,
                    recordings = recordings,
                    uploadStatuses = uploadStatuses,
                    onStart = { onStartClick() },
                    onStop = { onStopClick() },
                    onPauseResume = { togglePauseResume() },
                    onDelete = { rec ->
                        repo.delete(rec)
                        recordings = repo.listAll()
                        uploadStatuses = uploadStatuses - rec.id
                    },
                    onSend = { rec ->
                        uploadStatuses = uploadStatuses + (rec.id to UploadStatus.Sending)
                        coroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                Uploader.upload(rec)
                            }
                            uploadStatuses = uploadStatuses + (rec.id to when (result) {
                                is Uploader.Result.Success -> UploadStatus.Sent
                                is Uploader.Result.Failure -> UploadStatus.Failed(result.message)
                            })
                        }
                    }
                )
            }
        }

        // サービスが既に動いていれば再接続
        pendingLauncherAction = intent.action.takeIf(::isButtonAction)
        bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (boundService == null && isButtonAction(intent.action)) {
            pendingLauncherAction = intent.action
        } else {
            handleLauncherAction(intent.action)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP || event.repeatCount != 0) {
            return super.dispatchKeyEvent(event)
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { toggleStartStop(); true }
            KeyEvent.KEYCODE_CAMERA -> { requestPhoto(); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { togglePauseResume(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onDestroy() {
        try { unbindService(connection) } catch (_: Throwable) {}
        photoCapture.close()
        voice.shutdown()
        super.onDestroy()
    }

    private fun onStartClick() {
        val needed = requiredPermissions()
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startRecordingService()
            voice.speak("記録を開始します")
        } else {
            pendingAction = PendingAction.Start
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun onStopClick() {
        val stop = RecordingService.stopIntent(this)
        startServiceCompat(stop)
        voice.speak("記録を終了します")
    }

    private fun toggleStartStop() {
        if (boundService?.state?.value == RecordingService.RecordingState.Idle || boundService == null) {
            onStartClick()
        } else {
            onStopClick()
        }
    }

    private fun togglePauseResume() {
        when (boundService?.state?.value) {
            RecordingService.RecordingState.Recording -> {
                startServiceCompat(RecordingService.pauseIntent(this))
                voice.speak("記録を一時停止します")
            }
            RecordingService.RecordingState.Paused -> {
                startServiceCompat(RecordingService.resumeIntent(this))
                voice.speak("記録を再開します")
            }
            else -> voice.speak("記録は開始されていません")
        }
    }

    private fun requestPhoto() {
        when (boundService?.state?.value) {
            RecordingService.RecordingState.Recording -> Unit
            else -> {
                voice.speak("記録中のみ撮影できます")
                return
            }
        }
        if (photoPermissionsGranted()) capturePhoto() else {
            pendingAction = PendingAction.Photo
            requestPermissions.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun photoPermissionsGranted(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return camera
    }

    private fun capturePhoto() {
        val photoContext = boundService?.photoContext()
        if (photoContext == null) {
            voice.speak("記録中のみ撮影できます")
            return
        }
        val capturedAtMs = System.currentTimeMillis()
        val outputFile = File(photoContext.recording.directory, "photo_$capturedAtMs.jpg")
        photoCapture.takePhoto(outputFile) { result ->
            result.onSuccess { file ->
                appendPhotoMetadata(
                    photoContext.recording.directory,
                    file.name,
                    capturedAtMs,
                    photoContext.location,
                )
                val location = photoContext.location
                if (location != null) {
                    AppLog.i("写真撮影: ${file.name} lat=${location.latitude} lon=${location.longitude}")
                } else {
                    AppLog.i("写真撮影: ${file.name} (位置情報なし)")
                }
                voice.speak("撮影しました")
            }.onFailure {
                AppLog.e("写真撮影に失敗", it)
                voice.speak("撮影に失敗しました")
            }
        }
    }

    private fun appendPhotoMetadata(
        recordingDirectory: File,
        fileName: String,
        capturedAtMs: Long,
        location: android.location.Location?,
    ) {
        val metadataFile = File(recordingDirectory, "photos.json")
        val root = if (metadataFile.exists()) {
            runCatching { JSONObject(metadataFile.readText()) }.getOrElse { JSONObject() }
        } else JSONObject()
        val photos = root.optJSONArray("photos") ?: JSONArray().also { root.put("photos", it) }
        photos.put(JSONObject().apply {
            put("id", "photo_$capturedAtMs")
            put("fileName", fileName)
            put("capturedAtMs", capturedAtMs)
            if (location != null) {
                put("lat", location.latitude)
                put("lon", location.longitude)
                put("accuracyM", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
            }
        })
        metadataFile.writeText(root.toString(2))
    }

    private fun handleLauncherAction(action: String?) {
        when (action) {
            ACTION_TOGGLE_RECORDING -> toggleStartStop()
            ACTION_TAKE_PHOTO -> requestPhoto()
            ACTION_TOGGLE_PAUSE -> togglePauseResume()
        }
    }

    private fun isButtonAction(action: String?): Boolean = action == ACTION_TOGGLE_RECORDING ||
        action == ACTION_TAKE_PHOTO || action == ACTION_TOGGLE_PAUSE

    private fun startRecordingService() {
        val start = RecordingService.startIntent(this)
        startServiceCompat(start)
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestBluetoothSyncPermission() {
        val needed = bluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            BluetoothSyncServerService.start(this)
        } else {
            requestBluetoothPermissions.launch(needed.toTypedArray())
        }
    }

    private fun bluetoothPermissionsGranted() = bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun bluetoothPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list
    }

    private enum class PendingAction { None, Start, Photo }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "com.example.romenlogger.action.TOGGLE_RECORDING"
        const val ACTION_TAKE_PHOTO = "com.example.romenlogger.action.TAKE_PHOTO"
        const val ACTION_TOGGLE_PAUSE = "com.example.romenlogger.action.TOGGLE_PAUSE"
    }
}
