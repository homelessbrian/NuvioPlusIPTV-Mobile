package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.sports.SportsFeedCache
import com.nuvio.app.features.livetv.sports.SyncMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SportsFeedCacheTest {
    @Test
    fun freshResponsesKeepTheirIdentityUntilTheExactFreshnessBoundary() = runTest {
        var now = 0L
        var loads = 0
        val cache = SportsFeedCache { now }
        suspend fun get() = cache.getOrLoad("score", 90_000) { loads++; Any() }
        val first = get()
        now = 89_999
        assertSame(first, get())
        now = 90_000
        assertNotSame(first, get())
        assertEquals(2, loads)
    }

    @Test
    fun anotherKeyReleasesExpiredResponsesAndKeepsFreshTeamData() = runTest {
        var now = 0L
        val cache = SportsFeedCache { now }
        cache.getOrLoad("old-panel", 180_000) { Any() }
        val teams = cache.getOrLoad("teams", 43_200_000) { Any() }
        now = 240_000
        cache.getOrLoad("new-panel", 180_000) { Any() }
        assertEquals(2, cache.size)
        assertSame(teams, cache.getOrLoad<Any>("teams", 43_200_000) { error("Fresh teams must not reload") })
        now = 43_200_000
        assertNotSame(teams, cache.getOrLoad("teams", 43_200_000) { Any() })
    }

    @Test
    fun sweepingIsLimitedToOnceAMinuteAndResumesAfterAQuietPeriod() = runTest {
        var now = 0L
        val cache = SportsFeedCache { now }
        cache.getOrLoad("short", 1) { Any() }
        now = 1
        val long = cache.getOrLoad("long", 600_000) { Any() }
        now = 59_999
        assertSame(long, cache.getOrLoad("long", 600_000) { Any() })
        assertEquals(2, cache.size)
        now = 60_000
        assertSame(long, cache.getOrLoad("long", 600_000) { Any() })
        assertEquals(1, cache.size)
        now = 86_400_000
        assertEquals(1, cache.size)
        cache.getOrLoad("today", 180_000) { Any() }
        assertEquals(1, cache.size)
    }

    @Test
    fun liveMilbResponsesSurviveSweepsForTheLongerScheduleWindow() = runTest {
        var now = 0L
        var loads = 0
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("milb:schedule", 60_000, retainMs = 1_800_000) { loads++; Any() }
        now = 120_000
        cache.getOrLoad("other", 60_000) { Any() }
        assertSame(first, cache.getOrLoad("milb:schedule", 1_800_000, retainMs = 1_800_000) { loads++; Any() })
        now = 1_800_000
        assertNotSame(first, cache.getOrLoad("milb:schedule", 1_800_000, retainMs = 1_800_000) { loads++; Any() })
        assertEquals(2, loads)
    }

    @Test
    fun failedLiveRefreshPreservesTheStillReusableScheduleResponse() = runTest {
        var now = 0L
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("milb:schedule", 60_000, retainMs = 1_800_000) { Any() }
        now = 120_000
        assertFailsWith<IllegalStateException> {
            cache.getOrLoad<Any>("milb:schedule", 60_000, retainMs = 1_800_000) { error("offline") }
        }
        assertSame(first, cache.getOrLoad<Any>("milb:schedule", 1_800_000) { error("Still reusable") })
    }

    @Test
    fun failedLoadsAreNotCachedAndFreshnessStartsWhenLoadingFinishes() = runTest {
        var now = 0L
        var loads = 0
        val cache = SportsFeedCache { now }
        assertFailsWith<IllegalStateException> {
            cache.getOrLoad<Any>("slow", 60_000) { loads++; error("offline") }
        }
        assertEquals(0, cache.size)
        val first = cache.getOrLoad("slow", 60_000) { loads++; now = 120_000; Any() }
        now = 179_999
        assertSame(first, cache.getOrLoad("slow", 60_000) { loads++; Any() })
        assertEquals(2, loads)
    }

    @Test
    fun backwardsClockKeepsFreshnessAndDoesNotPreventLaterCleanup() = runTest {
        var now = 120_000L
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("score", 60_000) { Any() }
        now = 0
        assertSame(first, cache.getOrLoad<Any>("score", 60_000) { error("Same freshness as before") })
        now = 180_000
        cache.getOrLoad("new", 60_000) { Any() }
        assertEquals(1, cache.size)
    }

    @Test
    fun retentionCannotShortenTheReuseWindowAndZeroAgeAlwaysLoads() = runTest {
        var now = 0L
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("score", 120_000, retainMs = 1) { Any() }
        now = 60_000
        cache.getOrLoad("other", 60_000) { Any() }
        assertSame(first, cache.getOrLoad<Any>("score", 120_000, retainMs = 1) { error("Still fresh") })
        var loads = 0
        repeat(3) { cache.getOrLoad("uncached", 0) { loads++; Any() } }
        assertEquals(3, loads)
    }

    @Test
    fun suspendedReloadDoesNotBlockAnotherKeyOrLoseItsCompletionTimestamp() = runTest {
        var now = 0L
        val cache = SportsFeedCache { now }
        cache.getOrLoad("slow", 60_000) { Any() }
        now = 60_000
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocked = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("slow", 60_000) {
                entered.complete(Unit)
                release.await()
                Any()
            }
        }
        entered.await()
        now = 120_000
        val other = cache.getOrLoad("other", 60_000) { Any() }
        assertSame(other, cache.getOrLoad<Any>("other", 60_000) { error("Still fresh") })
        release.complete(Unit)
        val loaded = blocked.await()
        now = 179_999
        assertSame(loaded, cache.getOrLoad<Any>("slow", 60_000) { error("Finished at 120000") })
    }

    @Test
    fun staleSweepSnapshotCannotRemoveAReplacementEntry() {
        val kept = SyncMap<String, Any>()
        val expired = Any()
        val replacement = Any()
        kept["score"] = expired
        val snapshotEntry = kept.snapshot().getValue("score")
        kept["score"] = replacement
        assertFalse(kept.remove("score", snapshotEntry))
        assertSame(replacement, kept["score"])
        assertTrue(kept.remove("score", replacement))
        assertEquals(0, kept.size)
        assertFalse(kept.remove("score", replacement))
    }

    @Test
    fun parallelCallersPreserveFreshEntriesWhileExpiredEntriesAreSwept() = runTest {
        val now = atomic(0L)
        val cache = SportsFeedCache { now.value }
        repeat(200) { cache.getOrLoad("old-$it", 60_000) { Any() } }
        now.value = 60_000
        coroutineScope {
            repeat(8) { worker ->
                launch(Dispatchers.Default) {
                    repeat(100) { i ->
                        val key = "fresh-$worker-$i"
                        val first = cache.getOrLoad(key, 1_800_000) { Any() }
                        assertSame(first, cache.getOrLoad<Any>(key, 1_800_000) { error("Fresh entry lost") })
                    }
                }
            }
        }
        assertEquals(800, cache.size)
    }

    @Test
    fun resultsAndLoadCountsMatchTheOriginalThroughChangingRequestWindows() = runTest {
        class Old(val at: Long, val value: String)
        var now = 0L
        var originalLoads = 0
        var changedLoads = 0
        val original = HashMap<String, Old>()
        val cache = SportsFeedCache { now }
        val random = Random(83)
        repeat(20_000) {
            now += random.nextInt(20_000)
            val family = random.nextInt(3)
            val key = "$family:${random.nextInt(40)}"
            val retain = when (family) { 0 -> 180_000L; 1 -> 1_800_000L; else -> 43_200_000L }
            val age = if (family == 1 && random.nextBoolean()) 60_000L else retain
            val old = original[key]
            val expected = if (old != null && now - old.at < age) old.value else {
                (++originalLoads).toString().also { original[key] = Old(now, it) }
            }
            val actual = cache.getOrLoad(key, age, retainMs = retain) { (++changedLoads).toString() }
            assertEquals(expected, actual)
        }
        assertEquals(originalLoads, changedLoads)
        assertTrue(cache.size < original.size)
    }
}
