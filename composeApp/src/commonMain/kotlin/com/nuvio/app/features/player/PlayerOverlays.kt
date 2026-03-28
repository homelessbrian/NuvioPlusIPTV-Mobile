package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvioTypeScale
import kotlin.math.max

@Composable
internal fun OpeningOverlay(
    artwork: String?,
    logo: String?,
    onBack: () -> Unit,
    horizontalSafePadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.85f)),
    ) {
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f),
                                Color.Black.copy(alpha = 0.6f),
                                Color.Black.copy(alpha = 0.8f),
                                Color.Black.copy(alpha = 0.9f),
                            ),
                        ),
                    ),
            )
        }

        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                .padding(top = 20.dp, start = horizontalSafePadding, end = horizontalSafePadding + 20.dp)
                ,
            containerColor = Color.Black.copy(alpha = 0.3f),
            contentColor = Color.White,
            buttonSize = 44.dp,
            iconSize = 24.dp,
            contentDescription = "Close player",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier
                        .width(300.dp)
                        .height(180.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(
                    color = Color(0xFFE50914),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(54.dp),
                )
            }
        }
    }
}

@Composable
internal fun GestureFeedbackPill(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = message,
            style = MaterialTheme.nuvioTypeScale.bodyLg.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
    }
}

@Composable
internal fun PauseMetadataOverlay(
    title: String,
    isEpisode: Boolean,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    streamSubtitle: String?,
    providerName: String,
    metrics: PlayerLayoutMetrics,
    horizontalSafePadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.85f),
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeContent)
            .padding(
                start = 24.dp + horizontalSafePadding,
                end = 24.dp + horizontalSafePadding,
                top = 24.dp,
                bottom = 24.dp,
            ),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = "You're watching",
            style = MaterialTheme.nuvioTypeScale.bodyLg,
            color = Color(0xFFB8B8B8),
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.nuvioTypeScale.displayMd.copy(
                fontSize = max(metrics.titleSize.value * 1.8f, 32f).sp,
                fontWeight = FontWeight.ExtraBold,
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        val episodeInfo = if (isEpisode && seasonNumber != null && episodeNumber != null) {
            buildString {
                append("S")
                append(seasonNumber)
                append(" • E")
                append(episodeNumber)
                if (!episodeTitle.isNullOrBlank()) {
                    append(" • ")
                    append(episodeTitle)
                }
            }
        } else {
            providerName
        }
        Text(
            text = episodeInfo,
            style = MaterialTheme.nuvioTypeScale.bodyLg,
            color = Color(0xFFCCCCCC),
        )
        if (!streamSubtitle.isNullOrBlank()) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = streamSubtitle,
                style = MaterialTheme.nuvioTypeScale.bodyLg.copy(lineHeight = 24.sp),
                color = Color(0xFFD6D6D6),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ErrorModal(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.8f)
                .widthIn(max = 400.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1A1A1A),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(32.dp),
                    )
                }
                Text(
                    text = "Playback error",
                    style = MaterialTheme.nuvioTypeScale.titleMd.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    text = message,
                    style = MaterialTheme.nuvioTypeScale.bodyLg.copy(lineHeight = 22.sp),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss),
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "Dismiss",
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.nuvioTypeScale.bodyLg.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
