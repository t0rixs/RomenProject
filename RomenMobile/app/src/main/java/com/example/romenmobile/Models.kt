package com.example.romenmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MobileRecording(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val gpsCount: Int,
    val accelCount: Int,
    val photoCount: Int,
    val directory: File,
)

data class MapPoint(val lat: Double, val lon: Double)

data class RecordingPhoto(
    val id: String,
    val fileName: String,
    val capturedAtMs: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
) {
    val hasLocation: Boolean get() = lat != null && lon != null
}

class MobileRepository(context: Context) {
    val root = File(context.filesDir, "recordings").apply { mkdirs() }

    fun list(): List<MobileRecording> = MobileDebug.time("list") {
        root.listFiles { f -> f.isDirectory && isVisibleRecordingDir(f) }
            .orEmpty()
            .mapNotNull { dir ->
                runCatching {
                    val j = JSONObject(File(dir, "meta.json").readText())
                    MobileRecording(
                        j.getString("id"), j.getLong("startedAtMs"),
                        j.optLong("endedAtMs").takeIf { j.has("endedAtMs") },
                        j.optInt("gpsCount"), j.optInt("accelCount"),
                        countPhotos(dir), dir,
                    )
                }.getOrNull()
            }.sortedByDescending { it.startedAtMs }
            .also { recs ->
                MobileDebug.log("list/summary", "count=${recs.size} " + recs.joinToString { r ->
                    "${r.id}:gps=${r.gpsCount},photos=${r.photoCount}"
                })
            }
    }

    fun mapPath(recording: MobileRecording): List<MapPoint> = MobileDebug.time("mapPath/${recording.id}") {
        val cache = File(recording.directory, ROUTE_CACHE)
        val gpsFile = File(recording.directory, "gps.csv")
        val mergedFile = File(recording.directory, "merged.json")
        MobileDebug.log(
            "mapPath/files",
            "${MobileDebug.fileInfo(gpsFile)} ${MobileDebug.fileInfo(cache)} ${MobileDebug.fileInfo(mergedFile)}",
        )
        if (cache.exists()) {
            return@time loadRouteCache(cache).also {
                MobileDebug.log("mapPath/cache_hit", "points=${it.size}")
            }
        }
        if (!gpsFile.exists()) {
            MobileDebug.log("mapPath/miss", "no gps.csv")
            return@time emptyList()
        }
        val points = sampleGps(gpsFile)
        if (points.isNotEmpty()) saveRouteCache(cache, points)
        MobileDebug.log("mapPath/sampled", "points=${points.size} stride_source=gps.csv")
        points
    }

    fun photos(recording: MobileRecording): List<RecordingPhoto> = MobileDebug.time("photos/${recording.id}") {
        runCatching {
            val photosFile = File(recording.directory, "photos.json")
            if (!photosFile.exists()) return@time emptyList()
            MobileDebug.log("photos/file", MobileDebug.fileInfo(photosFile))
            val array = JSONObject(photosFile.readText()).getJSONArray("photos")
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val j = array.getJSONObject(i)
                    add(RecordingPhoto(
                        j.getString("id"), j.getString("fileName"), j.getLong("capturedAtMs"),
                        if (j.has("lat") && !j.isNull("lat")) j.getDouble("lat") else null,
                        if (j.has("lon") && !j.isNull("lon")) j.getDouble("lon") else null,
                        if (j.has("accuracyM") && !j.isNull("accuracyM")) j.getDouble("accuracyM") else null,
                    ))
                }
            }.also { MobileDebug.log("photos/loaded", "count=${it.size}") }
        }.getOrDefault(emptyList())
    }

    private fun loadRouteCache(cache: File): List<MapPoint> = runCatching {
        val array = JSONObject(cache.readText()).getJSONArray("points")
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val pair = array.getJSONArray(i)
                add(MapPoint(pair.getDouble(0), pair.getDouble(1)))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveRouteCache(cache: File, points: List<MapPoint>) {
        val array = JSONArray()
        for (point in points) {
            array.put(JSONArray().put(point.lat).put(point.lon))
        }
        cache.writeText(JSONObject().put("points", array).toString())
    }

    private fun sampleGps(gpsFile: File): List<MapPoint> {
        val stride = maxOf(1, ((gpsFile.length() / 45) / MAX_MAP_POINTS).toInt())
        MobileDebug.log("sampleGps/start", "${MobileDebug.fileInfo(gpsFile)} stride=$stride")
        val result = ArrayList<MapPoint>(MAX_MAP_POINTS + 1)
        var lines = 0
        var lastLine: String? = null
        gpsFile.bufferedReader().use { reader ->
            reader.readLine()
            var index = 0
            var line = reader.readLine()
            while (line != null) {
                lines++
                lastLine = line
                if (index % stride == 0 && result.size < MAX_MAP_POINTS) {
                    parseGpsLine(line)?.let { result.add(it) }
                }
                index++
                line = reader.readLine()
            }
        }
        lastLine?.let { parseGpsLine(it) }?.let {
            if (result.isEmpty() || result.last().lat != it.lat || result.last().lon != it.lon) {
                result.add(it)
            }
        }
        MobileDebug.log("sampleGps/done", "lines=$lines parsed_calls<=${result.size + 1} kept=${result.size}")
        return result
    }

    private fun parseGpsLine(line: String): MapPoint? {
        val c1 = line.indexOf(',')
        if (c1 < 0) return null
        val c2 = line.indexOf(',', c1 + 1)
        if (c2 < 0) return null
        val c3 = line.indexOf(',', c2 + 1)
        val lonEnd = if (c3 < 0) line.length else c3
        val lat = line.substring(c1 + 1, c2).toDoubleOrNull() ?: return null
        val lon = line.substring(c2 + 1, lonEnd).toDoubleOrNull() ?: return null
        return MapPoint(lat, lon)
    }

    private fun countPhotos(dir: File): Int =
        dir.listFiles()?.count { it.isFile && it.name.matches(PHOTO_FILE) }
            ?: countPhotosFromJson(dir)

    private fun countPhotosFromJson(dir: File): Int {
        val photosFile = File(dir, "photos.json")
        if (!photosFile.exists()) return 0
        return runCatching {
            JSONObject(photosFile.readText()).optJSONArray("photos")?.length() ?: 0
        }.getOrDefault(0)
    }

    private fun isVisibleRecordingDir(dir: File): Boolean {
        val name = dir.name
        return !name.startsWith('.') && !name.endsWith(".syncing")
    }

    companion object {
        private const val ROUTE_CACHE = "route_mobile.json"
        private const val MAX_MAP_POINTS = 200
        private val PHOTO_FILE = Regex("photo_[0-9]+\\.jpg")
    }
}
