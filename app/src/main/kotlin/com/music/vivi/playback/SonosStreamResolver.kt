package com.music.vivi.playback

import at.techtown.sonoscontroller.AudioSource
import com.music.vivi.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps only resolved stream descriptors; audio is fetched on Sonos HTTP requests. */
internal class SonosStreamResolver(private val service: MusicService) {
    private val mutex = Mutex()
    private data class Resolved(val source: AudioSource.Stream, val atMs: Long)
    private val ready = mutableMapOf<String, Resolved>()

    suspend fun resolve(metadata: MediaMetadata, protectedId: String?): AudioSource.Stream =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ready[metadata.id]?.takeIf {
                    android.os.SystemClock.elapsedRealtime() - it.atMs < 120_000
                }?.let { return@withLock it.source }
                retain(setOfNotNull(metadata.id, protectedId))
                require(metadata.duration > 0) { service.getString(com.music.vivi.R.string.sonos_finite_tracks_only) }
                service.resolveSonosStream(metadata.id).also {
                    ready[metadata.id] = Resolved(it, android.os.SystemClock.elapsedRealtime())
                }
            }
        }

    suspend fun retainOnly(ids: Set<String>) = mutex.withLock { retain(ids) }
    private fun retain(ids: Set<String>) { ready.keys.retainAll(ids) }
    suspend fun clear() = retainOnly(emptySet())
}
