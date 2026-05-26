package com.example.romenlogger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.romenlogger.log.AppLog
import com.example.romenlogger.map.GsiTileSource
import com.example.romenlogger.map.OsmdroidSetup
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos

private const val PrefsName = "map"
private const val PrefOfflineOnly = "offlineOnly"

/**
 * 加速度グラフの上などに差し込む、小さな現在地マップ。
 *
 * オフライン運用:
 *  - `/sdcard/Android/data/<pkg>/files/osmdroid/` に `.mbtiles` 等が置かれていれば
 *    自動的に MBTiles オフラインモード（ネットワーク完全未使用）。
 *  - 置かれていない場合でも「周辺を保存」で現在地を中心に一度タイルを
 *    キャッシュすれば、「オフライン」スイッチONでネットを一切使わず描画可能。
 */
@Composable
fun LocationMapCard(
    currentLocation: Location?,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
) {
    val context = LocalContext.current

    val prefs = remember { context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) }
    var offlineOnly by remember { mutableStateOf(prefs.getBoolean(PrefOfflineOnly, false)) }

    val archives = remember { OsmdroidSetup.listTileArchives(context) }
    val hasArchive = archives.isNotEmpty()

    // 画面表示中は Compose 側でも位置を購読（記録中でなくても即座に現在地を出すため）
    var liveLocation by remember { mutableStateOf(currentLocation) }
    LaunchedEffect(currentLocation) { currentLocation?.let { liveLocation = it } }
    DisposableEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) { liveLocation = loc }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    @SuppressLint("MissingPermission")
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
                    )
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    @SuppressLint("MissingPermission")
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper()
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        @SuppressLint("MissingPermission")
                        lm.requestLocationUpdates(
                            LocationManager.FUSED_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
                        )
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                AppLog.w("LocationMapCard 位置購読に失敗", t)
            }
        }
        onDispose {
            try { lm.removeUpdates(listener) } catch (_: Throwable) {}
        }
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                OsmMap(
                    currentLocation = liveLocation,
                    offlineOnly = offlineOnly,
                    hasArchive = hasArchive,
                    onMapReady = { mapViewRef = it },
                    modifier = Modifier.fillMaxSize()
                )
                if (liveLocation == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "現在地 取得中...",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xAA000000),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = summaryLine(liveLocation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "ｵﾌﾗｲﾝ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = offlineOnly,
                    onCheckedChange = { v ->
                        offlineOnly = v
                        prefs.edit().putBoolean(PrefOfflineOnly, v).apply()
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "出典: 地理院タイル",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (!hasArchive) {
                    val statusText = downloadStatus
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(0.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            val map = mapViewRef ?: return@OutlinedButton
                            val loc = liveLocation
                            if (loc == null) {
                                downloadStatus = "現在地未取得のため保存できません"
                                return@OutlinedButton
                            }
                            if (downloading) return@OutlinedButton
                            downloading = true
                            prefetchAround(context, map, loc) { msg, finished ->
                                downloadStatus = msg
                                if (finished) downloading = false
                            }
                        },
                        enabled = !downloading && liveLocation != null,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 2.dp
                        )
                    ) {
                        Text(
                            text = if (downloading) "保存中…" else "周辺を保存",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OsmMap(
    currentLocation: Location?,
    offlineOnly: Boolean,
    hasArchive: Boolean,
    onMapReady: (MapView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OsmdroidSetup.ensureInitialized(context) }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var accuracyOverlayRef by remember { mutableStateOf<AccuracyCircleOverlay?>(null) }
    var didCenterOnce by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            OsmdroidSetup.ensureInitialized(ctx)
            MapView(ctx).apply {
                setMultiTouchControls(true)
                setBuiltInZoomControls(false)
                minZoomLevel = 4.0
                maxZoomLevel = 19.0

                applyTileSource(this, ctx, offlineOnly = offlineOnly, hasArchive = hasArchive)

                val first = currentLocation ?: Location("init").apply {
                    latitude = 35.681236
                    longitude = 139.767125
                }
                controller.setZoom(16.0)
                controller.setCenter(GeoPoint(first.latitude, first.longitude))

                overlays.add(MyLocationNewOverlay(GpsMyLocationProvider(ctx), this).apply {
                    enableMyLocation()
                })
                val accuracy = AccuracyCircleOverlay()
                overlays.add(accuracy)
                accuracyOverlayRef = accuracy

                mapViewRef = this
                onMapReady(this)
            }
        },
        update = { map ->
            applyTileSource(map, context, offlineOnly = offlineOnly, hasArchive = hasArchive)
            currentLocation?.let { loc ->
                accuracyOverlayRef?.setLocation(loc)
                if (!didCenterOnce) {
                    map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                    didCenterOnce = true
                }
                map.invalidate()
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose { try { mapViewRef?.onDetach() } catch (_: Throwable) {} }
    }
}

/**
 * タイルソースを現在設定に合わせる。呼び出し毎に同一になるようオーダーに注意して
 * 差分のある時だけ実態を切り替える（副作用が重いため）。
 */
private fun applyTileSource(
    map: MapView,
    context: Context,
    offlineOnly: Boolean,
    hasArchive: Boolean,
) {
    // MBTiles 等があれば優先（ネットワーク不要で地図が出る）
    if (hasArchive) {
        if (map.tileProvider !is OfflineTileProvider) {
            try {
                val archives = OsmdroidSetup.listTileArchives(context)
                val provider = OfflineTileProvider(
                    SimpleRegisterReceiver(context),
                    archives.toTypedArray()
                )
                map.tileProvider = provider
                val sourceName = provider.archives.firstOrNull()
                    ?.tileSources?.firstOrNull()
                    ?: GsiTileSource.Standard.name()
                map.setTileSource(FileBasedTileSource.getSource(sourceName))
                map.setUseDataConnection(false)
                AppLog.i("地図モード: オフライン (archive source=$sourceName)")
            } catch (t: Throwable) {
                AppLog.e("オフラインプロバイダ初期化失敗", t)
            }
        }
        return
    }

    // アーカイブ無し: GSI 標準ソースで、ネット使用可否だけを切り替える
    val currentName = try { map.tileProvider?.tileSource?.name() } catch (_: Throwable) { null }
    if (currentName != GsiTileSource.Standard.name()) {
        map.setTileSource(GsiTileSource.Standard)
    }
    val desiredNet = !offlineOnly
    // osmdroid に明示的な getter は無いので毎回適用する（副作用は軽い）
    map.setUseDataConnection(desiredNet)
    AppLog.i("地図モード: ${if (desiredNet) "オンライン" else "完全オフライン (キャッシュのみ)"} (${GsiTileSource.Standard.name()})")
}

/**
 * 現在地を中心に、オンラインでタイルをキャッシュへ事前取得する。
 *
 * 半径は目安で 1.5km 四方。ズーム 13〜17 でだいたい 500 枚前後（数 MB）に収まる。
 */
private fun prefetchAround(
    context: Context,
    map: MapView,
    loc: Location,
    onStatus: (String, finished: Boolean) -> Unit,
) {
    val lat = loc.latitude
    val lon = loc.longitude
    val dLat = 0.015 // ≈ 1.6km
    val dLon = 0.015 / cos(Math.toRadians(lat)).coerceAtLeast(0.1)
    val bb = BoundingBox(
        lat + dLat, lon + dLon, lat - dLat, lon - dLon
    )
    val zoomMin = 13
    val zoomMax = 17

    // 事前取得は GSI オンライン経由。オフライン指定中でも一時的にネット有効化して取得。
    map.setTileSource(GsiTileSource.Standard)
    map.setUseDataConnection(true)
    // 取得完了後は Compose 側の update 再評価で applyTileSource が呼ばれ、
    // オフライン指定であれば setUseDataConnection(false) が再適用される。

    try {
        val mgr = CacheManager(map)
        mgr.downloadAreaAsync(context, bb, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                AppLog.i("タイル事前取得 完了")
                onStatus("保存完了", true)
            }
            override fun onTaskFailed(errors: Int) {
                AppLog.w("タイル事前取得 失敗 ($errors 件エラー)")
                onStatus("保存: $errors 件失敗", true)
            }
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                onStatus("保存中 $progress% (z=$currentZoomLevel)", false)
            }
            override fun downloadStarted() {
                AppLog.i("タイル事前取得 開始 bb=$bb z=$zoomMin..$zoomMax")
                onStatus("保存開始", false)
            }
            override fun setPossibleTilesInArea(total: Int) {
                onStatus("最大 $total タイル", false)
            }
        })
    } catch (t: Throwable) {
        AppLog.e("事前取得開始失敗", t)
        onStatus("保存できず: ${t.message}", true)
    }
}

