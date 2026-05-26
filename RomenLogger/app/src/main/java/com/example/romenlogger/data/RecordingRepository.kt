package com.example.romenlogger.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 記録セッションの保存先ディレクトリを管理するリポジトリ。
 *
 * ルート: context.filesDir/recordings/
 *   ├── 20260421_154010_abc12/
 *   │     ├── meta.json
 *   │     ├── gps.csv
 *   │     └── accel.csv
 *   └── ...
 */
class RecordingRepository(context: Context) {

    private val root: File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun rootDir(): File = root

    /** 新しい記録用ディレクトリを作り、Recording を返す。 */
    fun createNew(startedAtMs: Long): Recording {
        val id = idFormat.format(Date(startedAtMs)) + "_" + randomSuffix()
        val dir = File(root, id).apply { mkdirs() }
        val rec = Recording(
            id = id,
            startedAtMs = startedAtMs,
            endedAtMs = null,
            gpsCount = 0,
            accelCount = 0,
            directory = dir
        )
        writeMeta(rec)
        File(dir, "gps.csv").writeText("timestamp_ms,lat,lon,alt,speed_mps,accuracy_m\n")
        File(dir, "accel.csv").writeText("timestamp_ms,ax,ay,az\n")
        return rec
    }

    fun writeMeta(recording: Recording) {
        try {
            recording.metaFile.writeText(recording.toJson().toString(2))
        } catch (t: Throwable) {
            Log.w(TAG, "failed to write meta", t)
        }
    }

    fun listAll(): List<Recording> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, "meta.json")
            if (!meta.exists()) return@mapNotNull null
            try {
                val json = org.json.JSONObject(meta.readText())
                Recording.fromJson(json, dir)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to read meta: ${meta.absolutePath}", t)
                null
            }
        }.sortedByDescending { it.startedAtMs }
    }

    fun delete(recording: Recording): Boolean {
        return recording.directory.deleteRecursively()
    }

    private fun randomSuffix(): String {
        val chars = ('a'..'z') + ('0'..'9')
        return (1..5).map { chars.random() }.joinToString("")
    }

    companion object {
        private const val TAG = "RecordingRepository"
        private val idFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
