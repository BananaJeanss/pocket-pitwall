package dev.bananajeans.pitwall.wear

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TransferQueueTest {
    private lateinit var context: Context
    private lateinit var store: WatchLogStore
    private var clock = 100_000L
    private val attempts = mutableListOf<String>()
    private var send: suspend (String) -> Unit = {}
    private lateinit var queue: TransferQueue

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = WatchLogStore(context)
        queue = TransferQueue(context, object : TransferQueue.Transport {
            override suspend fun connectedNodes() = listOf("phone")
            override suspend fun send(nodeId: String, sessionId: String, file: File, metadata: WatchLogCodec.SourceMeta) {
                attempts.add(sessionId)
                send(sessionId)
            }
        }, { clock }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    private fun log(id: String, modified: Long) {
        store.startRecording(id)
        val (writer, stream) = store.writer(id, WatchLogCodec.Metadata(
            id, "0.3.2", "TestWatch", 1000, 10_000_000_000, emptyList(), 1))
        writer.appendSamples(4, listOf(WatchLogCodec.Sample(4, 10_000_000_000, .1, 0.0, 0.0, 0.0, 3)))
        writer.finish()
        stream.close()
        store.markFinalized(id, true)
        store.logFile(id).setLastModified(modified)
    }

    @Test fun failedFirstLogDoesNotBlockLaterLogsAndCanRetry() = runBlocking {
        log("bad-first", 1)
        log("good-second", 2)
        send = { if (it == "bad-first") throw IOException("Link interrupted") }
        queue.serveAll()?.join()
        assertEquals(listOf("bad-first", "good-second"), attempts)
        assertEquals(2, queue.pendingCount())
        attempts.clear()
        clock += 10_001
        send = {}
        queue.serveAll()?.join()
        assertEquals(listOf("bad-first", "good-second"), attempts)
    }

    @Test fun negativeAckOnlyBacksOffItsOwnLog() = runBlocking {
        log("rejected", 1)
        log("next", 2)
        queue.onAck(Messages.TransferAck("rejected", "rejected", false, "Retry"))
        queue.serveAll()?.join()
        assertEquals(listOf("next"), attempts)
        assertTrue(store.logFile("rejected").isFile)
    }

    @Test fun duplicatePullJoinsTheExistingTransfer() = runBlocking {
        log("one", 1)
        val release = CompletableDeferred<Unit>()
        send = { release.await() }
        val first = queue.serveAll()
        val second = queue.serveAll()
        assertSame(first, second)
        assertEquals(listOf("one"), attempts)
        release.complete(Unit)
        first?.join()
    }

    @Test fun acceptedAckRemovesPendingButKeepsRecoveryCopy() {
        log("safe", 1)
        val bytes = store.logFile("safe").readBytes()
        queue.onAck(Messages.TransferAck("safe", "safe", true, null))
        queue.onAck(Messages.TransferAck("safe", "safe", true, null))
        assertEquals(0, queue.pendingCount())
        assertEquals(WatchLogStore.State.IMPORTED, store.stateOf("safe"))
        assertArrayEquals(bytes, store.logFile("safe").readBytes())
    }

    @Test fun manifestCanWakeTheWatchForPullAndControlMessages() {
        for (path in listOf("/pitwall/log/pull", Messages.PATH)) {
            val services = context.packageManager.queryIntentServices(Intent(
                "com.google.android.gms.wearable.MESSAGE_RECEIVED").setData(Uri.parse("wear://phone$path")), 0)
            assertTrue("No background listener for $path", services.any {
                it.serviceInfo.name == WatchDataLayerService::class.java.name && it.serviceInfo.exported
            })
        }
    }
}
