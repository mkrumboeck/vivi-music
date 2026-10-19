package at.techtown.sonoscontroller

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioSourceAndroidTest {
    @Test fun contentUriCanBeReadCopiedAndReleasedWithoutDeletingOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "sonos-source-test").apply { mkdirs() }
        val original = File(directory, "test.mp3")
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            original.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".sonos-source-tests", original)
            val source = AudioSource.ContentUri(uri)
            assertEquals("content", source.uri.scheme)
            val mime = context.contentResolver.getType(source.uri)
            assertEquals("audio/mpeg", mime)
            val input = context.contentResolver.openInputStream(source.uri)!!
            val prepared = copyContentAudio(input, context.cacheDir, audioMime(mime, null))
            assertArrayEquals(bytes, prepared.file.readBytes())
            prepared.close()
            assertFalse(prepared.file.exists())
            assertTrue(original.exists())
        } finally { original.delete(); directory.delete() }
    }
    @Test fun localFileSourceKeepsCallerOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File.createTempFile("sonos-source-test", ".flac", context.cacheDir)
        try {
            original.writeBytes(byteArrayOf(1, 2, 3))
            val source = AudioSource.LocalFile(original)
            val prepared = localAudioFile(source.file, source.mimeType)
            assertEquals("audio/flac", prepared.mimeType)
            prepared.close()
            assertTrue(original.exists())
        } finally { original.delete() }
    }
    @Test fun streamUrlUsesDirectAddressWithoutAnAndroidFileServer() {
        val source = AudioSource.Url("https://example.org/live", "audio/aac")
        assertEquals("https", validateStreamUrl(source.url).scheme)
        assertEquals("audio/aac", audioMime(source.mimeType, null))
    }
}
