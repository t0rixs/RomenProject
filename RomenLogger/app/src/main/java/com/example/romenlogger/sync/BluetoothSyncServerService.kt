package com.example.romenlogger.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.romenlogger.MainActivity
import com.example.romenlogger.R
import com.example.romenlogger.data.MobileRouteCache
import com.example.romenlogger.data.RecordingRepository
import com.example.romenlogger.log.AppLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.Executors

/** ペアリング済みAndroidスマホへ終了済み記録を送るBluetooth Classicサーバー。 */
class BluetoothSyncServerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("スマホ同期待機中")
                .setContentText("Bluetoothで記録を自動同期できます")
                .setContentIntent(tap)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        running = true
        executor.execute(::acceptLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        while (running) {
            if (!canConnect()) {
                AppLog.w("Bluetooth同期待機: BLUETOOTH_CONNECT 権限が必要です")
                Thread.sleep(5_000)
                continue
            }
            try {
                val server = openServerSocket(adapter)
                serverSocket = server
                AppLog.i("Bluetooth同期待機中")
                val socket = server.accept()
                runCatching { serve(socket) }
                    .onFailure { AppLog.w("Bluetooth同期失敗: ${it.message}") }
                runCatching { socket.close() }
                runCatching { server.close() }
            } catch (t: Throwable) {
                if (running) {
                    AppLog.w("Bluetooth同期待受エラー: ${t.message}")
                    Thread.sleep(2_000)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openServerSocket(adapter: BluetoothAdapter): BluetoothServerSocket {
        return try {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (first: Throwable) {
            AppLog.w("secure RFCOMM失敗、insecureで再試行: ${first.message}")
            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        }
    }

    private fun canConnect() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun serve(socket: BluetoothSocket) {
        val input = DataInputStream(socket.inputStream.buffered())
        val output = DataOutputStream(socket.outputStream.buffered())
        require(input.readUTF() == PROTOCOL_MAGIC) { "protocol mismatch" }
        val known = buildSet {
            repeat(input.readInt().coerceIn(0, 100_000)) { add(input.readUTF()) }
        }
        val records = RecordingRepository(this).listAll()
            .filter { it.endedAtMs != null && it.id !in known }
            .sortedBy { it.startedAtMs }
        output.writeUTF(PROTOCOL_MAGIC)
        output.writeInt(records.size)
        for (recording in records) {
            MobileRouteCache.writeIfNeeded(recording.directory)
            val files = recording.directory.listFiles()
                ?.filter(::isMobileSyncFile)
                ?.sortedBy { it.name }
                .orEmpty()
            AppLog.i("Bluetooth同期送信: ${recording.id} (${files.size}ファイル)")
            output.writeUTF(recording.id)
            output.writeInt(files.size)
            for (file in files) {
                AppLog.i("Bluetooth同期ファイル: ${recording.id}/${file.name} ${file.length() / 1024}KB")
                output.writeUTF(file.name)
                output.writeLong(file.length())
                FileInputStream(file).use { it.copyTo(output, 64 * 1024) }
            }
        }
        output.flush()
        AppLog.i("Bluetooth同期完了: ${records.size}件")
    }

    private fun isMobileSyncFile(file: File): Boolean = file.isFile && (
        file.name in setOf("meta.json", "gps.csv", "photos.json", "route_mobile.json") ||
            file.name.matches(Regex("photo_[0-9]+\\.jpg"))
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "スマホ自動同期", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val PROTOCOL_MAGIC = "ROMEN_SYNC_V1"
        const val SERVICE_NAME = "RomenLogger Sync"
        val SERVICE_UUID: UUID = UUID.fromString("91b17c20-7d6a-4dc8-a0f3-4d893a3e3811")
        private const val CHANNEL_ID = "bluetooth_sync"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, BluetoothSyncServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
