package dev.bananajeans.pitwall.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A bounded integrity header followed by the unmodified watch log bytes. */
object WatchLogTransfer {
    private const val MAGIC = 0x50575458 // PWTX
    private const val MAX_METADATA_BYTES = 4096

    fun write(input: InputStream, metadata: WatchLogCodec.SourceMeta, output: OutputStream) {
        val bytes = metadata.encodeJson().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_METADATA_BYTES) { "Invalid transfer metadata length" }
        val header = DataOutputStream(output)
        header.writeInt(MAGIC)
        header.writeInt(bytes.size)
        header.write(bytes)
        input.copyTo(output)
        output.flush()
    }

    /** Leaves [input] positioned at the raw log; does not buffer payload bytes. */
    fun readMetadata(input: InputStream): WatchLogCodec.SourceMeta {
        val header = DataInputStream(input)
        require(header.readInt() == MAGIC) { "Unsupported watch transfer; update both apps" }
        val size = header.readInt()
        require(size in 1..MAX_METADATA_BYTES) { "Invalid transfer metadata length" }
        val bytes = ByteArray(size)
        header.readFully(bytes)
        return requireNotNull(WatchLogCodec.SourceMeta.parseJson(String(bytes, Charsets.UTF_8))) {
            "Invalid watch transfer metadata"
        }
    }
}
