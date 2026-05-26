package com.example.romenlogger.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * gps.csv と accel.csv を時刻でマージし、ブラウザ表示用の軽量 JSON を生成する。
 *
 * 振動指標の計算 (路面状態の推認が目的):
 *  - バケット内で各軸平均 (mx, my, mz) をとり、姿勢オフセット (重力) を除去
 *  - 偏差 (dx, dy, dz) に軸別重みを掛け vw = sqrt(WX²·dx² + WY²·dy² + WZ²·dz²)
 *    THINKLET 装着姿勢では Z 軸が鴛直近似になるため Z を重視 (既定 WZ=3)
 *  - バケット集約: vMax = max(vw), vMean = mean(vw), vRms = sqrt(mean(vw²))
 *  - 路面評価では连続的荷重を見る vRms を主指標とし、vMax で瞬間ショックを补足する使い方を想定
 *
 * 出力 JSON:
 * {
 *   "id": "...",
 *   "bucketMs": 100,
 *   "weights": {"wx":1, "wy":1, "wz":3},
 *   "startedAtMs": ..., "endedAtMs": ...,
 *   "samples": [
 *     {"t": <ms>, "lat":..., "lon":..., "vMax":..., "vMean":..., "vRms":...},
 *     ...
 *   ]
 * }
 */
object MergedBuilder {

    // 軸別重み。Z 軸 (鴛直) を 3 倍重視。
    // バケット平均で重力・姿勢オフセットを除去済みなので G を引く処理は不要。
    private const val WX = 1.0
    private const val WY = 1.0
    private const val WZ = 3.0

    data class Options(
        val bucketMs: Long = 100L,
        val maxGapMs: Long = 5_000L, // GPS が無いバケットは捨てる閾値
    )

    fun build(rec: Recording, opt: Options = Options()): JSONObject {
        val accelBuckets = aggregateAccel(rec.accelFile, opt.bucketMs)
        val gpsList = readGps(rec.gpsFile)

        val samples = JSONArray()
        if (gpsList.isNotEmpty() && accelBuckets.isNotEmpty()) {
            // 二分探索のため GPS は時刻順
            val gpsTimes = LongArray(gpsList.size) { gpsList[it].t }
            for (b in accelBuckets) {
                val nearest = nearestGps(gpsTimes, b.tMs)
                val gp = gpsList[nearest]
                if (abs(gp.t - b.tMs) > opt.maxGapMs) continue
                val o = JSONObject()
                o.put("t", b.tMs)
                o.put("lat", gp.lat)
                o.put("lon", gp.lon)
                o.put("vMax", round3(b.vMax))
                o.put("vMean", round3(b.vMean))
                o.put("vRms", round3(b.vRms))
                samples.put(o)
            }
        }

        return JSONObject().apply {
            put("id", rec.id)
            put("bucketMs", opt.bucketMs)
            put("weights", JSONObject().apply {
                put("wx", WX); put("wy", WY); put("wz", WZ)
            })
            put("startedAtMs", rec.startedAtMs)
            rec.endedAtMs?.let { put("endedAtMs", it) }
            put("gpsCount", gpsList.size)
            put("accelBucketCount", accelBuckets.size)
            put("samples", samples)
        }
    }

    private data class AccelBucket(val tMs: Long, val vMax: Double, val vMean: Double, val vRms: Double)
    private data class GpsPoint(val t: Long, val lat: Double, val lon: Double)

    /**
     * accel.csv を bucketMs ごとに集約し、軸別重み付き偏差ベクトルの
     * vMax / vMean / vRms を計算する。
     */
    private fun aggregateAccel(file: File, bucketMs: Long): List<AccelBucket> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<AccelBucket>(1024)

        // バケット内サンプルを一時バッファ。
        // 二周目 (偏差計算) で同じサンプルを読み返すため保存する。
        val bufX = ArrayList<Double>(8)
        val bufY = ArrayList<Double>(8)
        val bufZ = ArrayList<Double>(8)
        var curBucket = Long.MIN_VALUE

        val wx2 = WX * WX
        val wy2 = WY * WY
        val wz2 = WZ * WZ

        fun flush() {
            val n = bufX.size
            if (n == 0) return
            // 1. バケット内平均 (重力・姿勢オフセット除去)
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            for (i in 0 until n) { sx += bufX[i]; sy += bufY[i]; sz += bufZ[i] }
            val mx = sx / n; val my = sy / n; val mz = sz / n
            // 2. 偏差ベクトルから max / mean / rms を集約
            var max = 0.0
            var sumLin = 0.0
            var sumSq = 0.0
            for (i in 0 until n) {
                val dx = bufX[i] - mx
                val dy = bufY[i] - my
                val dz = bufZ[i] - mz
                val sq = wx2 * dx * dx + wy2 * dy * dy + wz2 * dz * dz
                val v = sqrt(sq)
                if (v > max) max = v
                sumLin += v
                sumSq += sq
            }
            val mean = sumLin / n
            val rms = sqrt(sumSq / n)
            out.add(AccelBucket(curBucket, max, mean, rms))
            bufX.clear(); bufY.clear(); bufZ.clear()
        }

        file.bufferedReader().use { br ->
            br.readLine() // header
            while (true) {
                val line = br.readLine() ?: break
                if (line.isEmpty()) continue
                val parts = line.split(',')
                if (parts.size < 4) continue
                val t = parts[0].toLongOrNull() ?: continue
                val ax = parts[1].toDoubleOrNull() ?: continue
                val ay = parts[2].toDoubleOrNull() ?: continue
                val az = parts[3].toDoubleOrNull() ?: continue

                val b = (t / bucketMs) * bucketMs
                if (b != curBucket) {
                    flush()
                    curBucket = b
                }
                bufX.add(ax); bufY.add(ay); bufZ.add(az)
            }
            flush()
        }
        return out
    }

    private fun readGps(file: File): List<GpsPoint> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<GpsPoint>(256)
        file.bufferedReader().use { br ->
            br.readLine() // header
            while (true) {
                val line = br.readLine() ?: break
                if (line.isEmpty()) continue
                val parts = line.split(',')
                if (parts.size < 3) continue
                val t = parts[0].toLongOrNull() ?: continue
                val lat = parts[1].toDoubleOrNull() ?: continue
                val lon = parts[2].toDoubleOrNull() ?: continue
                out.add(GpsPoint(t, lat, lon))
            }
        }
        return out
    }

    private fun nearestGps(times: LongArray, t: Long): Int {
        var lo = 0
        var hi = times.size - 1
        if (t <= times[lo]) return lo
        if (t >= times[hi]) return hi
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        return if (t - times[lo] <= times[hi] - t) lo else hi
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
