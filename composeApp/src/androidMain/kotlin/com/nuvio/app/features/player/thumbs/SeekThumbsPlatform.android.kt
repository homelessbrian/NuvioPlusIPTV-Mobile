package com.nuvio.app.features.player.thumbs

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nuvio.app.features.livetv.record.RecordingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Seek thumbnails on Android, made with Android's own frame reader (the decoding happens in
 * Android's media service, not the app's memory). The idea follows Nuvio Arc's seek thumbnails
 * (ysosrs123/NuvioTV-Fork, GPL-3.0). Same engine as the TV app: one video at a time, small
 * pictures (about 240x135) in a size-capped cache folder plus a few in memory.
 */
actual object SeekThumbsPlatform {
    private const val TAG = "SeekThumbs"
    actual val supported: Boolean = true

    private const val THUMB_W = 240
    private const val THUMB_H = 135
    private const val MB = 1024L * 1024L
    private const val MEMORY_BYTES = 3 * 1024 * 1024
    private const val APP_HEADROOM_BYTES = 24L * MB
    private const val SEEK_ACTIVE_MS = 8_000L
    private const val FOUR_K = "4k|"

    // ------------------------------------------------------------------ settings

    private const val FILE = "seek_thumbnails"
    private const val ENABLED = "enabled"
    private const val INCLUDE_4K = "include_4k"
    private const val WHEN = "when"
    private const val STORAGE_MB = "storage_mb"
    private const val LOW_MEMORY_AT = "low_memory_at"

    private fun context(): Context? = RecordingPlatform.context()

    private val _settings = MutableStateFlow(SeekThumbValues())
    @Volatile private var loaded = false

    private val settingsFlow = _settings.asStateFlow()
    actual val settings: StateFlow<SeekThumbValues>
        get() { load(); return settingsFlow }

    private fun load(): SeekThumbValues {
        if (loaded) return _settings.value
        val ctx = context() ?: return _settings.value
        synchronized(this) {
            if (loaded) return _settings.value
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _settings.value = SeekThumbValues(
                enabled = p.getBoolean(ENABLED, false),
                // Not chosen yet: on for devices with plenty of memory, off for lower-end ones (anyone can change it).
                include4k = if (p.contains(INCLUDE_4K)) p.getBoolean(INCLUDE_4K, false) else !isLowMemoryDevice(),
                whenToMake = runCatching { SeekThumbWhen.valueOf(p.getString(WHEN, null) ?: SeekThumbWhen.SEEKING.name) }.getOrDefault(SeekThumbWhen.SEEKING),
                storageMb = p.getInt(STORAGE_MB, 50).takeIf { it in SEEK_THUMB_STORAGE_CHOICES } ?: 50,
                lastLowMemoryAt = p.getLong(LOW_MEMORY_AT, 0L),
            )
            loaded = true
        }
        return _settings.value
    }

    actual fun updateSettings(change: (SeekThumbValues) -> SeekThumbValues) {
        load()
        val next = change(_settings.value)
        _settings.value = next
        context()?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(ENABLED, next.enabled)
            ?.putBoolean(INCLUDE_4K, next.include4k)
            ?.putString(WHEN, next.whenToMake.name)
            ?.putInt(STORAGE_MB, next.storageMb)
            ?.putLong(LOW_MEMORY_AT, next.lastLowMemoryAt)
            ?.apply()
    }

    actual fun isLowMemoryDevice(): Boolean = runCatching {
        val am = context()?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        am.isLowRamDevice || am.memoryClass <= 192
    }.getOrDefault(false)

    // ------------------------------------------------------------------ state

    private val _version = MutableStateFlow(0)
    actual val version: StateFlow<Int> = _version.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    actual val message: StateFlow<String?> = _message.asStateFlow()

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val images = object : LruCache<String, ImageBitmap>(64) {}
    private val lock = Any()
    private var session: Session? = null
    private val failed = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "SeekThumbLoad").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    private fun setMessage(m: String?) { if (_message.value != m) _message.value = m }

    actual fun open(source: SeekThumbSource, durationMs: Long, videoHeight: Int, playback: SeekThumbPlayback) {
        val ctx = context() ?: return
        val s = load()
        synchronized(lock) {
            if (!s.enabled || durationMs < 30_000L) { session?.stop(); session = null; setMessage(null); return }
            if (!s.include4k && source !is SeekThumbSource.Hls && videoHeight > 1088) {
                session?.stop(); session = null; setMessage(SeekThumbMessages.FOUR_K_OFF); return
            }
            (failed[source.key] ?: failed[FOUR_K + source.key]?.takeIf { !s.include4k })?.let { reason ->
                session?.stop(); session = null; setMessage(reason); return
            }
            val interval = intervalFor(durationMs)
            val existing = session
            if (existing != null && existing.source.key == source.key && existing.intervalMs == interval &&
                existing.alive && existing.mode == s.whenToMake) {
                existing.playback = playback
                existing.durationMs = durationMs
                return
            }
            existing?.stop()
            session = Session(ctx.applicationContext, source, durationMs, interval, playback, s.whenToMake, s.include4k).also {
                setMessage(SeekThumbMessages.MAKING)
                it.start()
            }
        }
    }

    actual fun unsupported(reason: String) {
        synchronized(lock) { session?.stop(); session = null }
        setMessage(reason)
    }

    actual fun close() {
        synchronized(lock) { session?.stop(); session = null }
        setMessage(null)
    }

    actual fun want(positionMs: Long) {
        synchronized(lock) { session }?.want(positionMs)
    }

    actual fun frame(positionMs: Long): ImageBitmap? {
        val s = synchronized(lock) { session } ?: return null
        val (key, bmp) = s.frame(positionMs) ?: return null
        images.get(key)?.let { return it }
        return bmp.asImageBitmap().also { images.put(key, it) }
    }

    private fun intervalFor(durationMs: Long): Long =
        ((durationMs / 300L).coerceIn(10_000L, 60_000L) / 1000L) * 1000L

    // ------------------------------------------------------------------ storage

    private fun root(context: Context): File = File(context.cacheDir, "seek_thumbs")

    actual fun savedBytes(): Long {
        val ctx = context() ?: return 0L
        return root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    actual fun clearAll() {
        val ctx = context() ?: return
        synchronized(lock) { session?.forgetSaved() }
        memory.evictAll()
        images.evictAll()
        failed.clear()
        root(ctx).listFiles()?.forEach { it.deleteRecursively() }
        _version.value++
    }

    private fun trim(context: Context, keep: File?) {
        val limit = load().storageMb * MB
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return
        val sized = dirs.map { d -> Triple(d, d.lastModified(), d.listFiles()?.sumOf { it.length() } ?: 0L) }
        var total = sized.sumOf { it.third }
        if (total <= limit) return
        for ((d, _, size) in sized.sortedBy { it.second }) {
            if (total <= limit) break
            if (d == keep) continue
            d.deleteRecursively()
            total -= size
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun memoryTight(context: Context): Boolean {
        val rt = Runtime.getRuntime()
        if (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) < APP_HEADROOM_BYTES) return true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.lowMemory
    }

    // ------------------------------------------------------------------ one video

    private class Session(
        val context: Context,
        val source: SeekThumbSource,
        @Volatile var durationMs: Long,
        val intervalMs: Long,
        @Volatile var playback: SeekThumbPlayback,
        val mode: SeekThumbWhen,
        val include4k: Boolean,
    ) {
        @Volatile var alive = true
        private val waiter = Object()
        private val count = (durationMs / intervalMs + 1).toInt().coerceAtMost(3_000)
        /** 0: not tried, 1: saved, 2: tried, no picture. */
        private val state = ByteArray(count)
        private val dir = File(root(context), sha1(source.key + "|" + intervalMs))
        private val loading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
        @Volatile private var target = -1
        @Volatile private var lastSeekAt = 0L
        private var retriever: MediaMetadataRetriever? = null
        private var hls: SeekThumbsHls? = null
        private var made = 0
        private var misses = 0
        private var failures = 0
        private var lowMemoryNoted = false
        private val thread = Thread({ run() }, "SeekThumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }

        fun start() = thread.start()

        fun stop() {
            alive = false
            synchronized(waiter) { waiter.notifyAll() }
        }

        fun forgetSaved() {
            synchronized(state) { for (i in state.indices) if (state[i] == 1.toByte()) state[i] = 0 }
        }

        private fun index(positionMs: Long): Int =
            (positionMs.toDouble() / intervalMs).roundToLong().toInt().coerceIn(0, count - 1)

        private fun memKey(i: Int) = dir.name + ":" + i

        fun want(positionMs: Long) {
            target = index(positionMs)
            lastSeekAt = SystemClock.elapsedRealtime()
            synchronized(waiter) { waiter.notifyAll() }
        }

        fun frame(positionMs: Long): Pair<String, Bitmap>? {
            val i = index(positionMs)
            memory.get(memKey(i))?.let { return memKey(i) to it }
            if (state[i] == 1.toByte()) load(i)
            for (d in 1..2) {
                memory.get(memKey(i - d))?.let { return memKey(i - d) to it }
                memory.get(memKey(i + d))?.let { return memKey(i + d) to it }
            }
            return null
        }

        private fun load(i: Int) {
            if (!loading.add(i)) return
            loader.execute {
                try {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeFile(File(dir, "$i.jpg").path, opts)?.let {
                        memory.put(memKey(i), it)
                        _version.value++
                    }
                } catch (_: Throwable) {
                } finally {
                    loading.remove(i)
                }
            }
        }

        private fun waitFor(ms: Long) {
            synchronized(waiter) { if (alive) waiter.wait(ms) }
        }

        private fun run() {
            try {
                dir.mkdirs()
                dir.setLastModified(System.currentTimeMillis())
                dir.listFiles()?.forEach { f ->
                    f.name.removeSuffix(".jpg").toIntOrNull()?.let { if (it in 0 until count) state[it] = 1 }
                }
                trim(context, dir)
                while (alive) {
                    val next = pick()
                    if (next < 0) { waitFor(1_000); continue }
                    if (memoryTight(context)) { lowMemory(); waitFor(5_000); continue }
                    if (lowMemoryNoted) setMessage(SeekThumbMessages.MAKING)
                    val urgent = next == target || abs(next - target) <= 2 && seeking()
                    if (!make(next)) break
                    if (!urgent) waitFor(if (playback.isPlaying()) 1_000 else 250)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "thumbnails stopped: ${t.javaClass.simpleName}")
                fail("Thumbnails stopped working for this video")
            } finally {
                val r = retriever
                retriever = null
                runCatching { r?.release() }
                if (made > 0) runCatching { trim(context, dir) }
            }
        }

        private fun seeking() = SystemClock.elapsedRealtime() - lastSeekAt < SEEK_ACTIVE_MS

        private fun pick(): Int {
            val t = target
            if (t >= 0 && seeking()) {
                for (d in 0..3) {
                    if (t + d < count && state[t + d] == 0.toByte()) return t + d
                    if (d > 0 && t - d >= 0 && state[t - d] == 0.toByte()) return t - d
                }
            }
            val p = playback
            val paused = !p.isPlaying()
            val allowed = when (mode) {
                SeekThumbWhen.SEEKING -> paused || seeking()
                SeekThumbWhen.WATCHING -> paused || seeking() || p.bufferedAheadMs() >= 30_000L
                SeekThumbWhen.RIGHT_AWAY -> paused || seeking() || p.bufferedAheadMs() >= 15_000L
            }
            if (!allowed) return -1
            if (mode == SeekThumbWhen.RIGHT_AWAY) {
                for (i in 0 until count) if (state[i] == 0.toByte()) return i
                return -1
            }
            val from = if (seeking() && t >= 0) t else index(p.positionMs())
            for (d in 0 until count) {
                val a = from + d
                val b = from - d
                if (a < count && state[a] == 0.toByte()) return a
                if (d > 0 && b >= 0 && state[b] == 0.toByte()) return b
                if (a >= count && b < 0) break
            }
            return -1
        }

        private fun lowMemory() {
            setMessage(SeekThumbMessages.LOW_MEMORY)
            if (lowMemoryNoted) return
            lowMemoryNoted = true
            updateSettings { it.copy(lastLowMemoryAt = System.currentTimeMillis()) }
        }

        private fun fail(reason: String, key: String = source.key) {
            alive = false
            failed[key] = reason
            setMessage(reason)
        }

        private fun make(i: Int): Boolean {
            val atMs = (i * intervalMs).coerceAtMost((durationMs - 1_000L).coerceAtLeast(0L)).coerceAtLeast(if (i == 0) 1_000L else 0L)
            val bmp = try {
                when (val s = source) {
                    is SeekThumbSource.Remote -> fileFrame(atMs) { it.setDataSource(s.url, s.headers) }
                    is SeekThumbSource.Local -> fileFrame(atMs) { it.setDataSource(s.path) }
                    is SeekThumbSource.Hls -> hlsFrame(s, atMs)
                }
            } catch (e: RuntimeException) {
                failures++
                null
            }
            if (!alive) { bmp?.recycle(); return false }
            if (bmp == null) {
                state[i] = 2
                misses++
                if (failures >= 3 && made == 0) { fail("The server didn't send thumbnail pictures"); return false }
                if (misses >= 8 && made == 0) { fail("This video's format can't make thumbnails on this device"); return false }
                return true
            }
            failures = 0
            val small = fit(bmp)
            try {
                File(dir, "$i.jpg.tmp").let { tmp ->
                    tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                    tmp.renameTo(File(dir, "$i.jpg"))
                }
            } catch (_: Throwable) {
            }
            state[i] = 1
            memory.put(memKey(i), small)
            made++
            if (made % 50 == 0) runCatching { trim(context, dir) }
            if (made == 1) setMessage(SeekThumbMessages.MAKING)
            _version.value++
            return true
        }

        private fun fit(b: Bitmap): Bitmap {
            if (b.width <= THUMB_W && b.height <= THUMB_H && b.config == Bitmap.Config.RGB_565) return b
            val scale = minOf(THUMB_W.toFloat() / b.width, THUMB_H.toFloat() / b.height).coerceAtMost(1f)
            val w = (b.width * scale).toInt().coerceAtLeast(1)
            val h = (b.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            android.graphics.Canvas(out).drawBitmap(b, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            if (out !== b) b.recycle()
            return out
        }

        private fun grab(r: MediaMetadataRetriever, atUs: Long): Bitmap? =
            if (Build.VERSION.SDK_INT >= 30) {
                val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
                r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_H, params)
            } else if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_H)
            } else {
                r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }

        private fun fileFrame(atMs: Long, open: (MediaMetadataRetriever) -> Unit): Bitmap? {
            val r = retriever ?: try {
                MediaMetadataRetriever().also(open).also { retriever = it }
            } catch (e: Exception) {
                fail("This video couldn't be opened for thumbnails")
                return null
            }
            return grab(r, atMs * 1000L)
        }

        private fun hlsFrame(s: SeekThumbSource.Hls, atMs: Long): Bitmap? {
            val reader = hls ?: try {
                SeekThumbsHls(s.url, s.headers, s.policy).also { r ->
                    r.prepare()
                    if (!include4k && (r.height > 1088 || r.hdr)) {
                        fail(SeekThumbMessages.FOUR_K_OFF, FOUR_K + source.key)
                        return null
                    }
                    hls = r
                }
            } catch (e: SeekThumbsHls.Unsupported) {
                fail(e.message ?: SeekThumbMessages.PROTECTED)
                return null
            } catch (e: java.io.IOException) {
                failures++
                return null
            }
            val tmp = File(dir, "piece.tmp")
            return try {
                val within = reader.pieceAt(atMs, tmp) ?: return null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(tmp.path)
                    grab(r, within.coerceAtLeast(0L) * 1000L) ?: grab(r, 0L) ?: r.frameAtTime
                } finally {
                    runCatching { r.release() }
                }
            } catch (e: SeekThumbsHls.Unsupported) {
                fail(e.message ?: SeekThumbMessages.PROTECTED)
                null
            } catch (e: java.io.IOException) {
                failures++
                null
            } finally {
                tmp.delete()
            }
        }
    }
}
