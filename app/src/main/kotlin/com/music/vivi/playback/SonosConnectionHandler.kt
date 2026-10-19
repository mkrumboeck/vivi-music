package com.music.vivi.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import at.techtown.sonoscontroller.*
import com.music.vivi.R
import com.music.vivi.extensions.metadata
import com.music.vivi.models.MediaMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

class SonosConnectionHandler internal constructor(
    private val service: MusicService,
    private val scope: CoroutineScope,
) {
    private val controller = SonosController.create(service)
    val state: StateFlow<SonosState> = controller.state
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    val isActive: Boolean get() = _active.value
    private val _routing = MutableStateFlow(false)
    internal val routing: StateFlow<Boolean> = _routing
    val controlsRemote: Boolean get() = _routing.value
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing
    private val _target = MutableStateFlow<SonosDevice?>(null)
    val target: StateFlow<SonosDevice?> = _target
    internal val remotePlayer = SonosPlayer(service, this)
    private val streamResolver = SonosStreamResolver(service)
    private var discoveryJob: Job? = null
    private var transferJob: Job? = null
    private var prefetchJob: Job? = null
    private var volumeJob: Job? = null
    private var returnJob: Job? = null
    internal var isReturning = false
        private set
    private var pendingPlayWhenReady: Boolean? = null
    private var commandsInFlight = 0
    private var generation = 0
    private var loadedId: String? = null
    private var changingQueue = false
    internal var wantsPlayback = false
        private set
    internal var ended = false
        private set
    private var lastStatus = SonosTransportStatus.UNKNOWN
    private var lastPlayingPosition = 0L
    private var lastPlayingTime = 0L
    private var remotePosition = 0L
    private var lastPositionTime = 0L

    val positionMs: Long get() {
        val elapsed = if (state.value.playing && isActive && !_preparing.value)
            SystemClock.elapsedRealtime() - lastPositionTime else 0L
        return (remotePosition + elapsed).coerceIn(0, durationMs.coerceAtLeast(remotePosition))
    }
    val durationMs: Long get() = state.value.duration.takeIf { it > 0 }?.times(1000L)
        ?: service.player.currentMediaItem?.metadata?.duration?.coerceAtLeast(0)?.times(1000L) ?: 0L

    init {
        scope.launch {
            state.collect { current ->
                if (!_active.value || _preparing.value) return@collect
                val now = SystemClock.elapsedRealtime()
                if (remotePosition != current.position * 1000L || lastStatus != current.transportStatus) {
                    remotePosition = current.position * 1000L
                    lastPositionTime = now
                }
                if (!current.connected && current.error != null) {
                    fail(service.getString(R.string.sonos_connection_lost))
                    return@collect
                }
                if (!current.session || (!current.ownTrack && current.transportStatus != SonosTransportStatus.STOPPED)) {
                    fail(service.getString(R.string.sonos_playback_replaced))
                    return@collect
                }
                if (SonosPlaybackPolicy.reachedEnd(current.transportStatus, lastStatus,
                        lastPlayingPosition, now - lastPlayingTime, durationMs, wantsPlayback)) {
                    onNaturalEnd()
                } else if (commandsInFlight == 0 && current.transportStatus == SonosTransportStatus.STOPPED) wantsPlayback = false
                if (commandsInFlight == 0 && current.transportStatus == SonosTransportStatus.PAUSED) wantsPlayback = false
                if (commandsInFlight == 0 && current.transportStatus == SonosTransportStatus.PLAYING) wantsPlayback = true
                if (current.transportStatus == SonosTransportStatus.PLAYING &&
                    (lastPlayingPosition != current.position * 1000L || lastStatus != SonosTransportStatus.PLAYING)) {
                    lastPlayingPosition = current.position * 1000L
                    lastPlayingTime = now
                }
                if (current.transportStatus != SonosTransportStatus.TRANSITIONING &&
                    current.transportStatus != SonosTransportStatus.UNKNOWN) lastStatus = current.transportStatus
                remotePlayer.refresh()
            }
        }
        // MusicService is bound when the app starts. Permission is requested by the device menu.
        if (android.os.Build.VERSION.SDK_INT < 37 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                service, android.Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            discover()
        }
    }

    fun discover() {
        if (discoveryJob?.isActive == true || state.value.scanning) return
        discoveryJob = scope.launch {
            try { controller.discover() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Timber.tag("ViviSonos").e(failure, "Sonos discovery failed")
            }
        }
    }
    fun connect(device: SonosDevice) {
        val metadata = service.player.currentMediaItem?.metadata
        if (metadata == null) {
            _target.value = device
            return
        }
        transfer(device, metadata, null, null)
    }
    private fun transfer(
        device: SonosDevice, metadata: MediaMetadata, position: Long?, playing: Boolean?,
    ) {
        val previousReturn = returnJob
        val previous = transferJob
        previous?.cancel()
        val request = ++generation
        _target.value = device
        _preparing.value = true
        pendingPlayWhenReady = playing ?: if (isActive) wantsPlayback else null
        ended = false
        transferJob = scope.launch {
            var stage = "preparing audio"
            try {
                previous?.join()
                previousReturn?.join()
                isReturning = false
                prefetchJob?.cancelAndJoin()
                prefetchJob = null
                val source = streamResolver.resolve(metadata, loadedId)
                ensureActive()
                if (generation != request || service.player.currentMediaItem?.mediaId != metadata.id) return@launch
                stage = "switching media session"
                val casting = service.castConnectionHandler?.takeIf { it.isCasting.value }
                val startPosition = position ?: if (_active.value) positionMs
                    else casting?.castPosition?.value ?: service.player.currentPosition
                val startPlaying = pendingPlayWhenReady ?: casting?.castIsPlaying?.value ?: service.player.playWhenReady
                casting?.let { cast ->
                    cast.disconnect()
                    withTimeout(10_000) { cast.isCasting.first { !it } }
                }
                service.cancelCrossfadeIfActive()
                _routing.value = true
                service.player.pause()
                wantsPlayback = startPlaying
                remotePosition = startPosition
                lastPositionTime = SystemClock.elapsedRealtime()
                service.useSonosSession(true)
                stage = "loading and positioning Sonos"
                controller.play(device, source,
                    SonosMetadata(
                        metadata.title,
                        metadata.artists.joinToString(", ") { it.name },
                        metadata.thumbnailUrl,
                        metadata.duration,
                    ),
                    (startPosition / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), startPlaying)
                ensureActive()
                if (generation != request) return@launch
                val latestPlaying = pendingPlayWhenReady ?: startPlaying
                if (latestPlaying != startPlaying) {
                    if (latestPlaying) controller.resume(device) else controller.pause(device)
                }
                wantsPlayback = latestPlaying
                loadedId = metadata.id
                _active.value = true
                lastStatus = state.value.transportStatus
                lastPlayingPosition = state.value.position * 1000L
                lastPlayingTime = SystemClock.elapsedRealtime()
                service.useSonosSession(true)
                streamResolver.retainOnly(setOf(metadata.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.tag("ViviSonos").e(failure, "Transfer failed at %s", stage)
                if (request == generation) fail(service.getString(R.string.sonos_start_failed, failure.message.orEmpty()))
            } finally {
                if (request == generation) {
                    _preparing.value = false
                    remotePlayer.refresh()
                    if (_active.value) prefetchNext()
                }
            }
        }
    }

    internal fun onLocalMediaChanged(metadata: MediaMetadata?) {
        if (changingQueue) return
        if (!isActive && _target.value == null) return
        if (metadata == null) { returnToLocal(resume = false); return }
        _target.value?.let { transfer(it, metadata, 0, if (isActive) wantsPlayback else null) }
    }
    private fun command(action: suspend (SonosDevice) -> Unit) {
        if (isReturning) return
        val device = _target.value ?: return
        scope.launch {
            commandsInFlight++
            try { action(device) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure.message ?: service.getString(R.string.sonos_connection_lost)) }
            finally { commandsInFlight--; remotePlayer.refresh() }
        }
    }
    fun play() {
        wantsPlayback = true
        pendingPlayWhenReady = true
        if (_preparing.value) return
        remotePlayer.refresh()
        if (ended) {
            _target.value?.let { device ->
                service.player.currentMediaItem?.metadata?.let { transfer(device, it, 0, true) }
            }
        } else command { controller.resume(it) }
    }
    fun pause() {
        wantsPlayback = false
        pendingPlayWhenReady = false
        if (isActive && state.value.playing) command { controller.pause(it) }
        remotePlayer.refresh()
    }
    fun seekTo(positionMs: Long) {
        if (_preparing.value) return
        command { controller.seekTo(it, (positionMs.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    }
    fun setVolume(volume: Int) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(120)
            command { controller.setVolume(it, volume.coerceIn(0, 100)) }
        }
    }

    fun seekToQueueItem(index: Int, positionMs: Long = 0) {
        if (index !in 0 until service.player.mediaItemCount) return
        val device = _target.value ?: return
        changingQueue = true
        try { service.player.pause(); service.player.seekTo(index, positionMs.coerceAtLeast(0)) }
        finally { changingQueue = false }
        service.player.currentMediaItem?.metadata?.let { transfer(device, it, positionMs.coerceAtLeast(0), true) }
    }
    fun skipToNext() {
        val next = service.player.nextMediaItemIndex
        if (next != C.INDEX_UNSET) seekToQueueItem(next)
    }
    fun skipToPrevious() {
        if (positionMs > 3000 || !service.player.hasPreviousMediaItem()) seekTo(0)
        else seekToQueueItem(service.player.previousMediaItemIndex)
    }
    private fun onNaturalEnd() {
        ended = true
        if (service.sleepTimer.pauseWhenSongEnd) {
            service.sleepTimer.notifySongTransition()
            wantsPlayback = false
            return
        }
        val local = service.player
        val next = SonosPlaybackPolicy.nextIndex(local.currentMediaItemIndex, local.nextMediaItemIndex,
            local.repeatMode == Player.REPEAT_MODE_ONE)
        if (next != C.INDEX_UNSET) seekToQueueItem(next)
        else wantsPlayback = false
    }
    internal fun onLocalQueueChanged() { remotePlayer.refresh(); if (isActive) prefetchNext() }
    private fun prefetchNext() {
        // Do not start a second resolution while the current source is being handed over.
        if (_preparing.value || !isActive || isReturning) return
        prefetchJob?.cancel()
        val local = service.player
        val index = local.nextMediaItemIndex
        if (index == C.INDEX_UNSET || index == local.currentMediaItemIndex) return
        val metadata = local.getMediaItemAt(index).metadata ?: return
        prefetchJob = scope.launch {
            try { streamResolver.resolve(metadata, loadedId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Timber.d(failure, "Sonos next-track preparation deferred") }
        }
    }
    fun returnToLocal(resume: Boolean? = null, preferredDeviceId: Int? = null) {
        val position = if (isActive) positionMs else service.player.currentPosition
        val shouldResume = resume ?: if (isActive) wantsPlayback else service.player.playWhenReady
        val request = ++generation
        isReturning = true
        val previousReturn = returnJob
        val pending = transferJob
        val preload = prefetchJob
        pending?.cancel(); preload?.cancel(); volumeJob?.cancel()
        transferJob = null; prefetchJob = null
        _routing.value = false
        _active.value = false
        _target.value = null
        _preparing.value = false
        wantsPlayback = false
        service.player.pause()
        returnJob = scope.launch {
            previousReturn?.join()
            pending?.join(); preload?.join()
            runCatching { controller.endSession() }
            streamResolver.clear()
            loadedId = null
            service.useSonosSession(false)
            service.setPreferredAudioDevice(preferredDeviceId)
            if (service.player.currentMediaItem != null) service.player.seekTo(position.coerceAtLeast(0))
            if (request == generation) {
                isReturning = false
                service.player.playWhenReady = shouldResume
            }
        }
    }
    internal suspend fun awaitLocalForCast() {
        if (isActive || _target.value != null || isReturning) returnToLocal(resume = false)
        returnJob?.join()
    }
    private fun fail(message: String) {
        Timber.tag("ViviSonos").e(message)
        returnToLocal(resume = false)
    }
    fun release() {
        discoveryJob?.cancel()
        ++generation
        isReturning = true
        val previousReturn = returnJob
        val pending = transferJob
        val preload = prefetchJob
        pending?.cancel(); preload?.cancel(); volumeJob?.cancel()
        _routing.value = false
        _active.value = false; _target.value = null
        CoroutineScope(Dispatchers.IO).launch {
            previousReturn?.join()
            pending?.join(); preload?.join()
            controller.close()
            runCatching { controller.awaitClosed() }
            streamResolver.clear()
        }
    }
}
