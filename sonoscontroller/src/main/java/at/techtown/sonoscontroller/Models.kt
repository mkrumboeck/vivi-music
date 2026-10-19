package at.techtown.sonoscontroller

import android.net.Uri
import java.io.File

/** Sonos must be able to retrieve Url directly, without app-specific HTTP headers. */
sealed class AudioSource {
    data class Url @JvmOverloads constructor(val url: String, val mimeType: String? = null) : AudioSource()
    data class LocalFile @JvmOverloads constructor(val file: File, val mimeType: String? = null) : AudioSource()
    /** Blocking range reader; the host serves it over a local URL with a foreground service. */
    class Stream(
        val length: Long,
        val mimeType: String,
        val openRange: (offset: Long, length: Long) -> java.io.InputStream,
    ) : AudioSource()
    data class ContentUri @JvmOverloads constructor(val uri: Uri, val mimeType: String? = null) : AudioSource()
}

data class SonosMetadata @JvmOverloads constructor(
    val title: String? = null, val artist: String? = null, val coverUrl: String? = null,
    val durationSeconds: Int? = null
)

enum class SonosErrorCode {
    NO_WIFI, DEVICE_UNREACHABLE, INVALID_SOURCE, INVALID_ADDRESS, NO_SESSION,
    FOREIGN_PLAYBACK, SEEK_UNSUPPORTED, PLAYBACK_REJECTED, SERVICE_UNAVAILABLE,
    NETWORK_CHANGED, CLOSED, PROTOCOL_ERROR
}
class SonosException @JvmOverloads constructor(
    val code: SonosErrorCode, message: String, cause: Throwable? = null
) : Exception(message, cause) {
    internal constructor(message: String) : this(SonosErrorCode.PROTOCOL_ERROR, message)
}
data class SonosError(val code: SonosErrorCode, val message: String)
enum class SonosTransportStatus { UNKNOWN, STOPPED, PLAYING, PAUSED, TRANSITIONING }

data class SonosState(
    val devices: List<SonosDevice> = emptyList(),
    val selected: SonosDevice? = null,
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val session: Boolean = false,
    val connected: Boolean = false,
    val playing: Boolean = false,
    val transportStatus: SonosTransportStatus = SonosTransportStatus.UNKNOWN,
    val ownTrack: Boolean = false,
    val position: Int = 0,
    val duration: Int = 0,
    val volume: Int = 0,
    val error: SonosError? = null
) {
    val canSeek: Boolean get() = ownTrack && duration > 0
}

internal fun asSonosException(error: Exception): SonosException = when (error) {
    is SonosException -> error
    is java.io.IOException -> SonosException(SonosErrorCode.DEVICE_UNREACHABLE,
        "Sonos ist nicht erreichbar. WLAN und Geräteverbindung prüfen.", error)
    else -> SonosException(SonosErrorCode.PROTOCOL_ERROR, error.message ?: "Sonos-Aktion fehlgeschlagen.", error)
}
