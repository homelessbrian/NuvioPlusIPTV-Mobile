package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DetailCastSection(
    cast: List<String>,
    modifier: Modifier = Modifier,
) {
    if (cast.isEmpty()) return

    DetailSection(
        title = "Cast",
        modifier = modifier,
    ) {
        BoxWithConstraints {
            val sizing = castSectionSizing(maxWidth.value)

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(sizing.avatarGap),
            ) {
                items(cast) { name ->
                    CastItem(
                        name = name,
                        sizing = sizing,
                    )
                }
            }
        }
    }
}

@Composable
private fun CastItem(
    name: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    sizing: CastSectionSizing,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(sizing.avatarSize)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.initials(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = sizing.nameLabelSize,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subLabel.isNullOrBlank()) {
            Text(
                text = subLabel,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = sizing.subLabelSize,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class CastSectionSizing(
    val avatarSize: androidx.compose.ui.unit.Dp,
    val avatarGap: androidx.compose.ui.unit.Dp,
    val nameLabelSize: TextUnit,
    val subLabelSize: TextUnit,
)

private fun castSectionSizing(maxWidthDp: Float): CastSectionSizing =
    when {
        maxWidthDp >= 1200f -> CastSectionSizing(
            avatarSize = 100.dp,
            avatarGap = 20.dp,
            nameLabelSize = 16.sp,
            subLabelSize = 14.sp,
        )
        maxWidthDp >= 840f -> CastSectionSizing(
            avatarSize = 90.dp,
            avatarGap = 18.dp,
            nameLabelSize = 15.sp,
            subLabelSize = 13.sp,
        )
        maxWidthDp >= 600f -> CastSectionSizing(
            avatarSize = 85.dp,
            avatarGap = 16.dp,
            nameLabelSize = 14.sp,
            subLabelSize = 12.sp,
        )
        else -> CastSectionSizing(
            avatarSize = 80.dp,
            avatarGap = 16.dp,
            nameLabelSize = 14.sp,
            subLabelSize = 12.sp,
        )
    }

private fun String.initials(): String {
    val parts = trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first().uppercaseChar()}${parts.last().first().uppercaseChar()}"
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> ""
    }
}
