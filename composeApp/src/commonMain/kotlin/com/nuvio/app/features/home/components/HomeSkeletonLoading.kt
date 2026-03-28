package com.nuvio.app.features.home.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
private fun rememberHomeSkeletonBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surface,
    )
    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )
    return brush
}

@Composable
fun HomeSkeletonHero(modifier: Modifier = Modifier) {
    val brush = rememberHomeSkeletonBrush()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
    ) {
        val heroHeight = (maxWidth.value * 1.22f).dp.coerceIn(440.dp, 800.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(brush),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.04f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.42f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                SkeletonBlock(
                    brush = brush,
                    width = 220.dp,
                    height = 64.dp,
                    cornerRadius = 20.dp,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row {
                    SkeletonBlock(brush = brush, width = 72.dp, height = 14.dp, cornerRadius = 999.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    SkeletonDot(brush = brush)
                    Spacer(modifier = Modifier.width(8.dp))
                    SkeletonBlock(brush = brush, width = 88.dp, height = 14.dp, cornerRadius = 999.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    SkeletonDot(brush = brush)
                    Spacer(modifier = Modifier.width(8.dp))
                    SkeletonBlock(brush = brush, width = 54.dp, height = 14.dp, cornerRadius = 999.dp)
                }
                Spacer(modifier = Modifier.height(18.dp))
                SkeletonBlock(
                    brush = brush,
                    width = 148.dp,
                    height = 48.dp,
                    cornerRadius = 40.dp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row {
                    repeat(4) { index ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        SkeletonBlock(
                            brush = brush,
                            width = if (index == 0) 32.dp else 8.dp,
                            height = 8.dp,
                            cornerRadius = 999.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeSkeletonRow(modifier: Modifier = Modifier) {
    val brush = rememberHomeSkeletonBrush()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title placeholder
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush),
        )
        // Accent bar
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.height(2.dp))
        // Poster row
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(163.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(brush),
                )
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    brush: Brush,
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush),
    )
}

@Composable
private fun SkeletonDot(brush: Brush) {
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(brush),
    )
}
