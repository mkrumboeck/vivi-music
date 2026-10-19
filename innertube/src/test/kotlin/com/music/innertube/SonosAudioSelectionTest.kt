package com.music.innertube

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

class SonosAudioSelectionTest {
    private fun audio(format: MediaFormat, bitrate: Int, url: Boolean = true,
                      delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP): AudioStream =
        AudioStream.Builder().setId("test-$bitrate").setContent("https://example.test/audio", url)
            .setMediaFormat(format).setAverageBitrate(bitrate).setDeliveryMethod(delivery).build()

    @Test fun m4aIsPreferredOverHigherBitrateWebm() {
        val m4a = audio(MediaFormat.M4A, 128)
        assertSame(m4a, NewPipeExtractor.selectSonosAudio(listOf(audio(MediaFormat.WEBMA, 256), m4a)))
    }

    @Test fun webmRemainsAvailableWhenNoM4aExistsLikeInSonosMusic() {
        val webm = audio(MediaFormat.WEBMA, 128)
        assertSame(webm, NewPipeExtractor.selectSonosAudio(listOf(webm)))
    }

    @Test fun nonUrlSourcesAreNotPassedToTheHttpProxy() {
        assertNull(NewPipeExtractor.selectSonosAudio(listOf(audio(MediaFormat.M4A, 128, url = false))))
    }

    @Test fun dashAudioCanBeServedThroughTheRangeProxy() {
        val dash = audio(MediaFormat.M4A, 128, delivery = DeliveryMethod.DASH)
        assertSame(dash, NewPipeExtractor.selectSonosAudio(listOf(dash)))
    }
}
