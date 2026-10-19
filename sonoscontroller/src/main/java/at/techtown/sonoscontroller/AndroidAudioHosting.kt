package at.techtown.sonoscontroller

import android.content.Context
import java.net.URI
import java.util.Locale

internal class AndroidAudioHosting(
    private val context: Context,
    private val wifi: WifiConnection
) : AudioHosting {
    // Serialized by SessionEngine. Preparing a replacement temporarily holds a second lease.
    private var localLeases = 0
    override suspend fun prepare(source: AudioSource, metadata: SonosMetadata?): HostedAudio {
        if (source is AudioSource.Url) {
            val uri = validateStreamUrl(source.url)
            val mime = audioMime(source.mimeType, uri.path)
            wifi.route()
            return object : HostedAudio {
                override val url = source.url
                override val mimeType = mime
                override fun checkConnection() { wifi.route() }
                override fun close() {}
            }
        }
        var local: PreparedLocalAudio? = null
        var server: LocalMediaServer? = null
        var leased = false
        try {
            val route = wifi.route()
            val prepared = when (source) {
                is AudioSource.Stream -> null
                is AudioSource.LocalFile -> localAudioFile(source.file, source.mimeType)
                is AudioSource.ContentUri -> {
                    if (source.uri.scheme != "content")
                        throw SonosException(SonosErrorCode.INVALID_SOURCE, "Eine content://-URI aus der Android-Dateiauswahl verwenden.")
                    val resolver = context.contentResolver
                    val mime = audioMime(source.mimeType ?: resolver.getType(source.uri), source.uri.lastPathSegment)
                    val input = resolver.openInputStream(source.uri)
                        ?: throw SonosException(SonosErrorCode.INVALID_SOURCE, "Die ausgewählte Datei kann nicht geöffnet werden.")
                    copyContentAudio(input, context.cacheDir, mime)
                }
                else -> error("Unexpected source")
            }
            local = prepared
            if (localLeases == 0) SonosHostingService.start(context, metadata?.title)
            localLeases++; leased = true
            val mime = if (source is AudioSource.Stream) {
                if (source.mimeType == "video/mp4") "video/mp4" else audioMime(source.mimeType, null)
            } else prepared!!.mimeType
            val hosted = if (source is AudioSource.Stream) {
                require(source.length > 0) { "A finite audio stream is required" }
                LocalMediaServer(source.length, route.address, mime, source.openRange)
            } else LocalMediaServer(prepared!!.file, route.address, mime)
            server = hosted
            return object : HostedAudio {
                private var closed = false
                override val url = hosted.url
                override val mimeType = mime
                override fun checkConnection() {
                    if (wifi.route().address != route.address)
                        throw SonosException(SonosErrorCode.NETWORK_CHANGED, "Die WLAN-Adresse hat sich geändert. Wiedergabe neu starten.")
                }
                override fun close() {
                    if (closed) return
                    closed = true
                    try { hosted.close() } finally {
                        prepared?.close()
                        localLeases--
                        if (localLeases == 0) SonosHostingService.stop(context)
                    }
                }
            }
        } catch (error: Exception) {
            server?.close()
            local?.close()
            if (leased) localLeases--
            if (localLeases == 0) SonosHostingService.stop(context)
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is SonosException) throw error
            throw SonosException(SonosErrorCode.INVALID_SOURCE, "Die lokale Audioquelle konnte nicht bereitgestellt werden.", error)
        }
    }
}

internal fun validateStreamUrl(url: String): URI {
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri == null || uri.scheme?.lowercase(Locale.ROOT) !in listOf("http", "https") ||
        uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null)
        throw SonosException(SonosErrorCode.INVALID_SOURCE, "Eine direkte HTTP-/HTTPS-Audio-URL ohne Anmeldedaten angeben.")
    return uri
}
internal fun audioMime(explicit: String?, name: String?): String {
    val mime = explicit?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (!mime.isNullOrEmpty()) {
        if (!Regex("audio/[a-z0-9.+-]+").matches(mime))
            throw SonosException(SonosErrorCode.INVALID_SOURCE, "Eine Audioquelle mit gültigem Audio-MIME-Typ verwenden.")
        return when (mime) { "audio/x-m4a", "audio/m4a" -> "audio/mp4"; else -> mime }
    }
    return when (name?.substringBefore('?')?.substringAfterLast('.')?.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        else -> "audio/mpeg" // For extensionless URLs pass mimeType when the endpoint is not MP3.
    }
}
