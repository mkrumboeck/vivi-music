package at.techtown.sonoscontroller

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class LocalAudioFilesTest {
    @Test fun localFileIsValidatedAndNeverDeleted() {
        val file = File.createTempFile("sonos-test", ".mp3")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val audio = localAudioFile(file, null)
            assertEquals("audio/mpeg", audio.mimeType)
            audio.close()
            assertTrue(file.exists())
        } finally { file.delete() }
    }
    @Test fun emptyLocalFileIsRejected() {
        val file = File.createTempFile("sonos-test", ".mp3")
        try {
            try { localAudioFile(file, null); fail("Expected invalid source") }
            catch (e: SonosException) { assertEquals(SonosErrorCode.INVALID_SOURCE, e.code) }
        } finally { file.delete() }
    }
    @Test fun contentBytesAreCopiedAndOnlyTheCopyIsDeleted() = runBlocking {
        val directory = Files.createTempDirectory("sonos-copy").toFile()
        try {
            val bytes = ByteArray(100_000) { (it % 256).toByte() }
            val audio = copyContentAudio(ByteArrayInputStream(bytes), directory, "audio/flac")
            assertArrayEquals(bytes, audio.file.readBytes())
            assertEquals("audio/flac", audio.mimeType)
            audio.close(); audio.close()
            assertFalse(audio.file.exists())
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.delete() }
    }
    @Test fun failedContentReadClosesInputAndDeletesPartialCopy() = runBlocking {
        val directory = Files.createTempDirectory("sonos-copy").toFile()
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("Read denied")
            override fun close() { closed = true }
        }
        try {
            try { copyContentAudio(input, directory, "audio/flac"); fail("Expected read failure") }
            catch (_: IOException) {}
            assertTrue(closed)
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.delete() }
    }
    @Test fun cancelledContentCopyLeavesNoTemporaryFile() = runBlocking {
        val directory = Files.createTempDirectory("sonos-copy").toFile()
        val job = Job()
        val input = object : ByteArrayInputStream(ByteArray(100_000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                job.cancel()
                return super.read(buffer, offset, length)
            }
        }
        try {
            try { withContext(job) { copyContentAudio(input, directory, "audio/flac") }; fail("Expected cancellation") }
            catch (_: CancellationException) {}
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.delete() }
    }
}
