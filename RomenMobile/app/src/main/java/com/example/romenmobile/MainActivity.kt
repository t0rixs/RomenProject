@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.romenmobile

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var repository: MobileRepository
    private var refresh by mutableStateOf(0)
    private var nearbyDevices by mutableStateOf<List<NearbyDevice>>(emptyList())
    private var isSearching by mutableStateOf(false)
    private var syncStatus by mutableStateOf("未接続")
    private var selectedAddress by mutableStateOf<String?>(null)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothSyncService.ACTION_SYNC_STATUS -> {
                    intent.getStringExtra(BluetoothSyncService.EXTRA_STATUS)?.let { syncStatus = it }
                }
                BluetoothSyncService.ACTION_SYNC_COMPLETE -> refresh++
            }
        }
    }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let(::addDevice)
                    if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED &&
                        device?.bondState == BluetoothDevice.BOND_BONDED &&
                        device.address == selectedAddress
                    ) {
                        BluetoothSyncService.start(this@MainActivity, device.address)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> isSearching = false
            }
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        loadBondedDevices()
        BluetoothSyncService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MobileRepository(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            tileFileSystemCacheMaxBytes = 20L * 1024 * 1024
            tileDownloadThreads = 2
            isMapViewHardwareAccelerated = true
        }
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(BluetoothSyncService.ACTION_SYNC_COMPLETE)
                addAction(BluetoothSyncService.ACTION_SYNC_STATUS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }, ContextCompat.RECEIVER_EXPORTED
        )
        requestBluetoothAndStart()
        if (isDebuggable()) runDebugProbe()
        selectedAddress = getSharedPreferences("bluetooth_sync", MODE_PRIVATE)
            .getString("device_address", null)
        setContent {
            MaterialTheme {
                MobileApp(
                    repository, refresh, nearbyDevices, isSearching, syncStatus, selectedAddress,
                    debugOpenRecordingId = intent.getStringExtra(EXTRA_OPEN_RECORDING),
                    onSearch = ::searchBluetoothDevices,
                    onConnect = ::selectAndConnect,
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }

    private fun isDebuggable() =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun runDebugProbe() {
        // 起動直後のUIと競合しないよう遅延実行
        Executors.newSingleThreadScheduledExecutor().schedule({
            MobileDebug.log("probe/start", "auto load heaviest recording")
            runCatching {
                val recs = repository.list()
                val target = recs.maxByOrNull { it.gpsCount } ?: return@runCatching
                MobileDebug.log("probe/target", "id=${target.id} gps=${target.gpsCount} photos=${target.photoCount}")
                val path = repository.mapPath(target)
                val photos = repository.photos(target)
                MobileDebug.log("probe/loaded", "path=${path.size} photos=${photos.size}")
            }.onFailure { MobileDebug.log("probe/error", it.message ?: it.toString()) }
        }, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun requestBluetoothAndStart() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            loadBondedDevices()
            BluetoothSyncService.start(this)
        } else permissions.launch(needed.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        if (!hasBluetoothPermission()) return
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.forEach(::addDevice)
    }

    @SuppressLint("MissingPermission")
    private fun searchBluetoothDevices() {
        if (!hasBluetoothPermission()) {
            requestBluetoothAndStart()
            return
        }
        loadBondedDevices()
        BluetoothAdapter.getDefaultAdapter()?.let { adapter ->
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            isSearching = adapter.startDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectAndConnect(device: NearbyDevice) {
        if (!hasBluetoothPermission()) {
            requestBluetoothAndStart()
            Toast.makeText(this, "Bluetooth権限が必要です", Toast.LENGTH_SHORT).show()
            return
        }
        val remote = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(device.address) ?: return
        selectedAddress = device.address
        syncStatus = "${device.name}に接続中…"
        if (remote.bondState != BluetoothDevice.BOND_BONDED) {
            syncStatus = "${device.name}とペアリング中…"
            remote.createBond()
        }
        BluetoothSyncService.start(this, device.address)
        Toast.makeText(this, "${device.name}への接続を開始しました", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) return
        val item = NearbyDevice(
            device.address,
            device.name ?: "名称不明",
            device.bondState == BluetoothDevice.BOND_BONDED,
        )
        nearbyDevices = (nearbyDevices.filterNot { it.address == item.address } + item)
            .sortedWith(compareByDescending<NearbyDevice> { it.bonded }.thenBy { it.name })
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    companion object {
        const val EXTRA_OPEN_RECORDING = "open_recording"
    }
}

private data class NearbyDevice(val address: String, val name: String, val bonded: Boolean)

@Composable
private fun MobileApp(
    repository: MobileRepository,
    refresh: Int,
    devices: List<NearbyDevice>,
    isSearching: Boolean,
    syncStatus: String,
    selectedAddress: String?,
    debugOpenRecordingId: String?,
    onSearch: () -> Unit,
    onConnect: (NearbyDevice) -> Unit,
) {
    var selected by remember { mutableStateOf<MobileRecording?>(null) }
    var recordings by remember { mutableStateOf<List<MobileRecording>>(emptyList()) }
    LaunchedEffect(refresh) {
        recordings = withContext(Dispatchers.IO) { repository.list() }
    }
    LaunchedEffect(recordings, debugOpenRecordingId) {
        if (!debugOpenRecordingId.isNullOrBlank() && selected == null) {
            recordings.find { it.id == debugOpenRecordingId }?.let {
                MobileDebug.log("debug/open_intent", debugOpenRecordingId)
                selected = it
            }
        }
    }
    if (selected == null) {
        RecordingList(recordings, devices, isSearching, syncStatus, selectedAddress, onSearch, onConnect) { selected = it }
    } else RecordingDetail(repository, selected!!, onBack = { selected = null })
}

@Composable
private fun RecordingList(
    recordings: List<MobileRecording>,
    devices: List<NearbyDevice>,
    isSearching: Boolean,
    syncStatus: String,
    selectedAddress: String?,
    onSearch: () -> Unit,
    onConnect: (NearbyDevice) -> Unit,
    onSelect: (MobileRecording) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("RomenMobile") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Bluetooth接続", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = onSearch, enabled = !isSearching) {
                                Text(if (isSearching) "検索中…" else "機器を検索")
                            }
                        }
                        Text("実機名は QCOM-BTD の場合があります。対象を選ぶと接続・自動同期を開始します。", style = MaterialTheme.typography.bodySmall)
                        Text("状態: $syncStatus", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        devices.forEach { device ->
                            val isSelected = device.address == selectedAddress
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name)
                                    Text(
                                        "${device.address} ${if (device.bonded) "・ペアリング済み" else "・未ペアリング"}${if (isSelected) " ・選択中" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Button(onClick = { onConnect(device) }) {
                                    Text(if (isSelected) "再接続" else "接続")
                                }
                            }
                        }
                    }
                }
            }
            if (recordings.isEmpty()) item { Text("同期された記録はまだありません") }
            items(recordings, key = { it.id }) { rec ->
                Card(Modifier.fillMaxWidth().clickable { onSelect(rec) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(formatTime(rec.startedAtMs), style = MaterialTheme.typography.titleMedium)
                        Text("GPS ${rec.gpsCount}点・振動 ${rec.accelCount}点・写真 ${rec.photoCount}枚", style = MaterialTheme.typography.bodySmall)
                        Text(rec.id, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private sealed interface DetailLoadState {
    data object Loading : DetailLoadState
    data class Ready(val path: List<MapPoint>, val photos: List<RecordingPhoto>) : DetailLoadState
}

@Composable
private fun RecordingDetail(repository: MobileRepository, recording: MobileRecording, onBack: () -> Unit) {
    var loadState by remember(recording.id) { mutableStateOf<DetailLoadState>(DetailLoadState.Loading) }
    LaunchedEffect(recording.id) {
        loadState = DetailLoadState.Loading
        MobileDebug.log("detail/open", "id=${recording.id} gps=${recording.gpsCount}")
        val loaded = withContext(Dispatchers.IO) {
            DetailLoadState.Ready(
                path = repository.mapPath(recording),
                photos = repository.photos(recording),
            )
        }
        MobileDebug.log("detail/ready", "id=${recording.id} path=${(loaded as DetailLoadState.Ready).path.size}")
        loadState = loaded
    }
    when (val state = loadState) {
        DetailLoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("記録を読み込み中…")
                    Button(onClick = onBack) { Text("戻る") }
                }
            }
        }
        is DetailLoadState.Ready -> RecordingDetailContent(recording, state.path, state.photos, onBack)
    }
}

@Composable
private fun RecordingDetailContent(
    recording: MobileRecording,
    path: List<MapPoint>,
    photos: List<RecordingPhoto>,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (path.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("表示できる位置データがありません")
            }
        } else {
            RecordingMap(path, photos, Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onBack) { Text("戻る") }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatTime(recording.startedAtMs), style = MaterialTheme.typography.titleSmall)
                    Text("GPS ${recording.gpsCount}点・写真 ${photos.size}枚", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(176.dp),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            if (photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("写真はありません", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text("写真 ${photos.size}枚", style = MaterialTheme.typography.titleSmall)
                    }
                    items(photos, key = { it.id }) { photo ->
                        PhotoListItem(recording, photo)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingMap(points: List<MapPoint>, photos: List<RecordingPhoto>, modifier: Modifier) {
    val geoPoints = remember(points) { points.map { GeoPoint(it.lat, it.lon) } }
    val bounds = remember(points) {
        BoundingBox(
            points.maxOf { it.lat },
            points.maxOf { it.lon },
            points.minOf { it.lat },
            points.minOf { it.lon },
        )
    }
    val dataKey = remember(points, photos) { "${points.size}_${photos.size}" }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MobileDebug.log("map/factory", "create MapView")
            MapView(context).apply {
                setMultiTouchControls(true)
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                setUseDataConnection(false)
                overlayManager.tilesOverlay.isEnabled = false
                onResume()
            }
        },
        update = { map ->
            if (geoPoints.size < 2 || map.tag == dataKey) return@AndroidView
            map.tag = dataKey
            map.post {
                MobileDebug.time("map/setup") {
                    map.overlays.removeAll { it is Polyline || it is Marker }
                    map.overlays.add(Polyline().apply {
                        setPoints(geoPoints)
                        outlinePaint.color = android.graphics.Color.rgb(25, 118, 210)
                        outlinePaint.strokeWidth = 6f
                    })
                    photos.filter { it.hasLocation }.forEach { photo ->
                        map.overlays.add(Marker(map).apply {
                            position = GeoPoint(photo.lat!!, photo.lon!!)
                            title = "撮影 ${formatTime(photo.capturedAtMs)}"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        })
                    }
                    val center = GeoPoint(
                        (bounds.latNorth + bounds.latSouth) / 2.0,
                        (bounds.lonEast + bounds.lonWest) / 2.0,
                    )
                    val zoom = zoomForBounds(bounds)
                    map.controller.setZoom(zoom)
                    map.controller.setCenter(center)
                    MobileDebug.log("map/setup/zoom", "zoom=$zoom center=${center.latitude},${center.longitude}")
                }
                map.postDelayed({
                    MobileDebug.log("map/tiles/enable", "deferred tile load")
                    map.setUseDataConnection(true)
                    map.overlayManager.tilesOverlay.isEnabled = true
                    map.invalidate()
                }, 400)
            }
        },
        onRelease = { map ->
            MobileDebug.log("map/release", "onPause+onDetach")
            map.onPause()
            map.onDetach()
        },
    )
}

private fun zoomForBounds(bounds: BoundingBox): Double {
    val latDiff = max(bounds.latNorth - bounds.latSouth, 0.0001)
    val lonDiff = max(bounds.lonEast - bounds.lonWest, 0.0001)
    val centerLat = (bounds.latNorth + bounds.latSouth) / 2.0
    val latZoom = ln(180.0 / latDiff) / ln(2.0)
    val lonZoom = ln(360.0 * cos(Math.toRadians(centerLat)) / lonDiff) / ln(2.0)
    return max(5.0, min(16.0, min(latZoom, lonZoom) - 0.8))
}

@Composable
private fun PhotoListItem(recording: MobileRecording, photo: RecordingPhoto) {
    var bitmap by remember(photo.fileName) { mutableStateOf<ImageBitmap?>(null) }
    val photoPath = remember(recording.id, photo.fileName) {
        recording.directory.resolve(photo.fileName).absolutePath
    }
    LaunchedEffect(photoPath) {
        MobileDebug.log("photo/decode/start", photoPath)
        bitmap = withContext(Dispatchers.IO) {
            MobileDebug.time("photo/decode") { decodePhotoThumbnail(photoPath, 240) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (val thumb = bitmap) {
                null -> Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                else -> Image(
                    thumb,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatTime(photo.capturedAtMs), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (photo.hasLocation) "${photo.lat!!.format(6)}, ${photo.lon!!.format(6)}"
                    else "位置情報なし",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun decodePhotoThumbnail(path: String, maxSide: Int) = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) sampleSize *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap()
}.getOrNull()

private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
private fun formatTime(ms: Long) = formatter.format(Date(ms))
private fun Double.format(digits: Int) = String.format(Locale.US, "%.${digits}f", this)
