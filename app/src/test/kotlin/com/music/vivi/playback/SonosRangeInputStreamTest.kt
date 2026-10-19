package com.music.vivi.playback

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SonosRangeInputStreamTest {
    @Test fun streamsOnDemandAndReopensOnlyTheRequestedBoundedRanges() {
        val bytes = ByteArray(100) { it.toByte() }
        val requests = mutableListOf<Pair<Long, Long>>()
        val stream = SonosRangeInputStream(7, 25, 10) { offset, length ->
            requests += offset to length
            ByteArrayInputStream(bytes, offset.toInt(), length.toInt())
        }
        assertTrue(requests.isEmpty())
        assertEquals(7, stream.read())
        assertEquals(listOf(7L to 10L), requests)
        val rest = stream.readBytes()
        assertArrayEquals(bytes.copyOfRange(8, 32), rest)
        assertEquals(listOf(7L to 10L, 17L to 10L, 27L to 5L), requests)
        stream.close()
    }

    @Test fun stoppingSonosClosesTheCurrentUpstreamWithoutDownloadingTheRest() {
        var closed = false
        var requests = 0
        val stream = SonosRangeInputStream(0, 1000, 100) { _, length ->
            requests++
            object : ByteArrayInputStream(ByteArray(length.toInt())) {
                override fun close() { closed = true }
            }
        }
        stream.read()
        stream.close()
        assertTrue(closed)
        assertEquals(1, requests)
        try { stream.read(); fail("Closed stream must reject reads") } catch (_: IOException) {}
    }

    @Test(expected = EOFException::class) fun aTruncatedUpstreamFailsInsteadOfSkippingBytes() {
        SonosRangeInputStream(0, 10, 10) { _, _ ->
            ByteArrayInputStream(byteArrayOf(1))
        }.use { it.readBytes() }
    }
}