private fun summaryLine(loc: Location?): String {
    if (loc == null) return "現在地: 未取得"
    val acc = if (loc.hasAccuracy()) "±${"%.0f".format(loc.accuracy)}m" else "精度不明"
    val age = ((System.currentTimeMillis() - loc.time) / 1000).coerceAtLeast(0)
    return "${"%.5f".format(loc.latitude)},${"%.5f".format(loc.longitude)} $acc ${age}s (${loc.provider ?: "-"})"
}

/** 精度円 Overlay */
private class AccuracyCircleOverlay : Overlay() {
    @Volatile private var location: Location? = null

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = AndroidColor.argb(40, 30, 136, 229)
        isAntiAlias = true
    }
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = AndroidColor.argb(160, 30, 136, 229)
        strokeWidth = 2f
        isAntiAlias = true
    }

    fun setLocation(loc: Location) { location = loc }

    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location ?: return
        if (!loc.hasAccuracy()) return
        val projection = mapView.projection
        val screen = projection.toPixels(GeoPoint(loc.latitude, loc.longitude), null)
        val radius = projection.metersToPixels(loc.accuracy)
        if (radius < 2f) return
        val path = Path().apply { addCircle(screen.x.toFloat(), screen.y.toFloat(), radius, Path.Direction.CW) }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }
}
