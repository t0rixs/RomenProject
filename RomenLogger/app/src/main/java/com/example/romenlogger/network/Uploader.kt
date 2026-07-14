package com.example.romenlogger.network

import com.example.romenlogger.data.MergedBuilder
import com.example.romenlogger.data.Recording
import com.example.romenlogger.log.AppLog
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 1セッション分の記録ファイル群を PC 側 RomenViewer 受信サーバへ multipart POST する。
 *
 * 接続先 URL: 既定で http://localhost:5174/api/upload
 *   → PC側で `adb reverse tcp:5174 tcp:5174` を事前に実行しておく必要あり。
 */
object Uploader {

    private const val DEFAULT_URL = "http://localhost:5174/api/upload"
    private const val BOUNDARY = "----RomenLoggerBoundary7d3a"

    sealed class Result {
        object Success : Result()
        data class Failure(val message: String) : Result()
    }

    fun upload(
        rec: Recording,
        url: String = DEFAULT_URL,
        progressTag: String = rec.id,
    ): Result {
        return try {
            // 事前にブラウザ向け軽量 JSON を生成（PC 側で再計算しなくて済む）
            val mergedJson = MergedBuilder.build(rec).toString()
            val mergedFile = File(rec.directory, "merged.json")
            mergedFile.writeText(mergedJson)

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                doOutput = true
                doInput = true
                useCaches = false
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 60_000
                setRequestProperty("Connection", "close")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                setChunkedStreamingMode(64 * 1024)
            }

            DataOutputStream(conn.outputStream).use { out ->
                writeField(out, "id", rec.id)
                writeFile(out, "meta.json", rec.metaFile, "application/json")
                writeFile(out, "merged.json", mergedFile, "application/json")
                if (rec.gpsFile.exists()) writeFile(out, "gps.csv", rec.gpsFile, "text/csv")
                if (rec.accelFile.exists()) writeFile(out, "accel.csv", rec.accelFile, "text/csv")
                val photosJson = File(rec.directory, "photos.json")
                if (photosJson.exists()) writeFile(out, "photos.json", photosJson, "application/json")
                rec.directory.listFiles()
                    ?.filter { it.isFile && it.name.matches(Regex("photo_[0-9]+\\.jpg")) }
                    ?.sortedBy { it.name }
                    ?.forEach { writeFile(out, it.name, it, "image/jpeg") }
                out.writeBytes("--$BOUNDARY--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                ?: ""
            AppLog.i("upload[$progressTag] HTTP $code: $body")
            if (code in 200..299) Result.Success
            else Result.Failure("HTTP $code: $body")
        } catch (t: Throwable) {
            AppLog.w("upload[$progressTag] failed: ${t.message}")
            Result.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun writeField(out: DataOutputStream, name: String, value: String) {
        out.writeBytes("--$BOUNDARY\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun writeFile(out: DataOutputStream, fieldName: String, file: File, mime: String) {
        out.writeBytes("--$BOUNDARY\r\n")
        out.writeBytes(
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fieldName\"\r\n"
        )
        out.writeBytes("Content-Type: $mime\r\n\r\n")
        FileInputStream(file).use { it.copyTo(out) }
        out.writeBytes("\r\n")
    }
}
