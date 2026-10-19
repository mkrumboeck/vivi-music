package com.music.vivi.playback

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Reads upstream in bounded requests while Sonos receives one continuous response. */
internal class SonosRangeInputStream(
    private var offset: Long,
    private var remaining: Long,
    private val chunkSize: Long,
    private val openRange: (Long, Long) -> InputStream,
) : InputStream() {
    @Volatile private var closed = false
    @Volatile private var input: InputStream? = null
    private var chunkRemaining = 0L

    init { require(offset >= 0 && remaining >= 0 && chunkSize > 0) }

    fun prepare() {
        if (closed) throw IOException("Sonos stream closed")
        if (remaining > 0 && input == null) {
            chunkRemaining = minOf(chunkSize, remaining)
            val opened = openRange(offset, chunkRemaining)
            input = opened
            if (closed) { opened.close(); throw IOException("Sonos stream closed") }
        }
    }

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, start: Int, count: Int): Int {
        if (start < 0 || count < 0 || start > buffer.size - count) throw IndexOutOfBoundsException()
        if (closed) throw IOException("Sonos stream closed")
        if (count == 0) return 0
        if (remaining == 0L) return -1
        prepare()
        val current = input ?: throw IOException("Sonos stream closed")
        val read = current.read(buffer, start, minOf(count.toLong(), chunkRemaining).toInt())
        if (read < 0) throw EOFException("Incomplete upstream audio range")
        offset += read
        remaining -= read
        chunkRemaining -= read
        if (chunkRemaining == 0L) { input?.close(); input = null }
        return read
    }

    override fun close() {
        closed = true
        input?.close()
        input = null
    }
}
