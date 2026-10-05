package dev.bananajeans.pitwall.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesTest {

    private val msgTypes = listOf(
        Messages.Hello(Messages.Role.PHONE, "0.2.5", 1),
        Messages.Hello(Messages.Role.WATCH, "0.2.5", 1),
        Messages.Start("session-1", "Motorcity · Underground", "Reverse", 1730000000000L, 7L),
        Messages.StartAck("session-1", recording = true, watchAppVersion = "0.2.5", protocolVersion = 1),
        Messages.Stop("session-1", 8L),
        Messages.StopAck("session-1", finalized = true, logId = "session-1", protocolVersion = 1),
        Messages.Status("session-1", recording = true, localLoggingHealthy = true, pendingTransfers = 2, batteryPct = 77),
        Messages.Status(null, recording = false, localLoggingHealthy = false, pendingTransfers = 0, batteryPct = null),
        Messages.SyncPing("session-1", 123456789L),
        Messages.SyncPong("session-1", 123456789L, 987654321L, 987656789L),
        Messages.Result("session-1", bestLapSeconds = 42.5, lapCount = 3, steeringSmoothness = 0.8, correctionCount = 12, peakHr = 165, averageHr = 140, watchDataQuality = "good", notes = null),
        Messages.Result("session-1", bestLapSeconds = null, lapCount = 0, steeringSmoothness = null, correctionCount = null, peakHr = null, averageHr = null, watchDataQuality = null, notes = "no watch data"),
        Messages.TransferAck("session-1", "session-1", accepted = true),
        Messages.TransferAck("session-1", "session-1", accepted = false, reason = "checksum mismatch")
    )

    @Test
    fun everyMessageRoundTrips() {
        for (message in msgTypes) {
            val decoded = Messages.decode(Messages.encode(message))
            assertEquals(message, decoded, "Round-trip failed for ${message.type}")
        }
    }

    @Test
    fun encodeIncludesTypeAndProtocolVersion() {
        val root = PitwallJson.parse(String(Messages.encode(Messages.Stop("s", 1L)), Charsets.UTF_8)) as PitwallJson.Value.Object
        assertEquals("stop", root.string("t"))
        assertEquals(1, root.number("v")!!.toInt())
    }

    @Test
    fun unknownTypeDecodesToUnknown() {
        val bytes = PitwallJson.write(PitwallJson.obj("t" to PitwallJson.s("futureMessageType"), "v" to PitwallJson.n(99L))).toByteArray()
        val decoded = Messages.decode(bytes)
        assertIs<Messages.Unknown>(decoded)
        assertEquals("futureMessageType", decoded.rawType)
    }

    @Test
    fun missingRequiredFieldsThrow() {
        fun message(t: String, vararg fields: Pair<String, PitwallJson.Value>) =
            PitwallJson.write(PitwallJson.obj("t" to PitwallJson.s(t), *fields)).toByteArray()
        assertFailsWith<PitwallJson.JsonException> { Messages.decode(message("start", "title" to PitwallJson.s("x"))) }
        assertFailsWith<PitwallJson.JsonException> { Messages.decode(message("stop", "seq" to PitwallJson.n(1L))) }
        assertFailsWith<PitwallJson.JsonException> { Messages.decode(message("syncPing", "sid" to PitwallJson.s("s"))) }
        assertFailsWith<PitwallJson.JsonException> { Messages.decode(message("transferAck", "sid" to PitwallJson.s("s"))) }
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = PitwallJson.obj(
            "t" to PitwallJson.s(Messages.TYPE_STOP),
            "v" to PitwallJson.n(1L),
            "sid" to PitwallJson.s("s1"),
            "seq" to PitwallJson.n(3L),
            "extraFieldFromNewerPhone" to PitwallJson.s("ignored")
        )
        assertEquals(Messages.Stop("s1", 3L), Messages.decode(PitwallJson.write(json).toByteArray()))
    }

    @Test
    fun nullVsMissingSessionIdInStatus() {
        val json = PitwallJson.obj(
            "t" to PitwallJson.s(Messages.TYPE_STATUS),
            "v" to PitwallJson.n(1L),
            "sid" to PitwallJson.Value.Null,
            "recording" to PitwallJson.b(false),
            "localOk" to PitwallJson.b(true),
            "pending" to PitwallJson.n(0L)
        )
        val decoded = Messages.decode(PitwallJson.write(json).toByteArray())
        assertIs<Messages.Status>(decoded)
        assertNull(decoded.sessionId)
    }

    @Test
    fun nonObjectPayloadsAreRejected() {
        assertFailsWith<PitwallJson.JsonException> { Messages.decode("[1,2,3]".toByteArray()) }
        assertFailsWith<PitwallJson.JsonException> { Messages.decode("42".toByteArray()) }
    }
}
