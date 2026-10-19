package at.techtown.sonoscontroller

import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
    companion object {
        fun parse(header: String, size: Long): ByteRange? {
            if (size <= 0 || !header.startsWith("bytes=") || header.contains(',')) return null
            val pair = header.removePrefix("bytes=").split('-')
            if (pair.size != 2) return null
            if (pair[0].isBlank()) {
                val suffix = pair[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
            }
            val start = pair[0].toLongOrNull()?.takeIf { it in 0 until size } ?: return null
            val end = if (pair[1].isEmpty()) size - 1 else pair[1].toLongOrNull() ?: return null
            if (end < start) return null
            return ByteRange(start, end.coerceAtMost(size - 1))
        }
    }
}

internal interface AudioServer : Closeable {
    val url: String
}

/** A single-file, token-scoped HTTP server. No filesystem paths are accepted from clients. */
internal class LocalMediaServer(
    private val size: Long,
    address: InetAddress,
    private val mimeType: String,
    private val openRange: (Long, Long) -> java.io.InputStream,
) : AudioServer {
    constructor(file: File, address: InetAddress, mimeType: String = "audio/mp4") :
        this(file.length(), address, mimeType, { offset, _ ->
            java.io.FileInputStream(file).apply { channel.position(offset) }
        })
    private val sources = ConcurrentHashMap.newKeySet<java.io.InputStream>()
    private val listener = ServerSocket(0, 8, address)
    private val extension = when (mimeType) {
        "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"; "audio/flac" -> "flac"
        "audio/webm" -> "webm"; "video/mp4" -> "mp4"
        "audio/wav" -> "wav"; "audio/aac" -> "aac"; "audio/ogg" -> "ogg"; else -> "audio"
    }
    private val path = "/${UUID.randomUUID()}/stream.$extension"
    override val url = "http://${address.hostAddress}:${listener.localPort}$path"
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val acceptor = Executors.newSingleThreadExecutor()
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    init {
        acceptor.execute {
            while (!listener.isClosed) {
                try {
                    val socket = listener.accept()
                    sockets.add(socket)
                    try { workers.execute { serve(socket) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
                } catch (_: java.io.IOException) { break }
            }
        }
    }
    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 8000
                val input = socket.getInputStream().buffered()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (bytes.size() < 8192) {
                        val b = input.read()
                        if (b == -1 || b == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                        bytes.write(b)
                    }
                    throw java.io.IOException("HTTP header too long")
                }
                val request = line().split(' ')
                val headers = mutableMapOf<String, String>()
                var count = 0
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (++count > 50) throw java.io.IOException("Too many headers")
                    val colon = header.indexOf(':')
                    if (colon > 0) headers[header.substring(0, colon).lowercase(java.util.Locale.ROOT)] = header.substring(colon + 1).trim()
                }
                val output = socket.getOutputStream().buffered()
                fun respond(code: String, extra: String = "", length: Long = 0) {
                    output.write(("HTTP/1.1 $code\r\nContent-Length: $length\r\n" +
                        "Accept-Ranges: bytes\r\nContent-Type: $mimeType\r\nConnection: close\r\n" + extra + "\r\n").toByteArray(Charsets.US_ASCII))
                }
                if (request.size != 3) respond("400 Bad Request")
                else if (request[1] != path) respond("404 Not Found")
                else if (request[0] !in listOf("GET", "HEAD")) respond("405 Method Not Allowed", "Allow: GET, HEAD\r\n")
                else {
                    // RFC 9110: Range applies to GET, HEAD describes the complete representation.
                    val rangeHeader = if (request[0] == "GET") headers["range"] else null
                    val range = rangeHeader?.let { ByteRange.parse(it, size) }
                    if (rangeHeader != null && range == null) {
                        respond("416 Range Not Satisfiable", "Content-Range: bytes */$size\r\n")
                    } else {
                        val selected = range ?: ByteRange(0, size - 1)
                        val source = if (request[0] == "GET") {
                            try { openRange(selected.start, selected.length) }
                            catch (_: Exception) {
                                respond("502 Bad Gateway")
                                output.flush()
                                return
                            }
                        } else null
                        source?.let { sources.add(it) }
                        try {
                            respond(if (range != null) "206 Partial Content" else "200 OK",
                                if (range != null) "Content-Range: bytes ${selected.start}-${selected.end}/$size\r\n" else "",
                                selected.length)
                            output.flush()
                            if (source != null) source.use {
                                var remaining = selected.length
                                val buffer = ByteArray(32 * 1024)
                                while (remaining > 0) {
                                    val read = it.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read < 0) throw java.io.EOFException("Incomplete upstream audio")
                                    output.write(buffer, 0, read)
                                    output.flush()
                                    remaining -= read
                                }
                            }
                        } finally {
                            source?.let { sources.remove(it); runCatching { it.close() } }
                        }
                    }
                }
                output.flush()
            }
        } catch (_: java.io.IOException) {
            // Sonos commonly closes a request early before starting another range request.
        } finally { sockets.remove(socket) }
    }
    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        sources.forEach { runCatching { it.close() } }
        workers.shutdownNow(); acceptor.shutdownNow()
    }
}
