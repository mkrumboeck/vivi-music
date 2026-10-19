package at.techtown.sonoscontroller

import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class PreparedLocalAudio(val file: File, val mimeType: String, private val temporary: Boolean) : Closeable {
    override fun close() { if (temporary) file.delete() }
}
internal fun localAudioFile(file: File, mimeType: String?): PreparedLocalAudio {
    if (!file.isFile || !file.canRead() || file.length() == 0L)
        throw SonosException(SonosErrorCode.INVALID_SOURCE, "Die Audiodatei ist leer oder nicht lesbar.")
    return PreparedLocalAudio(file, audioMime(mimeType, file.name), false)
}
internal suspend fun copyContentAudio(input: InputStream, directory: File, mimeType: String): PreparedLocalAudio = input.use { from ->
    val copy = File.createTempFile("sonos-", ".audio", directory)
    try {
        copy.outputStream().use { to ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = from.read(buffer)
                if (count < 0) break
                to.write(buffer, 0, count)
            }
        }
        if (copy.length() == 0L) throw SonosException(SonosErrorCode.INVALID_SOURCE, "Die ausgewählte Audiodatei ist leer.")
        PreparedLocalAudio(copy, mimeType, true)
    } catch (error: Exception) {
        copy.delete()
        throw error
    }
}
