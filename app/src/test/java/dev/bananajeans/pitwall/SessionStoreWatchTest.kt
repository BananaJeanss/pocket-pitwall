package dev.bananajeans.pitwall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bananajeans.pitwall.core.Telemetry
import dev.bananajeans.pitwall.protocol.ClockSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Session-model compatibility tests (issue #22 acceptance):
 * old stored sessions load unchanged; watch metadata round-trips; missing
 * watch data is represented honestly.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreWatchTest {

    private lateinit var store: SessionStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SessionStore(context)
    }

    private fun phoneOnlySession() = Session(
        id = "phone-only-1",
        title = "Old session",
        status = "complete",
        duration = 60.0,
        marks = listOf(Telemetry.Mark(5.0, "SF", false), Telemetry.Mark(45.0, "SF", false))
    )

    private fun watchInfo(complete: Boolean = true) = WatchSessionInfo(
        status = WatchSessionInfo.Status.IMPORTED,
        deviceModel = "Galaxy Watch7",
        watchAppVersion = "0.3.0",
        logComplete = complete,
        sampleCount = 12345,
        sensorRates = mapOf(1 to 100.0, 4 to 200.0),
        sync = WatchSessionInfo.Sync(
            offsetWatchMinusPhone = 37.5e9,
            driftPerNano = 10e-6,
            bestRttNanos = 8_000_000.0,
            residualRmsNanos = 900_000.0,
            exchangesUsed = 12,
            quality = ClockSync.Quality.EXCELLENT
        ),
        logFile = "watch.pwtch",
        phoneStartNanos = 60_000_000_000L,
        metrics = null
    )

    @Test
    fun phoneOnlySessionRoundTripsUnchanged() {
        val original = phoneOnlySession()
        store.save(original)
        val loaded = store.list().first { it.id == original.id }
        assertNull(loaded.watch)
        assertEquals(original.title, loaded.title)
        assertEquals(original.marks.size, loaded.marks.size)
        assertEquals(original.duration, loaded.duration, 1e-9)
        // JSON has no watch key at all.
        val raw = java.io.File(java.io.File(context.filesDir, "sessions"), original.id)
            .resolve("session.json").readText()
        assertFalse(raw.contains("\"watch\""))
    }

    @Test
    fun watchMetadataRoundTrips() {
        val original = phoneOnlySession().copy(id = "with-watch-1", watch = watchInfo())
        store.save(original)
        val loaded = store.list().first { it.id == original.id }
        val watch = requireNotNull(loaded.watch)
        assertEquals(WatchSessionInfo.Status.IMPORTED, watch.status)
        assertEquals("Galaxy Watch7", watch.deviceModel)
        assertEquals(12345L, watch.sampleCount)
        assertTrue(watch.logComplete)
        assertEquals(200.0, watch.sensorRates[4]!!, 1e-9)
        assertEquals(ClockSync.Quality.EXCELLENT, watch.sync?.quality)
        assertEquals(37.5e9, watch.sync?.offsetWatchMinusPhone!!, 1e3)
        assertEquals("watch.pwtch", watch.logFile)
        // P0: the phone monotonic anchor is persisted with the session.
        assertEquals(60_000_000_000L, watch.phoneStartNanos)
    }

    @Test
    fun phoneStartElapsedNanosRoundTripsAndLegacyDefaultsToZero() {
        // New sessions carry the phone monotonic anchor.
        val anchored = phoneOnlySession().copy(id = "anchored-1", phoneStartElapsedNanos = 42_000_000_000L)
        store.save(anchored)
        assertEquals(42_000_000_000L, store.list().first { it.id == anchored.id }.phoneStartElapsedNanos)

        // Old sessions recorded before the anchor existed keep working: the
        // field is absent in JSON and defaults to 0 (no epoch mixing).
        val dir = java.io.File(java.io.File(context.filesDir, "sessions"), "legacy-1").apply { mkdirs() }
        java.io.File(dir, "session.json").writeText(
            """{"schemaVersion":1,"id":"legacy-1","created":1730000000000,"title":"L",
               "direction":"Normal","status":"complete","duration":1.0,"lengthMeters":0.0,
               "sensors":"","notes":"","marks":[],"track":[],"pins":{}}"""
        )
        assertEquals(0L, store.list().first { it.id == "legacy-1" }.phoneStartElapsedNanos)
    }

    @Test
    fun incompleteWatchLogIsRepresentedHonestly() {
        val original = phoneOnlySession().copy(id = "partial-watch", watch = watchInfo(complete = false))
        store.save(original)
        val loaded = store.list().first { it.id == original.id }
        assertFalse(loaded.watch!!.logComplete)
    }

    @Test
    fun corruptWatchKeyDoesNotBreakLoading() {
        // A malformed watch payload must not make the session unreadable.
        val dir = java.io.File(java.io.File(context.filesDir, "sessions"), "broken-watch").apply { mkdirs() }
        val json = """{"schemaVersion":1,"id":"broken-watch","created":1730000000000,"title":"X",
            "direction":"Normal","status":"complete","duration":1.0,"lengthMeters":0.0,"sensors":"",
            "notes":"","marks":[],"track":[],"pins":{},"watch":"{not valid json"}"""
        java.io.File(dir, "session.json").writeText(json)
        val loaded = store.list().firstOrNull { it.id == "broken-watch" }
        requireNotNull(loaded)
        assertNull(loaded.watch)
        assertEquals("X", loaded.title)
    }

    @Test
    fun zipExportIncludesWatchLogWhenPresent() {
        val info = watchInfo()
        val original = phoneOnlySession().copy(id = "zip-watch", watch = info)
        store.save(original)
        val folder = store.folder("zip-watch")
        java.io.File(folder, "sensors.csv").writeText("elapsed_s,sensor_type,x,y,z,w,accuracy\n0.0,10,0,0,0,0,3\n")
        java.io.File(folder, "watch.pwtch").writeBytes(byteArrayOf(1, 2, 3, 4))
        val bytes = java.io.ByteArrayOutputStream().let { buffer ->
            store.writeZip(store.list().first { it.id == "zip-watch" }, buffer)
            buffer.toByteArray()
        }
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry?.name }.toList()
        }
        assertTrue(names.contains("watch/watch.pwtch"))
        assertTrue(names.contains("sensors.csv"))
    }

    @Test
    fun zipImportRestoresWatchLog() {
        // Export with watch data, delete the session, re-import: metadata + log survive.
        val original = phoneOnlySession().copy(id = "roundtrip-watch", watch = watchInfo())
        store.save(original)
        val folder = store.folder("roundtrip-watch")
        java.io.File(folder, "sensors.csv").writeText("elapsed_s,sensor_type,x,y,z,w,accuracy\n0.0,10,0,0,0,0,3\n")
        java.io.File(folder, "watch.pwtch").writeBytes(byteArrayOf(9, 8, 7))
        val bytes = java.io.ByteArrayOutputStream().let { buffer ->
            store.writeZip(store.list().first { it.id == "roundtrip-watch" }, buffer)
            buffer.toByteArray()
        }
        store.delete("roundtrip-watch")
        val imported = store.importBackupZip(bytes.inputStream())
        requireNotNull(imported)
        requireNotNull(imported.watch)
        assertTrue(java.io.File(store.folder("roundtrip-watch"), "watch.pwtch").isFile)
    }
}
