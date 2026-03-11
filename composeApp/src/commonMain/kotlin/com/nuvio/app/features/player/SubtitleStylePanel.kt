package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

@Composable
fun SubtitleStylePanel(
    style: SubtitleStyleState,
    useCustomSubtitles: Boolean,
    isCompact: Boolean,
    onStyleChanged: (SubtitleStyleState) -> Unit,
) {
    val sectionPadding = if (isCompact) 12.dp else 16.dp
    val gap = if (isCompact) 12.dp else 16.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        LivePreviewCard(
            style = style,
            useCustomSubtitles = useCustomSubtitles,
            isCompact = isCompact,
            sectionPadding = sectionPadding,
        )

        if (useCustomSubtitles) {
            QuickPresetsCard(
                style = style,
                isCompact = isCompact,
                sectionPadding = sectionPadding,
                onStyleChanged = onStyleChanged,
            )
        }

        CoreControlsCard(
            style = style,
            useCustomSubtitles = useCustomSubtitles,
            isCompact = isCompact,
            sectionPadding = sectionPadding,
            onStyleChanged = onStyleChanged,
        )

        AdvancedControlsCard(
            style = style,
            useCustomSubtitles = useCustomSubtitles,
            isCompact = isCompact,
            sectionPadding = sectionPadding,
            onStyleChanged = onStyleChanged,
        )

        TimingOffsetSection(
            style = style,
            useCustomSubtitles = useCustomSubtitles,
            isCompact = isCompact,
            sectionPadding = sectionPadding,
            onStyleChanged = onStyleChanged,
        )
    }
}

