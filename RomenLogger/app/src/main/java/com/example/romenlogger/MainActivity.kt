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
import com.example.romenlogger.log.AppLog
import com.example.romenlogger.network.Uploader
import com.example.romenlogger.service.RecordingService
import com.example.romenlogger.ui.MainScreen
import com.example.romenlogger.ui.UploadStatus
import com.example.romenlogger.ui.theme.RomenLoggerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var boundService: RecordingService? = null
    private val boundFlow = MutableStateFlow<RecordingService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RecordingService.LocalBinder ?: return
            boundService = binder.service
            boundFlow.value = binder.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            boundFlow.value = null
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            AppLog.i("runtime permission: $perm → ${if (granted) "許可" else "拒否"}")
        }
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) {
            startRecordingService()
        } else {
            AppLog.w("位置情報が許可されていないため記録サービスは起動しません")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    accelSamples = accelSamples,
                    gpsPointCount = gpsPoints,
                    currentLocation = lastLocation,
                    recordings = recordings,
                    uploadStatuses = uploadStatuses,
                    onStart = { onStartClick() },
                    onStop = { onStopClick() },
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
        bindService(Intent(this, RecordingService::class.java), connection, 0)
    }

    override fun onDestroy() {
        try { unbindService(connection) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun onStartClick() {
        val needed = requiredPermissions()
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startRecordingService()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun onStopClick() {
        val stop = RecordingService.stopIntent(this)
        startServiceCompat(stop)
    }

    private fun startRecordingService() {
        val start = RecordingService.startIntent(this)
        startServiceCompat(start)
        // サービス開始後にBind
        bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
}
