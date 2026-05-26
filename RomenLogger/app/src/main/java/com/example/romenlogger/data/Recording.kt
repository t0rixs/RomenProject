package com.example.romenlogger.data

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1回の記録セッションのメタ情報。
 * セッションごとに専用ディレクトリが作られ、そこに以下のファイルが置かれる:
 *  - meta.json (このクラスの内容)
 *  - gps.csv   (timestamp_ms, lat, lon, alt, speed_mps, accuracy_m)
 *  - accel.csv (timestamp_ms, ax, ay, az)
 */
data class Recording(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val gpsCount: Int,
    val accelCount: Int,
    val directory: File
) {
    val gpsFile: File get() = File(directory, "gps.csv")
    val accelFile: File get() = File(directory, "accel.csv")
    val metaFile: File get() = File(directory, "meta.json")

    val durationMs: Long?
        get() = endedAtMs?.let { it - startedAtMs }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startedAtMs", startedAtMs)
        if (endedAtMs != null) put("endedAtMs", endedAtMs)
        put("gpsCount", gpsCount)
        put("accelCount", accelCount)
    }

    companion object {
        private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)

        fun formatTime(ms: Long): String = formatter.format(Date(ms))

        fun formatDuration(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }

        fun fromJson(json: JSONObject, dir: File): Recording = Recording(
            id = json.getString("id"),
            startedAtMs = json.getLong("startedAtMs"),
            endedAtMs = if (json.has("endedAtMs")) json.getLong("endedAtMs") else null,
            gpsCount = json.optInt("gpsCount", 0),
            accelCount = json.optInt("accelCount", 0),
            directory = dir
        )
    }
}
