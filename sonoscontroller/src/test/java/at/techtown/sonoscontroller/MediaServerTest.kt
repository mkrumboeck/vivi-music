package at.techtown.sonoscontroller

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class MediaServerTest {
    @Test fun rangesHandleClosedOpenSuffixAndClipping() {
        assertEquals(ByteRange(2, 5), ByteRange.parse("bytes=2-5", 10))
        assertEquals(ByteRange(7, 9), ByteRange.parse("bytes=7-", 10))
        assertEquals(ByteRange(7, 9), ByteRange.parse("bytes=-3", 10))
        assertEquals(ByteRange(0, 9), ByteRange.parse("bytes=-99", 10))
        assertEquals(ByteRange(8, 9), ByteRange.parse("bytes=8-99", 10))
    }
    @Test fun malformedAndUnsatisfiableRangesAreRejected() {
        listOf("bytes=10-", "bytes=8-2", "bytes=-0", "bytes=-", "bytes=1-2,4-5",
            "bytes=abc-2", "items=1-2", "bytes=999999999999999999999-").forEach {
            assertNull(it, ByteRange.parse(it, 10))
        }
        assertNull(ByteRange.parse("bytes=0-", 0))
    }
    private fun withServer(test: (String) -> Unit) {
        val file = File.createTempFile("sonos-media", ".m4a")
        try {
            file.writeText("0123456789")
            LocalMediaServer(file, InetAddress.getByName("127.0.0.1")).use { test(it.url) }
        } finally { file.delete() }
    }
    private fun request(url: String, method: String = "GET", range: String? = null,
                        check: (HttpURLConnection) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            conn.requestMethod = method
            range?.let { conn.setRequestProperty("Range", it) }
            check(conn)
        } finally { conn.disconnect() }
    }
    @Test fun completeAndPartialRequestsReturnExactBytes() = withServer { url ->
        request(url) {
            assertEquals(200, it.responseCode)
            assertEquals("audio/mp4", it.contentType)
            assertEquals("bytes", it.getHeaderField("Accept-Ranges"))
            assertEquals("0123456789", it.inputStream.bufferedReader().readText())
        }
        request(url, range = "bytes=3-6") {
            assertEquals(206, it.responseCode)
            assertEquals("bytes 3-6/10", it.getHeaderField("Content-Range"))
            assertEquals(4, it.contentLength)
            assertEquals("3456", it.inputStream.bufferedReader().readText())
        }
        request(url, range = "bytes=-2") {
            assertEquals(206, it.responseCode)
            assertEquals("89", it.inputStream.bufferedReader().readText())
        }
    }
    @Test fun headAndErrorsRespectHttpSemantics() = withServer { url ->
        request(url, method = "HEAD", range = "bytes=1-2") {
            assertEquals(200, it.responseCode)
            assertEquals(10, it.contentLength)
            assertEquals(-1, it.inputStream.read())
        }
        request(url, range = "bytes=10-") {
            assertEquals(416, it.responseCode)
            assertEquals("bytes */10", it.getHeaderField("Content-Range"))
        }
        request(url + "/unknown") { assertEquals(404, it.responseCode) }
        request(url, method = "POST") {
            assertEquals(405, it.responseCode)
            assertEquals("GET, HEAD", it.getHeaderField("Allow"))
        }
    }
    @Test fun streamingHeadDoesNotDownloadAndSeekOpensOnlyRequestedBytes() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        LocalMediaServer(10, InetAddress.getLoopbackAddress(), "audio/mp4", { offset, count ->
            requests += offset to count
            object : java.io.ByteArrayInputStream("0123456789".toByteArray(), offset.toInt(), count.toInt()) {
                override fun close() { closed.set(true) }
            }
        }).use { server ->
            request(server.url, method = "HEAD") {
                assertEquals(200, it.responseCode)
                assertEquals(10, it.contentLength)
            }
            assertTrue(requests.isEmpty())
            request(server.url, range = "bytes=4-7") {
                assertEquals(206, it.responseCode)
                assertEquals("bytes 4-7/10", it.getHeaderField("Content-Range"))
                assertEquals("4567", it.inputStream.bufferedReader().readText())
            }
            assertEquals(listOf(4L to 4L), requests)
        }
        assertTrue(closed.get())
    }

    @Test fun unavailableUpstreamReturnsBadGateway() {
        LocalMediaServer(10, InetAddress.getLoopbackAddress(), "audio/mp4", { _, _ ->
            throw java.io.IOException("upstream unavailable")
        }).use { server ->
            request(server.url) { assertEquals(502, it.responseCode) }
        }
    }

    @Test fun closeReleasesListener() {
        val file = File.createTempFile("sonos-stop", ".m4a")
        try {
            file.writeText("test")
            val server = LocalMediaServer(file, InetAddress.getLoopbackAddress())
            val url = server.url
            server.close()
            try {
                request(url) { it.responseCode }
                fail("Server should have stopped")
            } catch (_: java.io.IOException) { }
        } finally { file.delete() }
    }
}
