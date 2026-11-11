package com.nuvio.app.features.livetv.multiview

import android.content.Context
import android.media.MediaDrm
import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.player.LivePlayRequest
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import com.nuvio.app.features.player.playbackMediaItemFromUrl
import org.json.JSONArray
import org.json.JSONObject

/** Only fixed, credential-free messages are allowed to reach a tile. */
internal class MultiviewSourceException(val tileMessage: String) : Exception()

@OptIn(UnstableApi::class)
internal fun multiviewMediaSource(
    context: Context,
    channel: LiveChannel,
    request: LivePlayRequest,
): MediaSource {
    val scheme = Uri.parse(request.url).scheme?.lowercase()
    if (scheme == "rtsp") {
        // Media3 RTSP uses its own sockets, outside the protected HTTP data-source path.
        throw MultiviewSourceException("RTSP streams are unavailable in Multiview.")
    }
    if (scheme != "http" && scheme != "https") {
        throw MultiviewSourceException("This stream format is unavailable in Multiview.")
    }
    val item = playbackMediaItemFromUrl(request.url, streamType = request.streamType).buildUpon()
        .setMediaId(channel.key)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
    val protection = channel.drm
    if (protection?.manifestType == "mpd") item.setMimeType(MimeTypes.APPLICATION_MPD)
    if (protection?.manifestType == "hls") item.setMimeType(MimeTypes.APPLICATION_M3U8)

    // This is the same VPN-wrapped, header-aware transport used by the regular player.
    // A manifest can point at another scheme. Use HTTP directly rather than DefaultDataSource,
    // which could dispatch an embedded UDP/file segment outside the protected transport.
    val protectedDataSourceFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(request.headers, recordDiagnostics = false)
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    val factory: MediaSource.Factory = if (item.build().localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
        HlsMediaSource.Factory(protectedDataSourceFactory).setExtractorFactory(
            DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true),
        )
    } else {
        DefaultMediaSourceFactory(protectedDataSourceFactory, extractors)
    }

    if (protection != null) {
        val uuid = when (protection.scheme.lowercase()) {
            "widevine" -> C.WIDEVINE_UUID
            "playready" -> C.PLAYREADY_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> throw MultiviewSourceException("This DRM scheme is unavailable in Multiview.")
        }
        if (!MediaDrm.isCryptoSchemeSupported(uuid)) {
            throw MultiviewSourceException("This protected stream is unavailable on this device.")
        }
        val license = protection.license.trim()
        val licenseScheme = Uri.parse(license).scheme?.lowercase()
        if (uuid == C.CLEARKEY_UUID && licenseScheme != "http" && licenseScheme != "https") {
            val keys = multiviewClearKeyJson(license)
                ?: throw MultiviewSourceException("The channel's ClearKey configuration is invalid.")
            factory.setDrmSessionManagerProvider(DrmSessionManagerProvider {
                DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(LocalMediaDrmCallback(keys))
            })
            item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(uuid).build())
        } else {
            if (licenseScheme != "http" && licenseScheme != "https") {
                throw MultiviewSourceException("The channel's DRM license address is unavailable in Multiview.")
            }
            val licenseHeaders = request.headers.filterKeys {
                it.equals("User-Agent", true) || it.equals("Referer", true) || it.equals("Origin", true)
            } + protection.licenseHeaders
            // createHttpDataSourceFactory returns the VPN-wrapped OkHttp HttpDataSource.Factory.
            val protectedLicenseFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(licenseHeaders, recordDiagnostics = false) as HttpDataSource.Factory
            factory.setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider().apply {
                setDrmHttpDataSourceFactory(protectedLicenseFactory)
            })
            item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(uuid)
                .setLicenseUri(license)
                .setLicenseRequestHeaders(licenseHeaders)
                .setMultiSession(false)
                .build())
        }
    }
    return factory.createMediaSource(item.build())
}

/** Matches the TV player's playlist formats while rejecting incomplete or invalid keys. */
internal fun multiviewClearKeyJson(raw: String): ByteArray? = runCatching {
    fun normalized(value: String): String {
        val trimmed = value.trim()
        val bytes = if (trimmed.length == 32 && trimmed.all { it in "0123456789abcdefABCDEF" }) {
            ByteArray(16) { trimmed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            Base64.decode(trimmed.replace('+', '-').replace('/', '_'), Base64.URL_SAFE or Base64.NO_WRAP)
        }
        require(bytes.size == 16)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    val keys = JSONArray()
    val text = raw.trim()
    if (text.startsWith("{")) {
        val supplied = JSONObject(text).getJSONArray("keys")
        require(supplied.length() in 1..32)
        for (index in 0 until supplied.length()) {
            val key = supplied.getJSONObject(index)
            require(key.optString("kty", "oct") == "oct")
            keys.put(JSONObject().put("kty", "oct").put("kid", normalized(key.getString("kid"))).put("k", normalized(key.getString("k"))))
        }
    } else {
        val pairs = text.split(',')
        require(pairs.size in 1..32)
        pairs.forEach { pair ->
            val parts = pair.split(':')
            require(parts.size == 2)
            keys.put(JSONObject().put("kty", "oct").put("kid", normalized(parts[0])).put("k", normalized(parts[1])))
        }
    }
    JSONObject().put("keys", keys).put("type", "temporary").toString().toByteArray(Charsets.UTF_8)
}.getOrNull()
