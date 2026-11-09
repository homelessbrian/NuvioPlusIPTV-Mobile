package com.nuvio.app.features.livetv.data

import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Works out the time zone a provider's server keeps its catch-up on, the TV app's way. Catch-up
 * links name the start time in the server's own clock with no zone attached, so a replay is
 * hours off unless the app writes it in that clock.
 *
 * Each answer comes with a few words on where it came from.
 */
internal object ZoneFinder {

    /**
     * From the "server_info" part of an Xtream account reply: the time zone it names, else the
     * gap between its clock ("time_now", server time) and the real time ("timestamp_now").
     */
    fun fromServerInfo(info: JsonObject?): Pair<String, String>? {
        if (info == null) return null
        val name = info.optString("timezone").trim()
        if (name.isNotEmpty() && isKnown(name)) return name to "reported by the server"
        val clock = info.optString("time_now").trim()
        val epoch = info.optString("timestamp_now").trim().toLongOrNull()
        if (clock.isNotEmpty() && epoch != null && epoch > 0) {
            val asUtc = CLOCK.find(clock)?.destructured?.let { (y, mo, d, h, mi, se) ->
                CivilTime.toEpochMs(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt(), LiveTvTimeZones.UTC)
            }
            if (asUtc != null) {
                gapToZone(asUtc - epoch * 1000)?.let { return it to "worked out from the server's clock" }
            }
        }
        return null
    }

    /** From the start of an XMLTV guide: the offset written with its first show time ("20261108150000 +0100"). */
    fun fromGuide(text: String): Pair<String, String>? {
        val m = GUIDE_TIME.find(text) ?: return null
        val off = m.groupValues[2]
        val sign = if (off[0] == '-') -1 else 1
        val h = off.substring(1, 3).toIntOrNull() ?: return null
        val mm = off.substring(3, 5).toIntOrNull() ?: return null
        val ms = sign * (h * 3_600_000L + mm * 60_000L)
        return gapToZone(ms)?.let { it to "from the provider's own guide" }
    }

    /** True for a zone this app worked out (a fixed offset), not one the server named. */
    fun isWorkedOut(zone: String): Boolean = WORKED_OUT.matches(zone)

    /** A gap from UTC, rounded to the nearest quarter hour, as a fixed zone ("GMT+01:00"); null if implausible. */
    internal fun gapToZone(gapMs: Long): String? {
        val quarter = 15 * 60_000L
        val rounded = (gapMs.toDouble() / quarter).roundToLong() * quarter
        if (abs(rounded) > 14 * 3_600_000L) return null
        val sign = if (rounded < 0) "-" else "+"
        val mins = abs(rounded) / 60_000L
        return "GMT$sign${(mins / 60).toString().padStart(2, '0')}:${(mins % 60).toString().padStart(2, '0')}"
    }

    /** A worked-out fixed zone ("GMT+01:00") as its offset from UTC, or null for any other name. */
    fun fixedOffsetMs(zone: String): Long? {
        val m = WORKED_OUT_PARTS.find(zone) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return sign * (m.groupValues[2].toLong() * 3_600_000L + m.groupValues[3].toLong() * 60_000L)
    }

    /** Whether catch-up can write times in [zone]: a zone the platform knows, or a worked-out fixed one. */
    fun isKnown(zone: String): Boolean = fixedOffsetMs(zone) != null || LiveTvTimeZones.isKnown(zone)

    /** [zone]'s offset from UTC at [epochMs] (fixed zones included; 0 for an unknown one). */
    fun offsetMs(zone: String, epochMs: Long): Long = fixedOffsetMs(zone) ?: LiveTvTimeZones.offsetMs(zone, epochMs)

    private val CLOCK = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")
    private val GUIDE_TIME = Regex("""start\s*=\s*["'](\d{12,14})\s*([+-]\d{4})["']""")
    private val WORKED_OUT = Regex("""^GMT[+-]\d{2}:\d{2}$""")
    private val WORKED_OUT_PARTS = Regex("""^GMT([+-])(\d{2}):(\d{2})$""")
}
