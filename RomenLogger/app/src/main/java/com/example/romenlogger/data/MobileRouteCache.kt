package com.example.romenlogger.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** スマホ地図用の軽量軌跡キャッシュ（gps.csv を間引いて保存）。 */
object MobileRouteCache {
    private const val FILE_NAME = "route_mobile.json"
    private const val MAX_POINTS = 200

    fun writeIfNeeded(directory: File) {
        val gps = File(directory, "gps.csv")
        if (!gps.exists()) return
        val points = sampleGps(gps)
        if (points.isEmpty()) return
        val array = JSONArray()
        for ((lat, lon) in points) {
            array.put(JSONArray().put(lat).put(lon))
        }
        File(directory, FILE_NAME).writeText(JSONObject().put("points", array).toString())
    }

    private fun sampleGps(gpsFile: File): List<Pair<Double, Double>> {
        val stride = maxOf(1, ((gpsFile.length() / 45) / MAX_POINTS).toInt())
        val result = ArrayList<Pair<Double, Double>>(MAX_POINTS + 1)
        gpsFile.bufferedReader().use { reader ->
            reader.readLine()
            var index = 0
            var line = reader.readLine()
            var last: Pair<Double, Double>? = null
            while (line != null) {
                val point = parseGpsLine(line)
                if (point != null) {
                    last = point
                    if (index % stride == 0 && result.size < MAX_POINTS) result.add(point)
                }
                index++
                line = reader.readLine()
            }
            last?.let { (lat, lon) ->
                if (result.isEmpty() || result.last().first != lat || result.last().second != lon) {
                    result.add(last)
                }
            }
        }
        return result
    }

    private fun parseGpsLine(line: String): Pair<Double, Double>? {
        val c1 = line.indexOf(',')
        if (c1 < 0) return null
        val c2 = line.indexOf(',', c1 + 1)
        if (c2 < 0) return null
        val c3 = line.indexOf(',', c2 + 1)
        val lonEnd = if (c3 < 0) line.length else c3
        val lat = line.substring(c1 + 1, c2).toDoubleOrNull() ?: return null
        val lon = line.substring(c2 + 1, lonEnd).toDoubleOrNull() ?: return null
        return lat to lon
    }
}
