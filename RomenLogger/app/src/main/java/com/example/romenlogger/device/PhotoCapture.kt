package com.example.romenlogger.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream

/** Previewを必要としないTHINKLET向けの1枚撮影。 */
class PhotoCapture(private val context: Context) {
    private val thread = HandlerThread("RomenPhoto").apply { start() }
    private val handler = Handler(thread.looper)

    @SuppressLint("MissingPermission")
    fun takePhoto(outputFile: File, onResult: (Result<File>) -> Unit) {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull()
            ?: return onResult(Result.failure(IllegalStateException("カメラが見つかりません")))
        val reader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)
        var camera: CameraDevice? = null

        fun finish(result: Result<File>) {
            try { camera?.close() } catch (_: Throwable) {}
            try { reader.close() } catch (_: Throwable) {}
            Handler(context.mainLooper).post { onResult(result) }
        }

        reader.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage().use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { it.write(bytes) }
                    finish(Result.success(outputFile))
                }
            } catch (t: Throwable) {
                finish(Result.failure(t))
            }
        }, handler)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    device.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val request = device.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply { addTarget(reader.surface) }.build()
                                    session.capture(request, null, handler)
                                } catch (t: Throwable) {
                                    finish(Result.failure(t))
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                finish(Result.failure(IllegalStateException("カメラ設定に失敗しました")))
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(device: CameraDevice) =
                    finish(Result.failure(IllegalStateException("カメラが切断されました")))

                override fun onError(device: CameraDevice, error: Int) =
                    finish(Result.failure(IllegalStateException("カメラエラー: $error")))
            }, handler)
        } catch (t: Throwable) {
            finish(Result.failure(t))
        }
    }

    fun close() = thread.quitSafely()
}
