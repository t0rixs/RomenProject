package com.example.romenlogger.map

import android.content.Context
import com.example.romenlogger.log.AppLog
import org.osmdroid.config.Configuration
import java.io.File

/**
 * osmdroid の初期化と、オフライン用のファイル置き場のパス提供。
 *
 * ストレージ運用:
 *   - オンライン時のタイルキャッシュ: `filesDir/osmdroid/tiles`
 *   - オフライン用 MBTiles/zip/sqlite 等: `filesDir/osmdroid/`
 *
 * 端末に PC から MBTiles を入れる例（アプリ内部ストレージ、権限不要）:
 *   adb push tiles.mbtiles /sdcard/Android/data/com.example.romenlogger/files/osmdroid/
 */
object OsmdroidSetup {

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val conf = Configuration.getInstance()
            // androidx.preference に依存しないよう、専用の SharedPreferences を使う
            val prefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
            conf.load(context, prefs)
            conf.userAgentValue = "RomenLogger/${context.packageName}"

            val base = offlineBaseDir(context)
            val cache = File(base, "tiles")
            base.mkdirs()
            cache.mkdirs()
            conf.osmdroidBasePath = base
            conf.osmdroidTileCache = cache

            AppLog.i("osmdroid init: base=$base cache=$cache")
            val tileArchives = listTileArchives(context)
            if (tileArchives.isNotEmpty()) {
                AppLog.i("検出されたオフライン地図: ${tileArchives.map { it.name }}")
            } else {
                AppLog.i("オフライン地図アーカイブは見つかりませんでした (配置場所: $base)")
            }
            initialized = true
        }
    }

    /** osmdroid がオフライン地図を探すフォルダ。ここに .mbtiles などを置く。 */
    fun offlineBaseDir(context: Context): File = File(context.filesDir, "osmdroid")

    /** 端末上に置かれたタイルアーカイブ一覧（UI 表示や有無判定に使う）。 */
    fun listTileArchives(context: Context): List<File> {
        val base = offlineBaseDir(context)
        val exts = setOf("mbtiles", "sqlite", "gemf", "zip", "map")
        return (base.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in exts
        } ?: emptyList()).sortedBy { it.name }
    }
}
