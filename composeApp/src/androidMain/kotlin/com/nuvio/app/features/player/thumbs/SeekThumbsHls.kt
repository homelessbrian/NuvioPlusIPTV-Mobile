package com.nuvio.app.features.player.thumbs

import android.os.SystemClock
import com.nuvio.app.features.livetv.stalker.StalkerMediaPolicy
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads a streamed (HLS) video for seek thumbnails: picks the lightest picture quality the link
 * offers (so a 4K stream is read from its smallest version), finds the piece at a time, and
 * saves just that piece (with its start file, decrypted when the link uses AES-128) to a temp
 * file Android's frame reader can open. Runs on the thumbnail thread only.
 */
internal class SeekThumbsHls(
    private val url: String,
    private val headers: Map<String, String>,
    private val policy: StalkerMediaPolicy?,
) {
    class Unsupported(message: String) : IOException(message)

    private class Range(val length: Long, val offset: Long)
    private class Key(val uri: String, val iv: ByteArray?)
    private class Segment(
        val uri: String,
        val startMs: Long,
        val durMs: Long,
        val sequence: Long,
        val range: Range?,
        val initUri: String?,
        val initRange: Range?,
        val key: Key?,
    )
    private class Playlist(val segments: List<Segment>, val ended: Boolean, val fetchedAt: Long)

    /** The chosen quality's height (0: not said) and whether it's HDR, once [prepare] ran. */
    var height = 0
        private set
    var hdr = false
        private set

    private var mediaUrl: String? = null
    private var playlist: Playlist? = null
    private val keys = HashMap<String, ByteArray>()

    private fun open(u: String, range: String? = null): HttpURLConnection {
        val h = if (policy != null) headers + policy.headersFor(u) else headers
        return PlayerPlaybackNetworking.openConnection(u, h, "GET", 15_000, 20_000, range)
    }

    private fun fetch(u: String): Pair<String, String> {
        val c = open(u)
        return try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } to c.url.toString()
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(base: String, ref: String): String =
        runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)

    private fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(line.substringAfter(':')).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return out
    }

    private fun range(text: String?, previousEnd: Long): Range? {
        if (text.isNullOrBlank()) return null
        val len = text.substringBefore('@').trim().toLongOrNull() ?: return null
        val off = text.substringAfter('@', "").trim().toLongOrNull() ?: previousEnd
        return Range(len, off)
    }

    /** Reads the link once (picks the version, reads its list of pieces). */
    fun prepare() { load(refresh = false) }

    private fun load(refresh: Boolean): Playlist {
        playlist?.let { p ->
            if (p.ended || !refresh || SystemClock.elapsedRealtime() - p.fetchedAt < 10_000L) return p
        }
        val media = mediaUrl ?: pickVariant().also { mediaUrl = it }
        val (text, servedFrom) = fetch(media)
        return parseMedia(text, servedFrom).also { playlist = it }
    }

    private fun pickVariant(): String {
        val (text, servedFrom) = fetch(url)
        if (!text.contains("#EXT-X-STREAM-INF")) return url
        class V(val uri: String, val bandwidth: Long, val height: Int, val hdr: Boolean, val audioOnly: Boolean)
        val variants = ArrayList<V>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-STREAM-INF") -> pending = attrs(l)
                pending != null && l.isNotEmpty() && !l.startsWith("#") -> {
                    val a = pending!!
                    val h = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() ?: 0
                    val codecs = a["CODECS"].orEmpty().lowercase()
                    val audioOnly = h == 0 && codecs.isNotEmpty() && listOf("avc", "hvc", "hev", "vp0", "vp9", "av01", "dvh").none { codecs.contains(it) }
                    variants += V(
                        resolve(servedFrom, l),
                        a["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE,
                        h,
                        a["VIDEO-RANGE"].orEmpty().let { it.equals("PQ", true) || it.equals("HLG", true) },
                        audioOnly,
                    )
                    pending = null
                }
            }
        }
        val withPictures = variants.filterNot { it.audioOnly }.ifEmpty { variants }
        val pick = withPictures.minWithOrNull(compareBy<V>({ it.hdr }, { if (it.height > 0) it.height else Int.MAX_VALUE }, { it.bandwidth }))
            ?: throw Unsupported("This stream couldn't be read for thumbnails")
        height = pick.height
        hdr = pick.hdr
        return pick.uri
    }

    private fun parseMedia(text: String, servedFrom: String): Playlist {
        if (text.contains("METHOD=SAMPLE-AES", ignoreCase = true)) throw Unsupported(SeekThumbMessages.PROTECTED)
        val out = ArrayList<Segment>()
        var sequence = 0L
        var dur = 0.0
        var at = 0L
        var nextRange: String? = null
        var lastEnd = 0L
        var initUri: String? = null
        var initRange: Range? = null
        var key: Key? = null
        var ended = false
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-ENDLIST") -> ended = true
                l.startsWith("#EXT-X-BYTERANGE:") -> nextRange = l.substringAfter(':')
                l.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(l)
                    initUri = a["URI"]?.let { resolve(servedFrom, it) }
                    initRange = range(a["BYTERANGE"], 0L)
                }
                l.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(l)
                    key = when (a["METHOD"]?.uppercase()) {
                        null, "NONE" -> null
                        "AES-128" -> Key(
                            resolve(servedFrom, a["URI"] ?: throw Unsupported(SeekThumbMessages.PROTECTED)),
                            a["IV"]?.let { hex(it) }
                        )
                        else -> throw Unsupported(SeekThumbMessages.PROTECTED)
                    }
                }
                l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                l.isNotEmpty() && !l.startsWith("#") -> {
                    val ms = (dur * 1000).toLong()
                    val r = range(nextRange, lastEnd)
                    if (r != null) lastEnd = r.offset + r.length
                    out += Segment(resolve(servedFrom, l), at, ms, sequence, r, initUri, initRange, key)
                    at += ms
                    sequence++
                    dur = 0.0
                    nextRange = null
                }
            }
        }
        if (out.isEmpty()) throw Unsupported("This stream couldn't be read for thumbnails")
        return Playlist(out, ended, SystemClock.elapsedRealtime())
    }

    private fun hex(text: String): ByteArray {
        val h = text.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Saves the piece at [ms] into [into]; returns how far into the piece [ms] is, or null when there's none. */
    fun pieceAt(ms: Long, into: File): Long? {
        val p = load(refresh = true)
        val s = p.segments.lastOrNull { it.startMs <= ms }?.takeIf { ms < it.startMs + it.durMs + 1_000 } ?: return null
        into.outputStream().buffered().use { out ->
            s.initUri?.let { copy(it, s.initRange, null, 0L, out) }
            copy(s.uri, s.range, s.key, s.sequence, out)
        }
        return ms - s.startMs
    }

    private fun keyBytes(k: Key): ByteArray = keys.getOrPut(k.uri) {
        val c = open(k.uri)
        try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    private fun copy(u: String, r: Range?, key: Key?, sequence: Long, out: OutputStream) {
        val c = open(u, r?.let { "bytes=${it.offset}-${it.offset + it.length - 1}" })
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            var input: InputStream = c.inputStream
            if (key != null) {
                val iv = key.iv ?: ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (sequence shr (8 * i)).toByte() }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(key), "AES"), IvParameterSpec(iv))
                input = CipherInputStream(input, cipher)
            }
            input.use { i ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > MAX_PIECE_BYTES) throw Unsupported("This stream's pieces are too large for thumbnails")
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val MAX_PIECE_BYTES = 48L * 1024 * 1024
    }
}
