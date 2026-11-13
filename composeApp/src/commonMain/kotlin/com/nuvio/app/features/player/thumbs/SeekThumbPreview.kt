package com.nuvio.app.features.player.thumbs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val PreviewShape = RoundedCornerShape(10.dp)
private val PreviewBack = Color(0xF20E1218)

/**
 * The seek thumbnail above the seek bar while you drag it: the picture (or why there's none) and
 * the time. Takes no room, so nothing on screen moves. Shows nothing while seek thumbnails are off.
 */
@Composable
internal fun SeekThumbAbove(
    positionMs: Long,
    fraction: Float,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!SeekThumbsPlatform.supported) return
    val settings by SeekThumbsPlatform.settings.collectAsState()
    if (!settings.enabled || !visible) return
    val version by SeekThumbsPlatform.version.collectAsState()
    val message by SeekThumbsPlatform.message.collectAsState()
    LaunchedEffect(positionMs) { SeekThumbsPlatform.want(positionMs) }
    val picture = remember(positionMs, version) { SeekThumbsPlatform.frame(positionMs) }
    if (picture == null && message == null) return
    Layout(
        content = {
            Column(
                Modifier.clip(PreviewShape).background(PreviewBack)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), PreviewShape)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    Modifier.size(width = 160.dp, height = 90.dp).clip(RoundedCornerShape(7.dp))
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (picture != null) {
                        Image(
                            bitmap = picture,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            message.orEmpty(),
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                    }
                }
                Text(
                    seekThumbTime(positionMs),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().height(18.dp)
                )
            }
        },
        modifier = modifier.fillMaxWidth().height(0.dp)
    ) { measurables, constraints ->
        val p = measurables.first().measure(Constraints())
        val width = constraints.maxWidth
        layout(width, 0) {
            val x = (width * fraction.coerceIn(0f, 1f) - p.width / 2f).roundToInt()
                .coerceIn(0, (width - p.width).coerceAtLeast(0))
            p.place(x, -p.height - 10.dp.roundToPx())
        }
    }
}

/** "1:02:03" / "12:04". */
internal fun seekThumbTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}
