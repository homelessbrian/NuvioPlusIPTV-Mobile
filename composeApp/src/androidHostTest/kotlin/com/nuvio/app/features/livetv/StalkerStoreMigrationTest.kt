package com.nuvio.app.features.livetv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.system.Os
import com.nuvio.app.features.livetv.data.EpgStore
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.livetv.ondemand.OnDemandStore
import com.nuvio.app.features.livetv.ondemand.VodItem
import com.nuvio.app.features.livetv.ondemand.VodKind
import com.nuvio.stalker.StalkerConfig
import com.nuvio.stalker.StalkerContentKind
import com.nuvio.stalker.StalkerError
import com.nuvio.stalker.StalkerException
import com.nuvio.stalker.StalkerStreamRef
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real production SQLiteOpenHelpers, using temporary JVM-only files and synthetic provider data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StalkerStoreMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val restoredHelpers = mutableListOf<Pair<Any, SQLiteOpenHelper?>>()
    private var oldFilesContext: Context? = null

    @BeforeTest fun prepareTemporaryStores() {
        val databaseDirectory = temporary.newFolder("databases")
        val filesDirectory = temporary.newFolder("files")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(databaseDirectory, name)
            override fun getFilesDir(): File = filesDirectory
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                openOrCreateDatabase(name, mode, factory, null)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY or if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0,
                    errorHandler)
        }
        val field = LiveTvFiles.javaClass.getDeclaredField("appContext").apply { isAccessible = true }
        oldFilesContext = field.get(LiveTvFiles) as? Context
        field.set(LiveTvFiles, context)
    }

    @AfterTest fun restoreProductionSingletonReferences() {
        restoredHelpers.asReversed().forEach { (store, previous) ->
            val field = store.javaClass.getDeclaredField("helper").apply { isAccessible = true }
            (field.get(store) as? SQLiteOpenHelper)?.takeIf { it !== previous }?.close()
            field.set(store, previous)
        }
        LiveTvFiles.javaClass.getDeclaredField("appContext").apply { isAccessible = true }.set(LiveTvFiles, oldFilesContext)
    }

    private fun installHelper(store: Any, rememberPrevious: Boolean = true) {
        val field = store.javaClass.getDeclaredField("helper").apply { isAccessible = true }
        val previous = field.get(store) as? SQLiteOpenHelper
        if (rememberPrevious) restoredHelpers += store to previous else previous?.close()
        val helperClass = store.javaClass.declaredClasses.single { it.simpleName == "Helper" }
        val constructor = helperClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        field.set(store, constructor.newInstance(context) as SQLiteOpenHelper)
    }

    private fun database(name: String): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)

    private fun legacyGuide() = database("livetv_guide.db").use { db ->
        db.execSQL("CREATE TABLE programs (ch TEXT NOT NULL,start INTEGER NOT NULL,stop INTEGER NOT NULL,title TEXT NOT NULL,descr TEXT,cat TEXT,ep TEXT,icon TEXT,year INTEGER,people TEXT)")
        db.execSQL("CREATE INDEX programs_ch_start ON programs(ch,start)")
        db.execSQL("CREATE INDEX programs_stop ON programs(stop)")
        db.execSQL("CREATE INDEX programs_start ON programs(start)")
        db.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY,source TEXT NOT NULL,xmltv TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY,v TEXT)")
        db.execSQL("INSERT INTO programs VALUES (?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>("legacy", 1_000L, 2_000L, "Équipe ⚽", "Existing description", "Sport", "S1 E2", "fixture-icon", 2025, "Actor\u001FDirector"))
        db.execSQL("INSERT INTO auto VALUES ('legacy','guide','provider-channel')")
        db.execSQL("INSERT INTO meta VALUES ('fingerprint','retained-guide')")
        db.version = 3
    }

    private fun legacyCatalog() = database("livetv_on_demand.db").use { db ->
        db.execSQL("CREATE TABLE items (pl TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,norm TEXT NOT NULL,icon TEXT,cat TEXT NOT NULL,ext TEXT,tmdb TEXT,year INTEGER,rating TEXT,added INTEGER NOT NULL DEFAULT 0,genre TEXT,h INTEGER,url TEXT,PRIMARY KEY(pl,kind,id))")
        db.execSQL("CREATE TABLE categories (pl TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(pl,kind,id))")
        db.execSQL("CREATE TABLE posters (uid TEXT PRIMARY KEY,poster TEXT,checked INTEGER NOT NULL,title TEXT,tmdb TEXT)")
        db.execSQL("CREATE TABLE prefixes (pl TEXT NOT NULL,prefix TEXT NOT NULL,PRIMARY KEY(pl,prefix))")
        db.execSQL("INSERT INTO items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>("legacy", "movie", "m", "Existing movie", "existingmovie", "icon", "g", "mp4", "17", 2025, "8", 123L, "Drama", 0L, "https://media.example.invalid/fixture.mp4"))
        db.execSQL("INSERT INTO categories VALUES ('legacy','movie','g','Existing group',0)")
        db.execSQL("INSERT INTO posters VALUES ('movie:legacy:m','poster',1000,'Matched title','17')")
        db.execSQL("INSERT INTO prefixes VALUES ('legacy','fixture')")
        db.version = 1
    }

    private val identity = "a".repeat(64)
    private fun ref(kind: StalkerContentKind, id: String, selector: String? = null) = StalkerStreamRef(kind, id,
        command = "opaque fixture command", seriesSelector = selector, movieId = if (selector == null) null else "parent",
        seasonId = if (selector == null) null else "season", sourceIdentity = identity, hasFiles = true)
    private fun movie(id: String, title: String = "Movie") = VodItem("portal", VodKind.MOVIE, id, title, "poster", "g",
        null, null, 2025, "8", 0, stalker = ref(StalkerContentKind.VOD, id), plot = "Synopsis", durationSecs = 7_200)

    @Test fun legacyGuideMigrationRetainsRowsAndArchiveIdentitySurvivesLightFullReadsAndReopen() {
        legacyGuide()
        installHelper(EpgStore)
        assertEquals(1L, EpgStore.count())
        assertEquals("retained-guide", EpgStore.fingerprint())
        val legacy = assertNotNull(EpgStore.program("legacy", 1_000))
        assertEquals("Existing description", legacy.description)
        assertEquals(listOf("Actor", "Director"), legacy.people)
        assertNull(legacy.stalkerProgramId)
        assertFalse(legacy.stalkerArchiveAvailable)
        val archive = legacy.copy(stalkerProgramId = "provider-programme", stalkerArchiveAvailable = true, stalkerSourceIdentity = identity)
        val future = EpgProgram(10_000, 11_000, "Future", description = "Future description")
        EpgStore.replaceChannels("new-guide", setOf("legacy"), mapOf("legacy" to listOf(archive, future)),
            mapOf("legacy" to EpgAssignment("guide", "provider-channel")))
        installHelper(EpgStore, rememberPrevious = false)
        assertEquals(archive, EpgStore.program("legacy", 1_000))
        val light = EpgStore.range(0, 20_000, light = true).getValue("legacy")
        assertEquals(2, light.size)
        assertEquals("provider-programme", light[0].stalkerProgramId)
        assertTrue(light[0].stalkerArchiveAvailable)
        assertEquals(identity, light[0].stalkerSourceIdentity)
        assertNull(light[0].description)
        assertEquals("S1 E2", light[0].episode)
        assertEquals(future, EpgStore.channelRange("legacy", 9_000, 12_000).single())
        assertEquals(EpgAssignment("guide", "provider-channel"), EpgStore.autoMatches()["legacy"])
    }

    @Test fun legacyCatalogMigrationRetainsExistingTitlesAndPersistsMovieAndBothSeriesNamespaces() {
        legacyCatalog()
        installHelper(OnDemandStore)
        assertEquals("Existing movie", OnDemandStore.item("legacy", VodKind.MOVIE, "m")?.name)
        assertEquals("Matched title", OnDemandStore.cachedMatch("movie:legacy:m")?.title)
        assertTrue("fixture" in OnDemandStore.loadPrefixes())
        val film = movie("remote")
        val classic = film.copy(kind = VodKind.SERIES, id = "CLASSIC:1", categoryId = "CLASSIC:g",
            stalker = ref(StalkerContentKind.SERIES, "1", "1,2"), stalkerSeriesVariant = "CLASSIC")
        val vodSeries = classic.copy(id = "VOD:1", categoryId = "VOD:g", stalkerSeriesVariant = "VOD",
            stalker = ref(StalkerContentKind.SERIES, "1"))
        OnDemandStore.replace("portal", VodKind.MOVIE, listOf("g" to "Movies"), sequenceOf(film))
        OnDemandStore.replace("portal", VodKind.SERIES, listOf("CLASSIC:g" to "Native", "VOD:g" to "VOD series"), sequenceOf(classic, vodSeries))
        installHelper(OnDemandStore, rememberPrevious = false)
        assertEquals(film, OnDemandStore.item("portal", VodKind.MOVIE, film.id))
        assertEquals(classic, OnDemandStore.item("portal", VodKind.SERIES, classic.id))
        assertEquals(vodSeries, OnDemandStore.item("portal", VodKind.SERIES, vodSeries.id))
        assertEquals(2, OnDemandStore.count(VodKind.SERIES))
        assertEquals(setOf("CLASSIC:g", "VOD:g"), OnDemandStore.categories(VodKind.SERIES).map { it.id }.toSet())
        assertEquals("https://media.example.invalid/fixture.mp4", OnDemandStore.item("legacy", VodKind.MOVIE, "m")?.url)
    }

    @Test fun sourceChangeAtPublicationRollsBackRowsAndCategoryChanges() {
        installHelper(OnDemandStore)
        val original = movie("m", "Original")
        OnDemandStore.replace("portal", VodKind.MOVIE, listOf("g" to "Original group"), sequenceOf(original))
        val source = PlaylistSource("portal", "Portal", "", providerKind = "stalker",
            stalker = StalkerConfig("https://portal.example.invalid/c/", "02:00:00:00:00:01"))
        val prefs = LiveTvPreferencesStore().apply { installPersistence(load = { null }, save = {}); updatePlaylists { listOf(source) } }
        assertFailsWith<StalkerException> {
            OnDemandStore.replace("portal", VodKind.MOVIE, listOf("changed" to "Changed group"),
                sequenceOf(original.copy(name = "Changed", categoryId = "changed")), publication = { commit ->
                    prefs.updatePlaylists { emptyList() }
                    prefs.withCurrentPlaylist(source, commit)
                })
        }
        assertEquals(original, OnDemandStore.item("portal", VodKind.MOVIE, "m"))
        assertEquals(listOf("Original group"), OnDemandStore.categories(VodKind.MOVIE).map { it.name })
    }

    @Test
    @Config(shadows = [StalkerAtomicRenameShadow::class])
    fun streamingFailureOrSourceRetirementPreservesPreviousCompressedSnapshot() = runBlocking {
        LiveTvFiles.writeTextStream("fixture", "guide") { emit -> emit("existing\n") }
        assertEquals("existing\n", LiveTvFiles.readChunks("fixture", "guide") { it.joinToString("") })
        assertFailsWith<StalkerException> {
            LiveTvFiles.writeTextStream("fixture", "guide") { emit ->
                emit("incomplete replacement\n")
                throw StalkerException(StalkerError.INVALID_RESPONSE)
            }
        }
        assertEquals("existing\n", LiveTvFiles.readChunks("fixture", "guide") { it.joinToString("") })
        assertFailsWith<StalkerException> {
            LiveTvFiles.writeTextStream("fixture", "guide", publish = { throw StalkerException(StalkerError.STALE_SESSION) }) { it("new\n") }
        }
        assertEquals("existing\n", LiveTvFiles.readChunks("fixture", "guide") { it.joinToString("") })
        assertTrue(LiveTvFiles.list("fixture").none { it.endsWith(".tmp") })
        LiveTvFiles.writeTextStream("fixture", "guide") { it("complete replacement\n") }
        assertEquals("complete replacement\n", LiveTvFiles.readChunks("fixture", "guide") { it.joinToString("") })
    }
}

/** Robolectric's ShadowOs/ShadowLinux omit rename. Supply its atomic host equivalent only for this test. */
@Implements(Os::class)
class StalkerAtomicRenameShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(from: String, to: String) {
            Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
