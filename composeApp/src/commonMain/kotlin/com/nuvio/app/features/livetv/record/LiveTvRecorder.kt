package com.nuvio.app.features.livetv.record

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.XtreamClient
import com.nuvio.app.features.livetv.data.parseObject
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.LiveTvSettings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records Live TV channels to the device, and keeps the list of recordings. Each recording is a
 * folder with one video file and an `info.json`:
 *  - TS channels are copied straight to a .ts file; a dropped connection is retried until the
 *    stop time and the stream appended, so playback carries on across the gap;
 *  - HLS channels' pieces are saved as they appear and appended to one file (.ts, or .mp4 when
 *    the stream is fragmented MP4 with an init part).
 * A catch-up "save" is a finite download of a whole show from the provider's archive.
 * While anything is recording, the platform keeps the app running in the background.
 */
internal object LiveTvRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val jobs = HashMap<String, Job>()
    private val stopRequested = HashSet<String>()
    /** Ended here because the job didn't stop in time: when the job does end, it just goes quietly. */
    private val forced = HashSet<String>()
    /** Catch-up downloads going on: recording id -> the provider (playlist) it's from. */
    private val catchupSources = HashMap<String, String>()

    /**
     * A catch-up download from this provider is going on. Most accounts allow one connection at
     * a time, so a second one would make the provider close the first part way through.
     */
    fun catchupBusy(sourceId: String?): Boolean = sourceId != null && synchronized(lock) { sourceId in catchupSources.values }

    private val _recordings = MutableStateFlow<List<LiveTvRecording>>(emptyList())
    /** Every recording, newest first. */
    val recordings: StateFlow<List<LiveTvRecording>> = _recordings.asStateFlow()

    private var loaded = false

    /** Reads the recordings on the device once (and keeps the space rule up to date). */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scope.launch { runCatching { load() } }
    }

    private fun settings(): LiveTvSettings = LiveTvPreferences.currentSettings()

    /** The recording running on [channelKey], if any. */
    fun activeFor(channelKey: String): LiveTvRecording? =
        _recordings.value.firstOrNull { it.channelKey == channelKey && it.isRecording }

    fun anyRecording(): Boolean = _recordings.value.any { it.isRecording }

    fun filePath(rec: LiveTvRecording): String = rec.dir + "/" + rec.fileName

    /** A link the player can open. */
    fun playUrl(rec: LiveTvRecording): String = RecordingPlatform.playUrl(filePath(rec))

    /** Roughly how much room there is where recordings go. */
    fun freeBytes(): Long = RecordingPlatform.usableSpace()

    /**
     * Starts recording [channel] from [url] until [stopAtMs]. Returns the new recording, or the
     * one already running on that channel.
     */
    fun start(
        channel: LiveChannel,
        url: String,
        title: String,
        episode: String?,
        description: String?,
        programStartMs: Long,
        programStopMs: Long,
        stopAtMs: Long,
        /** A catch-up download: it ends by itself (end of file / end of playlist); no reconnecting. */
        finite: Boolean = false,
        /** Other links to try if the first doesn't work (catch-up variants). */
        fallbacks: List<String> = emptyList(),
        /** Called once it's finished (done or failed). */
        onFinished: (LiveTvRecording) -> Unit = {},
        seriesId: String? = null,
        /**
         * Catch-up only: the link for the rest of the show, from this far in (ms). A download that
         * gets cut off carries on from there instead of stopping with part of the show.
         */
        resumeFrom: ((doneMs: Long) -> String?)? = null,
    ): LiveTvRecording {
        ensureLoaded()
        if (!finite) activeFor(channel.key)?.let { return it }
        val now = LiveTvClock.nowMs()
        val id = "rec$now"
        val root = RecordingPlatform.recordingsRoot()
        val dir = "$root/$id"
        val rec = LiveTvRecording(
            id = id,
            channelKey = channel.key,
            channelName = channel.name,
            channelLogo = channel.logo,
            title = title,
            episode = episode,
            description = description,
            programStartMs = programStartMs,
            programStopMs = programStopMs,
            startedAtMs = now,
            stopAtMs = stopAtMs,
            dir = dir,
            seriesId = seriesId,
            fromCatchup = finite,
        )
        if (!RecordingPlatform.available || root.isBlank()) {
            val failed = rec.copy(status = RecordingStatus.FAILED, endedAtMs = now, problem = "Recording isn't available on this device")
            update(failed)
            runCatching { onFinished(failed) }
            return failed
        }
        // Not enough room to start: make room (if allowed) or say so.
        if (!ensureSpace(protect = id)) {
            val failed = rec.copy(status = RecordingStatus.FAILED, endedAtMs = now, problem = "Not enough storage space to record")
            save(failed); update(failed)
            RecordingNotices.show("Not enough storage space to record \"$title\"")
            runCatching { onFinished(failed) }
            return failed
        }
        save(rec)
        update(rec)
        RecordingPlatform.keepAlive(true)
        if (finite) synchronized(lock) { catchupSources[id] = channel.sourceId }
        val job = scope.launch {
            try {
                record(rec, url, channel.headers, finite, fallbacks, onFinished, resumeFrom)
            } finally {
                synchronized(lock) { catchupSources.remove(id) }
            }
        }
        synchronized(lock) { jobs[id] = job }
        return rec
    }

    /** Stops a recording now (what's recorded is kept). */
    fun stop(id: String) {
        val job = synchronized(lock) { stopRequested += id; jobs[id] }
        job?.cancel()
        // A recording stuck waiting on a dead connection can ignore the stop: if it is still
        // "recording" a few seconds later, end it here.
        scope.launch {
            delay(4_000)
            if (_recordings.value.firstOrNull { it.id == id }?.isRecording == true) forceFinish(id)
        }
    }

    private fun forceFinish(id: String) {
        val latest = _recordings.value.firstOrNull { it.id == id } ?: return
        val hasVideo = RecordingPlatform.fileSize(filePath(latest)) > 0
        val done = latest.copy(
            status = if (hasVideo) RecordingStatus.DONE else RecordingStatus.FAILED,
            endedAtMs = LiveTvClock.nowMs(),
            sizeBytes = RecordingPlatform.dirSize(latest.dir),
            problem = if (hasVideo) null else "Stopped before anything was recorded"
        )
        save(done); update(done)
        synchronized(lock) { forced += id; jobs.remove(id)?.cancel(); catchupSources.remove(id); stopRequested.remove(id) }
        stopServiceIfIdle()
    }

    /** Deletes a recording (stopping it first if it's still going). */
    fun delete(id: String) {
        val rec = _recordings.value.firstOrNull { it.id == id }
        if (rec?.isRecording == true) stop(id)
        _recordings.value = _recordings.value.filterNot { it.id == id }
        rec?.let { r -> scope.launch { runCatching { RecordingPlatform.deleteDir(r.dir) } } }
        stopServiceIfIdle()
    }

    /** Changes when a running recording stops. */
    fun extend(id: String, stopAtMs: Long) {
        val rec = _recordings.value.firstOrNull { it.id == id } ?: return
        val r = rec.copy(stopAtMs = stopAtMs)
        save(r); update(r)
    }

    // ------------------------------------------------------------------ recording

    private fun stopping(id: String): Boolean = synchronized(lock) { id in stopRequested }

    private suspend fun record(
        start: LiveTvRecording,
        firstUrl: String,
        headers: Map<String, String>,
        finite: Boolean,
        fallbacks: List<String>,
        onFinished: (LiveTvRecording) -> Unit,
        resumeFrom: ((doneMs: Long) -> String?)?,
    ) {
        val links = ArrayDeque((listOf(firstUrl) + fallbacks).distinct())
        var url = links.removeFirst()
        var rec = start
        var problem: String? = null
        val path = filePath(rec)
        val hdrs = withUserAgent(headers)
        fun looksHls(link: String) = link.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        // What's in the file so far is thrown away when the next download starts (a catch-up HLS
        // replay that sent nothing but a cut-off piece, before switching to its TS link).
        var discardFile = false
        try {
            val isHls = looksHls(url)
            var hls = isHls
            // Catch-up saved as HLS: the plain (TS) form to switch to if the HLS one sends nothing,
            // as the player does when watching (some providers' HLS replays never deliver).
            val tsLink = if (finite && isHls) links.firstOrNull { !looksHls(it) && it.contains("/timeshift/") } else null
            if (hls) {
                problem = try {
                    recordHls(rec, url, hdrs, finite, canGiveUp = tsLink != null) { secs, size -> rec = progress(rec, secs, size) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (tsLink == null) throw e
                    HLS_SENT_NOTHING
                }
                if (problem == HLS_SENT_NOTHING && tsLink != null && !stopping(rec.id)) {
                    links.remove(tsLink)
                    url = tsLink
                    hls = false
                    problem = null
                    discardFile = true
                    // Back to the plain .ts file (an HLS replay in MP4 pieces had switched it).
                    val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
                    rec = latest.copy(fileName = start.fileName, problem = null)
                    save(rec); update(rec)
                }
                // Some providers refuse the pieces of their .m3u8 stream but give the plain stream:
                // if nothing was saved, try the same link as .ts once.
                val plain = url.substringBefore('?').removeSuffix(".m3u8").takeIf { it != url.substringBefore('?') }
                    ?.let { it + ".ts" + url.substringAfter('?', "").let { q -> if (q.isEmpty()) "" else "?$q" } }
                if (!finite && plain != null && RecordingPlatform.dirSize(rec.dir) <= 2_048L && !stopping(rec.id) && LiveTvClock.nowMs() < currentStop(rec.id)) {
                    url = plain
                    hls = false
                    problem = null
                    rec = noteProblem(rec, null)
                }
            }
            if (!hls) {
                var first = true
                var tries = 0
                var recordedMs = 0L
                while (scope.isActive && !stopping(rec.id) && LiveTvClock.nowMs() < currentStop(rec.id)) {
                    if (!first) {
                        if (finite) {
                            // A catch-up download that got cut off carries on from where it got to
                            // (a new link starting there); one that never started gets a few tries.
                            if (++tries > (if (recordedMs > 0) 10 else 3)) break
                            if (recordedMs > 0) url = resumeFrom?.invoke(recordedMs) ?: break
                            delay(3_000L * tries.coerceAtMost(5))
                        } else {
                            // A live recording reconnects until its end.
                            delay(3_000)
                        }
                    }
                    first = false
                    val segmentStart = LiveTvClock.nowMs()
                    try {
                        val append = !discardFile && RecordingPlatform.fileSize(path) > 0
                        discardFile = false
                        var lastSave = 0L
                        val before = if (append) RecordingPlatform.fileSize(path) else 0L
                        var got = 0L
                        RecordingPlatform.download(url, hdrs, path, append, shouldStop = { stopping(rec.id) || LiveTvClock.nowMs() >= currentStop(rec.id) }) { total ->
                            got = total - before
                            val now = LiveTvClock.nowMs()
                            if (now - lastSave > 5_000) {
                                lastSave = now
                                val secs = (recordedMs + (now - segmentStart)) / 1000.0
                                rec = progress(rec, secs, RecordingPlatform.dirSize(rec.dir))
                                if (!ensureSpace(protect = rec.id)) {
                                    problem = "Storage ran out, so it stopped early"
                                    synchronized(lock) { stopRequested += rec.id }
                                }
                            }
                        }
                        if (got > 0L) {
                            recordedMs += LiveTvClock.nowMs() - segmentStart
                            problem = null
                        } else if (!stopping(rec.id)) {
                            // Connected without an error but no video came: say so instead of staying silent.
                            problem = NO_VIDEO
                            rec = noteProblem(rec, problem)
                        }
                        if (finite) break // the whole show has come in
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RecordingHttpError) {
                        problem = "The provider answered HTTP ${e.code}"
                        rec = noteProblem(rec, problem)
                        // Catch-up: try the other link forms.
                        if (finite && links.isNotEmpty()) url = links.removeFirst()
                    } catch (e: Exception) {
                        recordedMs += LiveTvClock.nowMs() - segmentStart
                        problem = "The connection dropped (${e.message ?: "unknown error"}); it reconnected where it could"
                        rec = noteProblem(rec, problem)
                    }
                }
                rec = progress(rec, recordedMs / 1000.0, RecordingPlatform.dirSize(rec.dir))
            }
        } catch (e: CancellationException) {
            // Stopped by hand: finish below.
        } catch (e: Exception) {
            problem = e.message ?: "unknown error"
        } finally {
            finish(rec, path, problem, onFinished)
        }
    }

    /** The end of [record]: the recording marked done or failed, and [onFinished] told. */
    private fun finish(rec: LiveTvRecording, startPath: String, problem: String?, onFinished: (LiveTvRecording) -> Unit) {
        // Already ended by [forceFinish] (and maybe deleted since): nothing more to do.
        if (synchronized(lock) { forced.remove(rec.id) }) {
            synchronized(lock) { stopRequested.remove(rec.id) }
            stopServiceIfIdle()
            return
        }
        run {
            val byHand = stopping(rec.id)
            val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
            val path = latest.dir + "/" + latest.fileName
            val hasVideo = RecordingPlatform.fileSize(path) > 0 || (path != startPath && RecordingPlatform.fileSize(startPath) > 0)
            val expectedSec = (latest.stopAtMs - latest.startedAtMs) / 1000.0
            val done = latest.copy(
                status = if (hasVideo) RecordingStatus.DONE else RecordingStatus.FAILED,
                endedAtMs = LiveTvClock.nowMs(),
                sizeBytes = RecordingPlatform.dirSize(latest.dir),
                problem = if (hasVideo) problem?.takeIf { latest.durationSec < expectedSec * 0.9 }
                else if (byHand) "Stopped before anything was recorded"
                else (problem ?: latest.problem ?: "Nothing could be recorded from this channel ($NO_VIDEO_SHORT)")
            )
            if (_recordings.value.any { it.id == done.id }) { save(done); update(done) }
            synchronized(lock) { jobs.remove(rec.id); stopRequested.remove(rec.id) }
            stopServiceIfIdle()
            // Stopped by hand before anything came in: not a failure to try again later.
            if (!(byHand && done.status == RecordingStatus.FAILED)) runCatching { onFinished(done) }
        }
    }

    private fun withUserAgent(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers else headers + ("User-Agent" to XtreamClient.DEFAULT_UA)

    private fun currentStop(id: String): Long = _recordings.value.firstOrNull { it.id == id }?.stopAtMs ?: 0L

    /** Shows what's going wrong while it's still recording (Recordings shows it under the title). */
    private fun noteProblem(rec: LiveTvRecording, problem: String?): LiveTvRecording {
        val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
        val r = latest.copy(problem = problem)
        update(r)
        return r
    }

    private fun progress(rec: LiveTvRecording, durationSec: Double, size: Long): LiveTvRecording {
        val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
        val r = latest.copy(durationSec = durationSec, sizeBytes = size, problem = if (durationSec > latest.durationSec) null else latest.problem)
        save(r); update(r)
        return r
    }

    private val STREAM_INF = Regex("""#EXT-X-STREAM-INF""")
    private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
    private val MEDIA_SEQUENCE = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")
    private val TARGET_DURATION = Regex("""#EXT-X-TARGETDURATION:(\d+)""")
    private val MAP_URI = Regex("""#EXT-X-MAP:URI="([^"]+)"""")

    /**
     * HLS channel: saves each piece as it appears, appended to one file: a live channel from its
     * newest pieces (a short lead-in, not its whole look-back window, which can be hours), a
     * catch-up show all of it. Returns a problem, or null. [canGiveUp]: there's a TS link to
     * switch to, so a catch-up replay that has saved nothing after [HLS_GIVE_UP_MS] stops with
     * [HLS_SENT_NOTHING].
     */
    private suspend fun recordHls(
        rec: LiveTvRecording,
        url: String,
        headers: Map<String, String>,
        finite: Boolean,
        canGiveUp: Boolean = false,
        onProgress: (Double, Long) -> Unit,
    ): String? {
        val began = LiveTvClock.nowMs()
        var total = 0.0
        fun nothingYet() = canGiveUp && total <= 0.0 && LiveTvClock.nowMs() - began > HLS_GIVE_UP_MS
        var mediaUrl = url
        var text = RecordingPlatform.fetchTextAt(mediaUrl, headers).let { (t, at) -> mediaUrl = at; t }
        if (STREAM_INF.containsMatchIn(text)) {
            mediaUrl = resolve(mediaUrl, pickVariant(text) ?: return "This channel's stream couldn't be read")
            text = RecordingPlatform.fetchTextAt(mediaUrl, headers).let { (t, at) -> mediaUrl = at; t }
        }
        if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) return "This channel is encrypted and can't be recorded"
        var path = filePath(rec)
        var lastSeq = -1L
        var target = 6
        var problem: String? = null
        var mapDone = false
        var shownAt = LiveTvClock.nowMs()
        while (scope.isActive && !stopping(rec.id) && LiveTvClock.nowMs() < currentStop(rec.id)) {
            if (nothingYet()) return HLS_SENT_NOTHING
            val lines = text.lines()
            var seq = MEDIA_SEQUENCE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            target = TARGET_DURATION.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: target
            // A live channel (first time, or after a reconnect): start a short lead-in before its
            // newest piece, the way players do, instead of at the start of its look-back window.
            if (!finite && lastSeq < 0) lastSeq = liveStartSeq(text, seq, target)
            if (!mapDone) {
                MAP_URI.find(text)?.groupValues?.get(1)?.let { m ->
                    // Fragmented MP4: the init part first, then the pieces, all in one .mp4.
                    path = rec.dir + "/recording.mp4"
                    val withMp4 = (_recordings.value.firstOrNull { it.id == rec.id } ?: rec).copy(fileName = "recording.mp4")
                    save(withMp4); update(withMp4)
                    runCatching { RecordingPlatform.download(resolve(mediaUrl, m), headers, path, append = false, shouldStop = { false }) {} }
                }
                mapDone = true
            }
            var dur = 0.0
            for (l in lines) {
                when {
                    l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: target.toDouble()
                    l.isNotBlank() && !l.startsWith("#") -> {
                        if (seq > lastSeq) {
                            var failure: String? = null
                            var tries = 0
                            while (true) {
                                val sizeBefore = RecordingPlatform.fileSize(path)
                                failure = try {
                                    // A stop (or an HLS replay that's sent nothing) cuts a piece part way
                                    // instead of waiting for it: some providers send a whole show as one
                                    // long piece, at viewing speed. What came in of it is kept.
                                    RecordingPlatform.download(resolve(mediaUrl, l), headers, path, append = sizeBefore > 0, shouldStop = { stopping(rec.id) || nothingYet() }) {}
                                    null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: RecordingHttpError) {
                                    "The provider answered HTTP ${e.code} for the video pieces"
                                } catch (e: Exception) {
                                    e.message ?: "unknown error"
                                }
                                // A catch-up show's list has every piece at once and isn't read again:
                                // a piece that failed before any of it was saved gets a few more tries.
                                if (failure == null || !finite || ++tries >= 3 || stopping(rec.id) ||
                                    RecordingPlatform.fileSize(path) != sizeBefore) break
                                delay(1_000L * tries)
                            }
                            if (failure == null) {
                                total += dur
                                lastSeq = seq
                                // The saved part shows as it comes in, not only once the whole list is done.
                                if (finite && LiveTvClock.nowMs() - shownAt > 5_000) {
                                    shownAt = LiveTvClock.nowMs()
                                    onProgress(total, RecordingPlatform.dirSize(rec.dir))
                                }
                            } else if (total <= 0.0) {
                                problem = failure
                                noteProblem(rec, failure) // kept even if a stop cancels this loop
                            }
                        }
                        seq++
                    }
                }
            }
            if (nothingYet()) return HLS_SENT_NOTHING
            onProgress(total, RecordingPlatform.dirSize(rec.dir))
            if (!ensureSpace(protect = rec.id)) return "Storage ran out, so it stopped early"
            // A finished playlist (a catch-up show): everything's saved.
            if (finite && text.contains("#EXT-X-ENDLIST")) break
            delay((target * 1000L / 2).coerceIn(1_000L, 6_000L))
            text = try {
                RecordingPlatform.fetchTextAt(mediaUrl, headers).let { (t, at) -> mediaUrl = at; t }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "The connection dropped (${e.message ?: "unknown error"}); it reconnected where it could"
                // A live channel's list moves on while it's away, so it starts again at the newest
                // pieces. A catch-up show's list is the same one: carry on from the last piece.
                if (!finite) lastSeq = -1L
                delay(3_000)
                continue
            }
        }
        return problem
    }

    /**
     * The variant of a master playlist to save: the best quality (as before), but one with its
     * sound inside its pieces when the channel offers both kinds. Channels that send their sound
     * as a separate stream (ARD, ZDF…) would otherwise save picture only, since a recording here
     * is one file.
     */
    private fun pickVariant(master: String): String? {
        val lines = master.lines().map { it.trim() }
        // Sound groups whose chosen sound has its own link (a separate stream).
        val separate = lines.filter { it.startsWith("#EXT-X-MEDIA:") && attr(it, "TYPE") == "AUDIO" && attr(it, "URI") != null }
            .mapNotNull { attr(it, "GROUP-ID") }.toSet()
        data class Variant(val bw: Long, val uri: String, val ownSound: Boolean)
        val variants = lines.mapIndexedNotNull { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val bw = BANDWIDTH.find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            Variant(bw, uri, attr(l, "AUDIO")?.let { it !in separate } ?: true)
        }
        val pool = variants.filter { it.ownSound }.ifEmpty { variants }
        return pool.maxByOrNull { it.bw }?.uri
    }

    private fun attr(line: String, name: String): String? =
        Regex("""(?:^|[,:])$name=("([^"]*)"|[^,]*)""").find(line)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }?.takeIf { it.isNotEmpty() }

    /** The sequence number just before the first piece to save of a live list: about [LIVE_LEAD_MS] before its newest piece. */
    private fun liveStartSeq(text: String, firstSeq: Long, target: Int): Long {
        val durs = ArrayList<Double>()
        var dur = target.toDouble()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: target.toDouble()
                l.isNotEmpty() && !l.startsWith("#") -> { durs += dur; dur = target.toDouble() }
            }
        }
        if (durs.isEmpty()) return firstSeq - 1
        var acc = 0.0
        var i = durs.size
        while (i > 0 && acc * 1000 < LIVE_LEAD_MS) { i--; acc += durs[i] }
        return firstSeq + i.coerceIn(0, durs.size - 1) - 1
    }

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        val noQuery = base.substringBefore('?')
        if (ref.startsWith("/")) {
            val schemeEnd = noQuery.indexOf("://") + 3
            val hostEnd = noQuery.indexOf('/', schemeEnd).let { if (it < 0) noQuery.length else it }
            return noQuery.substring(0, hostEnd) + ref
        }
        return noQuery.substringBeforeLast('/') + "/" + ref
    }

    // ------------------------------------------------------------------ list and files

    private fun save(rec: LiveTvRecording) {
        runCatching { RecordingPlatform.writeText(rec.dir + "/info.json", rec.toJson().toString()) }
    }

    private fun update(rec: LiveTvRecording) {
        synchronized(lock) {
            val list = _recordings.value.filterNot { it.id == rec.id } + rec
            _recordings.value = list.sortedByDescending { it.startedAtMs }
        }
    }

    /**
     * Makes sure there's room to keep recording: below 500 MB free, either deletes the oldest
     * finished recordings (if set to) or says no. [protect]: never deleted.
     */
    private fun ensureSpace(protect: String): Boolean {
        fun free() = runCatching { RecordingPlatform.usableSpace() }.getOrDefault(Long.MAX_VALUE)
        if (free() >= MIN_FREE) return true
        if (settings().recordingSpaceRule != "DELETE_OLDEST") return false
        val oldest = _recordings.value.filter { !it.isRecording && it.id != protect }.sortedBy { it.startedAtMs }
        for (r in oldest) {
            if (free() >= MIN_FREE) break
            RecordingNotices.show("Storage is low: deleted the oldest recording, \"${r.title}\"")
            _recordings.value = _recordings.value.filterNot { it.id == r.id }
            runCatching { RecordingPlatform.deleteDir(r.dir) }
        }
        return free() >= MIN_FREE
    }

    /** Reads the recording folders. Ones that were still recording when the app closed are finished off. */
    private suspend fun load() = withContext(Dispatchers.Default) {
        if (!RecordingPlatform.available) return@withContext
        val out = ArrayList<LiveTvRecording>()
        RecordingPlatform.listRecordingDirs().forEach { d ->
            val info = RecordingPlatform.readText("$d/info.json") ?: return@forEach
            val rec = runCatching { LiveTvRecording.fromJson(parseObject(info), d) }.getOrNull() ?: return@forEach
            val running = synchronized(lock) { jobs.containsKey(rec.id) }
            if (rec.isRecording && !running) {
                val hasVideo = RecordingPlatform.fileSize("$d/${rec.fileName}") > 0
                val fixed = if (hasVideo) rec.copy(status = RecordingStatus.DONE, endedAtMs = LiveTvClock.nowMs(), sizeBytes = RecordingPlatform.dirSize(d),
                    problem = "The app closed before it finished, so it stops early")
                else rec.copy(status = RecordingStatus.FAILED, problem = "The app closed before anything was recorded")
                save(fixed)
                out += fixed
            } else out += rec
        }
        synchronized(lock) {
            val running = _recordings.value.filter { it.isRecording }
            _recordings.value = (out.filterNot { o -> running.any { it.id == o.id } } + running).sortedByDescending { it.startedAtMs }
        }
    }

    fun stopServiceIfIdle() {
        val idle = synchronized(lock) { jobs.isEmpty() } || _recordings.value.none { it.isRecording }
        if (idle) RecordingPlatform.keepAlive(false)
    }

    private const val NO_VIDEO = "The provider answered but sent no video. If you're watching this channel, your provider may only allow one connection at a time"
    private const val NO_VIDEO_SHORT = "no video came back"

    /** A live HLS recording starts this far before now (the newest pieces), not at the channel's look-back start. */
    private const val LIVE_LEAD_MS = 30_000L
    /** A catch-up HLS replay that's saved nothing after this long: switch to the TS link. */
    private const val HLS_GIVE_UP_MS = 20_000L
    private const val HLS_SENT_NOTHING = "The HLS replay sent nothing"

    /** Below this much free space, recording stops (or the oldest recordings make room). */
    private const val MIN_FREE = 500L * 1024 * 1024
}

/** Short messages about recordings: in the app while it's open, and as a notification. */
internal object RecordingNotices {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    fun show(text: String) {
        _current.value = text
        runCatching { RecordingPlatform.notify("notice", "Recordings", text) }
    }

    fun clear() { _current.value = null }
}
