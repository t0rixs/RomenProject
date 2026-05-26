package com.example.romenlogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.romenlogger.data.AccelSample
import kotlin.math.max
import kotlin.math.min

/**
 * 直近の加速度サンプル（X/Y/Z）を同一スケールの折れ線で表示する。
 */
@Composable
fun AccelLineChart(
    samples: List<AccelSample>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 160.dp,
) {
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "加速度 m/s² (X赤/Y緑/Z青)",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        if (samples.isEmpty()) {
            Text(
                text = "サンプル待ち…",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
            )
            return@Column
        }

        val colorX = Color(0xFFE53935)
        val colorY = Color(0xFF43A047)
        val colorZ = Color(0xFF1E88E5)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            val w = size.width
            val h = size.height
            val pad = 8f

            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            for (s in samples) {
                minV = min(minV, min(s.ax, min(s.ay, s.az)))
                maxV = max(maxV, max(s.ax, max(s.ay, s.az)))
            }
            if (minV == maxV) {
                minV -= 1f
                maxV += 1f
            }
            val span = (maxV - minV).coerceAtLeast(0.01f)

            fun yFor(v: Float): Float {
                val t = (v - minV) / span
                return h - pad - t * (h - 2 * pad)
            }

            val n = samples.size
            if (n < 2) return@Canvas /* single point: skip line */

            fun polyline(axis: (AccelSample) -> Float, color: Color) {
                val path = Path()
                samples.forEachIndexed { i, sample ->
                    val x = pad + (w - 2 * pad) * i / (n - 1).toFloat()
                    val y = yFor(axis(sample))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.5f))
            }

            polyline({ it.ax }, colorX)
            polyline({ it.ay }, colorY)
            polyline({ it.az }, colorZ)

            // 1G 付近の基準（概ね水平線）
            val g0 = yFor(0f)
            if (g0 in pad..h - pad) {
                drawLine(
                    color = scheme.outline.copy(alpha = 0.35f),
                    start = Offset(pad, g0),
                    end = Offset(w - pad, g0),
                    strokeWidth = 1f
                )
            }
        }
    }
}
