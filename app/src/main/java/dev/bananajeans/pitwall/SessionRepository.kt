package dev.bananajeans.pitwall

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** Application-lifetime ordered mutations, independent of an Activity's lifecycle. */
object SessionRepository {
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val ready = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile private var revision = 0L
    private val deleted = mutableSetOf<String>()
    private lateinit var store: SessionStore

    @Synchronized fun initialize(context: Context) {
        if (::store.isInitialized) return
        store = SessionStore(context.applicationContext)
        scope.launch {
            for (command in commands) {
                try { command() } catch (e: Exception) { error.value = "Could not save changes: ${e.message}" }
            }
        }
        refresh()
    }

    fun refresh() {
        commands.trySend {
            val observedRevision = revision
            if (!RecorderService.active.value) store.recover()
            val loaded = store.list()
            withContext(Dispatchers.Main.immediate) {
                if (revision == observedRevision) sessions.value = loaded
                ready.value = true
            }
        }
    }

    /** Call from the main thread. Optimistic state updates keep successive edits consistent. */
    fun save(session: Session) {
        if (session.id in deleted) return
        revision++
        sessions.value = sessions.value.map { if (it.id == session.id) session else it }
        commands.trySend { store.save(session); error.value = null }
    }

    fun delete(id: String) {
        revision++
        deleted.add(id)
        sessions.value = sessions.value.filterNot { it.id == id }
        commands.trySend { store.delete(id) }
    }
}
