package com.music.vivi.playback

import android.os.Looper
import androidx.media3.common.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class SonosPlayer(
    private val service: MusicService,
    private val handler: SonosConnectionHandler,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    fun refresh() = invalidateState()

    override fun getState(): State {
        val local = service.player
        val snapshot = handler.state.value
        val playlist = (0 until local.mediaItemCount).map { index ->
            val item = local.getMediaItemAt(index)
            MediaItemData.Builder(index)
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(if (index == local.currentMediaItemIndex && snapshot.duration > 0)
                    snapshot.duration * 1_000_000L else C.TIME_UNSET)
                .setIsSeekable(index != local.currentMediaItemIndex || snapshot.canSeek)
                .build()
        }
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).apply {
            if (snapshot.canSeek) addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        }.build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(ImmutableList.copyOf(playlist))
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else local.currentMediaItemIndex.coerceIn(playlist.indices))
            .setPlayWhenReady(handler.wantsPlayback, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when {
                playlist.isEmpty() -> Player.STATE_IDLE
                handler.preparing.value -> Player.STATE_BUFFERING
                handler.ended -> Player.STATE_ENDED
                else -> Player.STATE_READY
            })
            .setContentPositionMs(handler.positionMs)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(handler.durationMs))
            .setRepeatMode(local.repeatMode)
            .setShuffleModeEnabled(local.shuffleModeEnabled)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(100).build())
            .setDeviceVolume(snapshot.volume)
            .setVolume(snapshot.volume / 100f)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) handler.play() else handler.pause()
        return Futures.immediateVoidFuture()
    }
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleStop(): ListenableFuture<*> {
        handler.returnToLocal(resume = false); return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
            handler.skipToNext()
        } else if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
            handler.skipToPrevious()
        } else if (mediaItemIndex != service.player.currentMediaItemIndex) handler.seekToQueueItem(mediaItemIndex, positionMs)
        else handler.seekTo(positionMs)
        return Futures.immediateVoidFuture()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        service.player.repeatMode = repeatMode; refresh(); return Futures.immediateVoidFuture()
    }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        service.player.shuffleModeEnabled = shuffleModeEnabled; refresh(); return Futures.immediateVoidFuture()
    }
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        handler.setVolume(SonosPlaybackPolicy.volume(volume)); return Futures.immediateVoidFuture()
    }
    override fun handleSetDeviceVolume(volume: Int, flags: Int): ListenableFuture<*> {
        handler.setVolume(volume); return Futures.immediateVoidFuture()
    }
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        handler.setVolume(handler.state.value.volume + 2); return Futures.immediateVoidFuture()
    }
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        handler.setVolume(handler.state.value.volume - 2); return Futures.immediateVoidFuture()
    }
}
