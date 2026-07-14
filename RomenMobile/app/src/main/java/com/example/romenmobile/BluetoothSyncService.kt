package com.example.romenmobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class BluetoothSyncService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val reconnectSignal = Semaphore(0)
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        running = true
        notifyStatus("THINKLETを検索中…")
        executor.execute(::syncLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)?.let { address ->
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_DEVICE_ADDRESS, address).commit()
        }
        reconnectSignal.release()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false
        reconnectSignal.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun syncLoop() {
        while (running) {
            try {
                if (!canConnect()) {
                    notifyStatus("Bluetooth権限が必要です")
                } else {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val selectedAddress = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_DEVICE_ADDRESS, null)
                    val device = selectedAddress?.let { address ->
                        adapter?.bondedDevices?.firstOrNull { it.address == address }
                    }
                    if (adapter == null || !adapter.isEnabled) notifyStatus("BluetoothがOFFです")
                    else if (selectedAddress == null) notifyStatus("アプリでTHINKLETを選択してください")
                    else if (device == null) notifyStatus("選択した機器のペアリング待ちです")
                    else sync(device)
                }
            } catch (t: Throwable) {
                notifyStatus("再接続待ち: ${t.message ?: "接続失敗"}")
            }
            try {
                reconnectSignal.tryAcquire(30, TimeUnit.SECONDS)
                reconnectSignal.drainPermits()
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sync(device: BluetoothDevice) {
        notifyStatus("${device.name ?: "THINKLET"}に接続中…")
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        connectSocket(device).use { socket ->
            val input = DataInputStream(socket.inputStream.buffered())
            val output = DataOutputStream(socket.outputStream.buffered())
            val repository = MobileRepository(this)
            val known = repository.list().map { it.id }
            output.writeUTF(PROTOCOL_MAGIC)
            output.writeInt(known.size)
            known.forEach(output::writeUTF)
            output.flush()
            require(input.readUTF() == PROTOCOL_MAGIC) { "通信形式が一致しません" }
            val count = input.readInt().coerceIn(0, 10_000)
            repeat(count) {
                val id = input.readUTF().also { require(isSafeName(it)) }
                val temp = File(repository.root, ".$id.syncing").apply { deleteRecursively(); mkdirs() }
                val files = input.readInt().coerceIn(0, 10_000)
                repeat(files) {
                    val name = input.readUTF().also { require(isSafeName(it)) }
                    var remaining = input.readLong().also { require(it in 0..MAX_FILE_BYTES) }
                    FileOutputStream(File(temp, name)).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            require(n > 0) { "通信が途中で切れました" }
                            out.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                }
                val target = File(repository.root, id)
                target.deleteRecursively()
                require(temp.renameTo(target)) { "保存に失敗しました" }
            }
            notifyStatus(if (count == 0) "同期済み・最新です" else "$count 件を同期しました")
            sendBroadcast(Intent(ACTION_SYNC_COMPLETE).setPackage(packageName))
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        return try {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID).also { it.connect() }
        } catch (first: IOException) {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            (method.invoke(device, 1) as BluetoothSocket).also { it.connect() }
        }
    }

    private fun canConnect() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun notifyStatus(text: String) {
        showNotification(text)
        sendBroadcast(
            Intent(ACTION_SYNC_STATUS)
                .putExtra(EXTRA_STATUS, text)
                .setPackage(packageName)
        )
    }

    private fun showNotification(text: String) {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.romenmobile.R.mipmap.ic_launcher)
            .setContentTitle("RomenMobile 自動同期")
            .setContentText(text).setContentIntent(pi).setOngoing(true).build())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bluetooth自動同期", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_SYNC_COMPLETE = "com.example.romenmobile.SYNC_COMPLETE"
        const val ACTION_SYNC_STATUS = "com.example.romenmobile.SYNC_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val PROTOCOL_MAGIC = "ROMEN_SYNC_V1"
        private val SERVICE_UUID = UUID.fromString("91b17c20-7d6a-4dc8-a0f3-4d893a3e3811")
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private const val PREFS = "bluetooth_sync"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private fun isSafeName(value: String) = value.matches(Regex("[A-Za-z0-9._-]+"))

        fun start(context: Context, deviceAddress: String? = null) {
            if (deviceAddress != null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DEVICE_ADDRESS, deviceAddress).commit()
            }
            val i = Intent(context, BluetoothSyncService::class.java)
            deviceAddress?.let { i.putExtra(EXTRA_DEVICE_ADDRESS, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
    }
}
