package com.example.romenlogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.location.Location
import com.example.romenlogger.data.AccelSample
import com.example.romenlogger.data.Recording

/** 1セッションの送信状態。 */
sealed class UploadStatus {
    object Idle : UploadStatus()
    object Sending : UploadStatus()
    object Sent : UploadStatus()
    data class Failed(val message: String) : UploadStatus()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isRecording: Boolean,
    isPaused: Boolean,
    accelSamples: List<AccelSample>,
    gpsPointCount: Int,
    currentLocation: Location?,
    recordings: List<Recording>,
    uploadStatuses: Map<String, UploadStatus>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPauseResume: () -> Unit,
    onDelete: (Recording) -> Unit,
    onSend: (Recording) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("RomenLogger") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRecording) Color(0xFFFFE0E0)
                            else if (isPaused) Color(0xFFFFF3CD)
                            else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isRecording -> "● 記録中"
                                    isPaused -> "Ⅱ 一時停止中"
                                    else -> "停止中"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isRecording) Color(0xFFC62828)
                                    else MaterialTheme.colorScheme.onSurface
                            )
                            if (isRecording) {
                                Text(
                                    text = "GPS: $gpsPointCount 点",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isRecording || isPaused) {
                            OutlinedButton(onClick = onPauseResume) {
                                Text(if (isPaused) "再開" else "一時停止")
                            }
                        }
                        Button(onClick = if (isRecording || isPaused) onStop else onStart) {
                            Text(if (isRecording || isPaused) "終了" else "開始")
                        }
                    }
                }
            }

            item { LocationMapCard(currentLocation = currentLocation, height = 140.dp) }

            if (isRecording) {
                item { AccelLineChart(samples = accelSamples, chartHeight = 120.dp) }
            }

            item {
                Column {
                    Text(
                        text = "履歴 (${recordings.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "PCに USB 接続後、行ごとに「PCへ送信」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                }
            }

            if (recordings.isEmpty()) {
                item {
                    Text(
                        text = "まだ記録はありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                items(recordings, key = { it.id }) { rec ->
                    RecordingRow(
                        recording = rec,
                        status = uploadStatuses[rec.id] ?: UploadStatus.Idle,
                        onDelete = { onDelete(rec) },
                        onSend = { onSend(rec) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    status: UploadStatus,
    onDelete: () -> Unit,
    onSend: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = Recording.formatTime(recording.startedAtMs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            val duration = recording.durationMs?.let(Recording::formatDuration)
                ?: "記録中または未完了"
            Text("時間: $duration", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "GPS: ${recording.gpsCount} 点 / 加速度: ${recording.accelCount} 点",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "ID: ${recording.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val (statusText, statusColor) = when (status) {
                UploadStatus.Idle -> "" to MaterialTheme.colorScheme.onSurfaceVariant
                UploadStatus.Sending -> "送信中…" to Color(0xFF1565C0)
                UploadStatus.Sent -> "✓ 送信済" to Color(0xFF2E7D32)
                is UploadStatus.Failed -> "✗ 失敗: ${status.message}" to Color(0xFFC62828)
            }
            if (statusText.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = onDelete) { Text("削除") }
                Button(
                    onClick = onSend,
                    enabled = status !is UploadStatus.Sending
                ) {
                    Text(
                        when (status) {
                            UploadStatus.Sending -> "送信中…"
                            UploadStatus.Sent -> "再送信"
                            else -> "📤 PCへ送信"
                        }
                    )
                }
            }
        }
    }
}