@Composable
private fun LivePreviewCard(
    style: SubtitleStyleState,
    useCustomSubtitles: Boolean,
    isCompact: Boolean,
    sectionPadding: androidx.compose.ui.unit.Dp,
) {
    val previewHeight = if (isCompact) 90.dp else 120.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(sectionPadding),
    ) {
        SectionHeader(
            icon = Icons.Rounded.Visibility,
            label = "Preview",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val bgOpacity = if (useCustomSubtitles && style.backgroundEnabled) style.backgroundOpacity else 0f
            val align = style.alignment

            Box(
                modifier = Modifier
                    .padding(bottom = min(80, style.bottomOffset).dp, start = 10.dp, end = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = bgOpacity))
                    .padding(horizontal = if (isCompact) 10.dp else 12.dp, vertical = if (isCompact) 6.dp else 8.dp),
            ) {
                Text(
                    text = "The quick brown fox jumps over the lazy dog.",
                    color = style.textColor,
                    fontSize = style.fontSize.sp,
                    letterSpacing = style.letterSpacing.sp,
                    lineHeight = (style.fontSize * style.lineHeightMultiplier).sp,
                    textAlign = align,
                    style = TextStyle(
                        shadow = if (style.textShadowEnabled) Shadow(
                            color = Color.Black,
                            offset = Offset(1f, 1f),
                            blurRadius = 3f,
                        ) else null,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPresetsCard(
    style: SubtitleStyleState,
    isCompact: Boolean,
    sectionPadding: androidx.compose.ui.unit.Dp,
    onStyleChanged: (SubtitleStyleState) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(sectionPadding),
    ) {
        SectionHeader(
            icon = Icons.Rounded.Star,
            label = "Presets",
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubtitlePreset.entries.forEach { preset ->
                val chipPadH = if (isCompact) 8.dp else 12.dp
                val chipPadV = if (isCompact) 6.dp else 8.dp
                val chipSize = if (isCompact) 11.sp else 12.sp

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                        .clickable { onStyleChanged(SubtitleStyleState.fromPreset(preset)) }
                        .padding(horizontal = chipPadH, vertical = chipPadV),
                ) {
                    Text(
                        text = preset.label,
                        color = Color.White,
                        fontSize = chipSize,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreControlsCard(
    style: SubtitleStyleState,
    useCustomSubtitles: Boolean,
    isCompact: Boolean,
    sectionPadding: androidx.compose.ui.unit.Dp,
    onStyleChanged: (SubtitleStyleState) -> Unit,
) {
    val btnSize = if (isCompact) 28.dp else 32.dp
    val btnRadius = if (isCompact) 14.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(sectionPadding),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 12.dp else 16.dp),
    ) {
        SectionHeader(
            icon = Icons.Rounded.Tune,
            label = "Core",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FormatSize,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Font Size",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            StepperControl(
                value = style.fontSize.toString(),
                onMinus = { onStyleChanged(style.copy(fontSize = (style.fontSize - 1).coerceAtLeast(8))) },
                onPlus = { onStyleChanged(style.copy(fontSize = (style.fontSize + 1).coerceAtMost(40))) },
                buttonSize = btnSize,
                buttonRadius = btnRadius,
            )
        }

        if (useCustomSubtitles) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Background",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = style.backgroundEnabled,
                    onCheckedChange = { onStyleChanged(style.copy(backgroundEnabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color.White,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedControlsCard(
    style: SubtitleStyleState,
    useCustomSubtitles: Boolean,
    isCompact: Boolean,
    sectionPadding: androidx.compose.ui.unit.Dp,
    onStyleChanged: (SubtitleStyleState) -> Unit,
) {
    val btnSize = if (isCompact) 28.dp else 32.dp
    val btnRadius = if (isCompact) 14.dp else 16.dp
    val headerLabel = if (useCustomSubtitles) "Advanced" else "Position"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(sectionPadding),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 12.dp else 16.dp),
    ) {
        SectionHeader(
            icon = Icons.Rounded.Build,
            label = headerLabel,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubtitleColorSwatches.forEach { color ->
                val isSelected = style.textColor == color
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            2.dp,
                            if (isSelected) Color.White else Color.White.copy(alpha = 0.3f),
                            CircleShape,
                        )
                        .clickable { onStyleChanged(style.copy(textColor = color)) },
                )
            }
        }

        if (useCustomSubtitles) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AlignmentButton(
                    icon = Icons.AutoMirrored.Rounded.FormatAlignLeft,
                    isSelected = style.alignment == TextAlign.Left,
                    onClick = { onStyleChanged(style.copy(alignment = TextAlign.Left)) },
                )
                AlignmentButton(
                    icon = Icons.Rounded.FormatAlignCenter,
                    isSelected = style.alignment == TextAlign.Center,
                    onClick = { onStyleChanged(style.copy(alignment = TextAlign.Center)) },
                )
                AlignmentButton(
                    icon = Icons.AutoMirrored.Rounded.FormatAlignRight,
                    isSelected = style.alignment == TextAlign.Right,
                    onClick = { onStyleChanged(style.copy(alignment = TextAlign.Right)) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Bottom Offset",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            StepperControl(
                value = style.bottomOffset.toString(),
                onMinus = { onStyleChanged(style.copy(bottomOffset = (style.bottomOffset - 5).coerceAtLeast(0))) },
                onPlus = { onStyleChanged(style.copy(bottomOffset = (style.bottomOffset + 5).coerceAtMost(200))) },
                buttonSize = btnSize,
                buttonRadius = btnRadius,
                minWidth = 46.dp,
                minusIcon = Icons.Rounded.KeyboardArrowDown,
                plusIcon = Icons.Rounded.KeyboardArrowUp,
            )
        }

        if (useCustomSubtitles) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Bg Opacity",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                StepperControl(
                    value = String.format("%.1f", style.backgroundOpacity),
                    onMinus = { onStyleChanged(style.copy(backgroundOpacity = (style.backgroundOpacity - 0.1f).coerceAtLeast(0f))) },
                    onPlus = { onStyleChanged(style.copy(backgroundOpacity = (style.backgroundOpacity + 0.1f).coerceAtMost(1f))) },
                    buttonSize = btnSize,
                    buttonRadius = btnRadius,
                    minWidth = 48.dp,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Text Shadow",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (style.textShadowEnabled) Color.White.copy(alpha = 0.18f)
                            else Color.White.copy(alpha = 0.08f)
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .clickable { onStyleChanged(style.copy(textShadowEnabled = !style.textShadowEnabled)) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (style.textShadowEnabled) "On" else "Off",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Outline",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (style.outlineEnabled) Color.White.copy(alpha = 0.18f)
                            else Color.White.copy(alpha = 0.08f)
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .clickable { onStyleChanged(style.copy(outlineEnabled = !style.outlineEnabled)) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (style.outlineEnabled) "On" else "Off",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }

            if (style.outlineEnabled) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlineColorSwatches.forEach { color ->
                        val isSelected = style.outlineColor == color
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    2.dp,
                                    if (isSelected) Color.White else Color.White.copy(alpha = 0.3f),
                                    CircleShape,
                                )
                                .clickable { onStyleChanged(style.copy(outlineColor = color)) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Outline Width",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    StepperControl(
                        value = String.format("%.1f", style.outlineWidth),
                        onMinus = { onStyleChanged(style.copy(outlineWidth = (style.outlineWidth - 0.5f).coerceAtLeast(0.5f))) },
                        onPlus = { onStyleChanged(style.copy(outlineWidth = (style.outlineWidth + 0.5f).coerceAtMost(5f))) },
                        buttonSize = btnSize,
                        buttonRadius = btnRadius,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Letter Spacing",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                StepperControl(
                    value = String.format("%.1f", style.letterSpacing),
                    onMinus = { onStyleChanged(style.copy(letterSpacing = (style.letterSpacing - 0.5f).coerceAtLeast(-2f))) },
                    onPlus = { onStyleChanged(style.copy(letterSpacing = (style.letterSpacing + 0.5f).coerceAtMost(5f))) },
                    buttonSize = btnSize,
                    buttonRadius = btnRadius,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Line Height",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                StepperControl(
                    value = String.format("%.1f", style.lineHeightMultiplier),
                    onMinus = { onStyleChanged(style.copy(lineHeightMultiplier = (style.lineHeightMultiplier - 0.1f).coerceAtLeast(1f))) },
                    onPlus = { onStyleChanged(style.copy(lineHeightMultiplier = (style.lineHeightMultiplier + 0.1f).coerceAtMost(2.5f))) },
                    buttonSize = btnSize,
                    buttonRadius = btnRadius,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Outline",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (style.outlineEnabled) Color.White.copy(alpha = 0.18f)
                            else Color.White.copy(alpha = 0.08f)
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .clickable { onStyleChanged(style.copy(outlineEnabled = !style.outlineEnabled)) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (style.outlineEnabled) "On" else "Off",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .clickable { onStyleChanged(SubtitleStyleState.DEFAULT) }
                    .padding(horizontal = if (isCompact) 8.dp else 12.dp, vertical = if (isCompact) 6.dp else 8.dp),
            ) {
                Text(
                    text = "Reset Defaults",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (isCompact) 12.sp else 14.sp,
                )
            }
        }
    }
}

@Composable
private fun TimingOffsetSection(
    style: SubtitleStyleState,
    useCustomSubtitles: Boolean,
    isCompact: Boolean,
    sectionPadding: androidx.compose.ui.unit.Dp,
    onStyleChanged: (SubtitleStyleState) -> Unit,
) {
    if (!useCustomSubtitles) return

    val btnSize = if (isCompact) 28.dp else 32.dp
    val btnRadius = if (isCompact) 14.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(sectionPadding),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Timing Offset",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            StepperControl(
                value = String.format("%.1fs", style.timingOffsetMs / 1000f),
                onMinus = { onStyleChanged(style.copy(timingOffsetMs = style.timingOffsetMs - 250L)) },
                onPlus = { onStyleChanged(style.copy(timingOffsetMs = style.timingOffsetMs + 250L)) },
                buttonSize = btnSize,
                buttonRadius = btnRadius,
                minWidth = 60.dp,
            )
        }

        Text(
            text = "Adjust if subtitles are out of sync with audio",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StepperControl(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp,
    buttonRadius: androidx.compose.ui.unit.Dp,
    minWidth: androidx.compose.ui.unit.Dp = 42.dp,
    minusIcon: ImageVector = Icons.Rounded.Remove,
    plusIcon: ImageVector = Icons.Rounded.Add,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(RoundedCornerShape(buttonRadius))
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(onClick = onMinus),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = minusIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }

        Box(
            modifier = Modifier
                .widthIn(min = minWidth)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }

        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(RoundedCornerShape(buttonRadius))
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(onClick = onPlus),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = plusIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    label: String,
) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AlignmentButton(
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) Color.White.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.08f)
            )
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}
