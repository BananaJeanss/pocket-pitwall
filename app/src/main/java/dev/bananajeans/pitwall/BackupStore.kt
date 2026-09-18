package dev.bananajeans.pitwall

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class BackupAllResult(val backedUp: Int, val failed: Int)
data class RestoreResult(val imported: Int, val skipped: Int, val failed: Int)

class BackupStore(private val context: Context) {
    private val resolver = context.contentResolver

    fun folderLabel(treeUri: String): String? {
        if (treeUri.isBlank()) return null
        return runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name ?: "Selected folder"
        }.getOrNull()
    }

    private fun tree(treeUri: String): DocumentFile {
        require(treeUri.isNotBlank()) { "Choose a backup folder first." }
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw IOException("Backup folder is unavailable.")
        require(folder.exists() && folder.isDirectory) { "Backup folder is unavailable." }
        require(folder.canWrite()) { "Pocket Pitwall no longer has write access to the backup folder." }
        return folder
    }

    private fun digest(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest()
    }

    private fun digest(uri: Uri): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        requireNotNull(resolver.openInputStream(uri)).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest()
    }

    fun backupSession(store: SessionStore, session: Session, treeUri: String) {
        val folder = tree(treeUri)
        val staged = File.createTempFile("pitwall-backup-", ".zip", context.cacheDir)
        try {
            staged.outputStream().buffered().use { store.writeZip(session, it) }
            require(staged.length() > 0L) { "Backup produced no data." }
            val expected = digest(staged)

            val prefix = "pitwall-${session.id}-"
            val name = "$prefix${System.currentTimeMillis()}.zip"
            val document = folder.createFile("application/zip", name)
                ?: throw IOException("Could not create backup file.")

            try {
                requireNotNull(resolver.openOutputStream(document.uri, "wt")).buffered().use { output ->
                    staged.inputStream().buffered().use { it.copyTo(output) }
                    output.flush()
                }
                require(expected.contentEquals(digest(document.uri))) { "Backup verification failed." }
            } catch (e: Exception) {
                runCatching { document.delete() }
                throw e
            }

            folder.listFiles().forEach { candidate ->
                val candidateName = candidate.name.orEmpty()
                if (candidate.uri != document.uri && candidateName.startsWith(prefix) && candidateName.endsWith(".zip")) {
                    runCatching { candidate.delete() }
                }
            }
        } finally {
            staged.delete()
        }
    }

    fun backupAll(store: SessionStore, sessions: List<Session>, treeUri: String): BackupAllResult {
        var backedUp = 0
        var failed = 0
        sessions.filter { it.status != "recording" }.forEach { session ->
            runCatching { backupSession(store, session, treeUri) }
                .onSuccess { backedUp++ }
                .onFailure { failed++ }
        }
        return BackupAllResult(backedUp, failed)
    }

    fun restoreMissing(store: SessionStore, treeUri: String): RestoreResult {
        val files = tree(treeUri).listFiles()
            .filter { it.isFile && it.name.orEmpty().startsWith("pitwall-") && it.name.orEmpty().endsWith(".zip") }
            .sortedByDescending { it.name.orEmpty() }

        var imported = 0
        var skipped = 0
        var failed = 0
        files.forEach { file ->
            runCatching {
                requireNotNull(resolver.openInputStream(file.uri)).use { store.importBackupZip(it) }
            }.onSuccess { session ->
                if (session == null) skipped++ else imported++
            }.onFailure { failed++ }
        }
        return RestoreResult(imported, skipped, failed)
    }
}
