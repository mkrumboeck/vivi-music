package com.music.vivi.playback

import at.techtown.sonoscontroller.SonosTransportStatus

internal object SonosPlaybackPolicy {
    fun canPassThrough(mime: String): Boolean {
        val type = mime.substringBefore(';').trim().lowercase()
        return type == "audio/mpeg" || (type == "audio/mp4" && !mime.contains("opus", ignoreCase = true))
    }

    fun nextIndex(current: Int, next: Int, repeatOne: Boolean): Int =
        if (repeatOne) current else next

    fun reachedEnd(
        status: SonosTransportStatus, previousStatus: SonosTransportStatus,
        lastPositionMs: Long, elapsedMs: Long, durationMs: Long, wantedPlaying: Boolean,
    ): Boolean = wantedPlaying && status == SonosTransportStatus.STOPPED &&
        previousStatus == SonosTransportStatus.PLAYING && durationMs > 0 &&
        lastPositionMs + elapsedMs >= durationMs - 2000

    fun volume(value: Float): Int = (value.coerceIn(0f, 1f) * 100).toInt()
}
