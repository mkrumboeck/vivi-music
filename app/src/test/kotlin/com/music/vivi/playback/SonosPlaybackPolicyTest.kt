package com.music.vivi.playback

import at.techtown.sonoscontroller.SonosTransportStatus
import org.junit.Assert.*
import org.junit.Test

class SonosPlaybackPolicyTest {
    @Test fun pauseAndBufferingNeverAdvanceTheQueue() {
        assertFalse(SonosPlaybackPolicy.reachedEnd(SonosTransportStatus.PAUSED,
            SonosTransportStatus.PLAYING, 298_000, 5000, 300_000, true))
        assertFalse(SonosPlaybackPolicy.reachedEnd(SonosTransportStatus.STOPPED,
            SonosTransportStatus.PLAYING, 298_000, 5000, 300_000, false))
        assertFalse(SonosPlaybackPolicy.reachedEnd(SonosTransportStatus.TRANSITIONING,
            SonosTransportStatus.PLAYING, 298_000, 5000, 300_000, true))
    }

    @Test fun onlyAPlayingTrackThatReachesItsDurationCanEndNaturally() {
        assertTrue(SonosPlaybackPolicy.reachedEnd(SonosTransportStatus.STOPPED,
            SonosTransportStatus.PLAYING, 298_000, 2000, 300_000, true))
        assertFalse(SonosPlaybackPolicy.reachedEnd(SonosTransportStatus.STOPPED,
            SonosTransportStatus.PLAYING, 120_000, 2000, 300_000, true))
        assertFalse(SonosPlaybackPolicy.reachedEnd(SonosTransportStatus.STOPPED,
            SonosTransportStatus.STOPPED, 298_000, 2000, 300_000, true))
    }

    @Test fun repeatOneKeepsTheCurrentItemAndShuffleUsesThePlayerProvidedNextIndex() {
        assertEquals(4, SonosPlaybackPolicy.nextIndex(4, 9, true))
        assertEquals(9, SonosPlaybackPolicy.nextIndex(4, 9, false))
        assertEquals(-1, SonosPlaybackPolicy.nextIndex(4, -1, false))
    }

    @Test fun opusNeedsConversionWhileAacAndMp3CanPassThrough() {
        assertTrue(SonosPlaybackPolicy.canPassThrough("audio/mp4; codecs=\"mp4a.40.2\""))
        assertTrue(SonosPlaybackPolicy.canPassThrough("audio/mpeg"))
        assertFalse(SonosPlaybackPolicy.canPassThrough("audio/webm; codecs=\"opus\""))
        assertFalse(SonosPlaybackPolicy.canPassThrough("audio/mp4; codecs=\"opus\""))
    }

    @Test fun sonosVolumeUsesItsOwnBoundedScale() {
        assertEquals(0, SonosPlaybackPolicy.volume(-0.1f))
        assertEquals(50, SonosPlaybackPolicy.volume(0.5f))
        assertEquals(100, SonosPlaybackPolicy.volume(1.5f))
    }

}
