package at.techtown.sonoscontroller

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SessionEngineTest {
    private val room = SonosDevice("room", "Küche", "192.168.0.20")
    private val other = SonosDevice("other", "Büro", "192.168.0.21")
    private val coordinator = SonosDevice("coordinator", "Gruppe", "192.168.0.22")
    private class FakeClient : SonosControl {
        var transport = TransportState(false, 0, 30, "")
        var loudness = 50
        var loads = 0
        var starts = 0
        var stops = 0
        var pauses = 0
        var lastSeek: Int? = null
        var lastVolumeDevice: SonosDevice? = null
        var lastMetadata: SonosMetadata? = null
        var lastMime = ""
        var failLoad = false
        var failPlay = false
        var failStop = false
        var topology: ((SonosDevice) -> SonosDevice) = { it }
        override fun device(host: String) = SonosDevice("room", "Küche", host)
        override fun coordinator(device: SonosDevice) = topology(device)
        override fun state(device: SonosDevice) = transport
        override fun volume(device: SonosDevice) = loudness
        override fun setVolume(device: SonosDevice, volume: Int) { lastVolumeDevice = device; loudness = volume }
        override fun load(device: SonosDevice, uri: String, metadata: SonosMetadata?, mimeType: String) {
            loads++; lastMetadata = metadata; lastMime = mimeType
            transport = transport.copy(uri = uri, position = 0)
            if (failLoad) throw IOException("Lost load response")
        }
        override fun play(device: SonosDevice) {
            starts++
            if (failPlay) throw IOException("Play failed")
            transport = transport.copy(playing = true)
        }
        override fun pause(device: SonosDevice) { pauses++; transport = transport.copy(playing = false) }
        override fun stop(device: SonosDevice) {
            stops++
            if (failStop) throw IOException("Stop failed")
            transport = transport.copy(playing = false, uri = "")
        }
        override fun seek(device: SonosDevice, seconds: Int) {
            lastSeek = seconds; transport = transport.copy(position = seconds)
        }
    }
    private class FakeAudio(override val url: String) : HostedAudio {
        override val mimeType = "audio/flac"
        var closes = 0
        var changed = false
        override fun checkConnection() {
            if (changed) throw SonosException(SonosErrorCode.NETWORK_CHANGED, "Changed")
        }
        override fun close() { closes++ }
    }
    private class FakeHosting : AudioHosting {
        val audio = mutableListOf<FakeAudio>()
        var fail = false
        override suspend fun prepare(source: AudioSource, metadata: SonosMetadata?): HostedAudio {
            if (fail) throw SonosException(SonosErrorCode.INVALID_SOURCE, "Bad source")
            return FakeAudio("http://phone/${audio.size}/audio").also { audio.add(it) }
        }
    }
    private inner class Fixture {
        val client = FakeClient()
        val hosting = FakeHosting()
        val engine = SessionEngine(client, DeviceDiscovery { listOf(room, other) }, hosting,
            Dispatchers.Unconfined, pollIntervalMillis = 0)
        suspend fun play(device: SonosDevice = room) =
            engine.play(device, AudioSource.Url("http://example.test/song.flac"), SonosMetadata("Titel", "Interpret"))
    }
    private suspend fun expect(code: SonosErrorCode, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (error: SonosException) { assertEquals(code, error.code) }
    }
    @Test fun discoversAndConnectsWithoutStartingPlayback() = runBlocking {
        val f = Fixture()
        assertEquals(listOf(room, other), f.engine.discover())
        assertEquals(room, f.engine.connect(room.host))
        assertTrue(f.engine.state.value.connected)
        assertFalse(f.engine.state.value.session)
        assertEquals(0, f.client.loads)
        f.engine.shutdown()
    }
    @Test fun refreshKeepsLastDevicesVisibleUntilSuccessAndAlsoOnFailure() = runBlocking {
        var attempt = 0
        lateinit var engine: SessionEngine
        engine = SessionEngine(FakeClient(), DeviceDiscovery {
            attempt++
            if (attempt > 1) {
                assertTrue(engine.state.value.scanning)
                assertEquals(if (attempt == 2) listOf(room) else listOf(other), engine.state.value.devices)
            }
            when (attempt) {
                1 -> listOf(room)
                2 -> listOf(other)
                else -> throw IOException("Wi-Fi unavailable")
            }
        }, FakeHosting(), Dispatchers.Unconfined, pollIntervalMillis = 0)
        try {
            engine.discover()
            engine.discover()
            assertEquals(listOf(other), engine.state.value.devices)
            expect(SonosErrorCode.DEVICE_UNREACHABLE) { engine.discover() }
            assertEquals(listOf(other), engine.state.value.devices)
            assertFalse(engine.state.value.scanning)
        } finally { engine.shutdown() }
    }

    @Test fun invalidAddressReturnsTypedError() = runBlocking {
        val f = Fixture()
        expect(SonosErrorCode.INVALID_ADDRESS) { f.engine.connect("999.0.0.1") }
        expect(SonosErrorCode.INVALID_ADDRESS) { f.engine.connect("http://speaker") }
        assertEquals(SonosErrorCode.INVALID_ADDRESS, f.engine.state.value.error?.code)
        f.engine.shutdown()
    }
    @Test fun startsPreparedSourceWithMetadata() = runBlocking {
        val f = Fixture(); f.play()
        assertEquals(1, f.client.loads)
        assertEquals(SonosMetadata("Titel", "Interpret"), f.client.lastMetadata)
        assertEquals("audio/flac", f.client.lastMime)
        assertTrue(f.engine.state.value.ownTrack)
        assertTrue(f.engine.state.value.canSeek)
        f.engine.shutdown()
    }
    @Test fun pauseAndResumeDoNotReload() = runBlocking {
        val f = Fixture(); f.play()
        f.engine.pause(room); assertFalse(f.client.transport.playing)
        f.engine.resume(room); assertTrue(f.client.transport.playing)
        assertEquals(1, f.client.loads)
        assertEquals(2, f.client.starts)
        f.engine.shutdown()
    }
    @Test fun foreignPlaybackIsProtectedByEveryTransportAction() = runBlocking {
        val f = Fixture(); f.play()
        f.client.transport = f.client.transport.copy(uri = "http://other/song")
        expect(SonosErrorCode.FOREIGN_PLAYBACK) { f.engine.pause(room) }
        expect(SonosErrorCode.FOREIGN_PLAYBACK) { f.engine.resume(room) }
        expect(SonosErrorCode.FOREIGN_PLAYBACK) { f.engine.seekTo(room, 5) }
        expect(SonosErrorCode.FOREIGN_PLAYBACK) { f.engine.seekBy(room, 5) }
        f.engine.endSession()
        assertEquals(0, f.client.pauses); assertEquals(0, f.client.stops)
        assertEquals(1, f.hosting.audio.single().closes)
        f.engine.shutdown()
    }
    @Test fun wrongRoomCannotControlActiveSession() = runBlocking {
        val f = Fixture(); f.play()
        expect(SonosErrorCode.NO_SESSION) { f.engine.pause(other) }
        assertEquals(0, f.client.pauses)
        f.engine.shutdown()
    }
    @Test fun seekUsesCurrentPositionAndClampsBoundaries() = runBlocking {
        val f = Fixture(); f.play()
        f.client.transport = f.client.transport.copy(position = 25)
        f.engine.seekBy(room, 10); assertEquals(29, f.client.lastSeek)
        f.engine.seekBy(room, Int.MIN_VALUE); assertEquals(0, f.client.lastSeek)
        f.engine.seekTo(room, Int.MAX_VALUE); assertEquals(29, f.client.lastSeek)
        f.engine.seekTo(room, -1); assertEquals(0, f.client.lastSeek)
        f.engine.shutdown()
    }
    @Test fun liveStreamRejectsSeek() = runBlocking {
        val f = Fixture(); f.play()
        f.client.transport = f.client.transport.copy(duration = 0)
        expect(SonosErrorCode.SEEK_UNSUPPORTED) { f.engine.seekBy(room, 10) }
        assertNull(f.client.lastSeek)
        f.engine.shutdown()
    }
    @Test fun volumeUsesActualValueAndSelectedRoomWithinGroup() = runBlocking {
        val f = Fixture()
        f.client.topology = { coordinator }
        f.engine.select(room)
        f.engine.setVolume(room, 150); assertEquals(100, f.client.loudness)
        f.client.loudness = 1 // External controller changed the level.
        f.engine.changeVolume(room, -2); assertEquals(0, f.client.loudness)
        f.engine.changeVolume(room, Int.MAX_VALUE); assertEquals(100, f.client.loudness)
        assertEquals(room, f.client.lastVolumeDevice)
        f.engine.shutdown()
    }
    @Test fun replacingSessionStopsAndReleasesPreviousSource() = runBlocking {
        val f = Fixture(); f.play(); f.play(other)
        assertEquals(1, f.client.stops)
        assertEquals(1, f.hosting.audio[0].closes)
        assertEquals(0, f.hosting.audio[1].closes)
        assertEquals(other, f.engine.state.value.selected)
        f.engine.shutdown()
        assertEquals(1, f.hosting.audio[1].closes)
    }
    @Test fun badReplacementDoesNotInterruptCurrentPlayback() = runBlocking {
        val f = Fixture(); f.play(); f.hosting.fail = true
        expect(SonosErrorCode.INVALID_SOURCE) { f.play(other) }
        assertEquals(0, f.client.stops)
        assertTrue(f.engine.state.value.ownTrack)
        assertEquals(0, f.hosting.audio[0].closes)
        f.engine.shutdown()
    }
    @Test fun failedLoadCleansUpEvenIfSonosReceivedTheUrl() = runBlocking {
        val f = Fixture(); f.client.failLoad = true
        expect(SonosErrorCode.DEVICE_UNREACHABLE) { f.play() }
        assertEquals(1, f.client.stops)
        assertEquals(1, f.hosting.audio.single().closes)
        assertFalse(f.engine.state.value.session)
        f.engine.shutdown()
    }
    @Test fun failedPlayReleasesPreparedAudio() = runBlocking {
        val f = Fixture(); f.client.failPlay = true
        expect(SonosErrorCode.DEVICE_UNREACHABLE) { f.play() }
        assertEquals(1, f.hosting.audio.single().closes)
        assertFalse(f.engine.state.value.session)
        f.engine.shutdown()
    }
    @Test fun endingOfflineStillReleasesResources() = runBlocking {
        val f = Fixture(); f.play(); f.client.failStop = true
        expect(SonosErrorCode.DEVICE_UNREACHABLE) { f.engine.endSession() }
        assertEquals(1, f.hosting.audio.single().closes)
        assertFalse(f.engine.state.value.session)
        f.engine.shutdown()
    }
    @Test fun shutdownIsIdempotentAndRejectsFurtherCommands() = runBlocking {
        val f = Fixture(); f.play()
        f.engine.shutdown(); f.engine.shutdown()
        assertEquals(1, f.hosting.audio.single().closes)
        expect(SonosErrorCode.CLOSED) { f.engine.discover() }
    }
    @Test fun changedNetworkDoesNotSendTransportCommands() = runBlocking {
        val f = Fixture(); f.play(); f.hosting.audio.single().changed = true
        expect(SonosErrorCode.NETWORK_CHANGED) { f.engine.pause(room) }
        assertEquals(0, f.client.pauses)
        f.engine.shutdown()
    }
    @Test fun mimeTypesSupportLocalFormatsAndExplicitExtensionlessStreams() {
        assertEquals("audio/mpeg", audioMime(null, "song.mp3"))
        assertEquals("audio/mp4", audioMime(null, "song.M4A"))
        assertEquals("audio/flac", audioMime("audio/flac", "/live"))
        assertEquals("audio/mp4", audioMime("audio/x-m4a", null))
        try { audioMime("video/mp4", "movie.mp4"); fail("Video MIME should be rejected") }
        catch (error: SonosException) { assertEquals(SonosErrorCode.INVALID_SOURCE, error.code) }
    }

    @Test fun directUrlsAcceptHttpAndHttpsButRejectNonStreamAddresses() {
        assertEquals("http", validateStreamUrl("http://192.168.0.20/song.mp3").scheme)
        assertEquals("https", validateStreamUrl("https://example.test/live?token=123").scheme)
        listOf("file:///song.mp3", "content://provider/song", "/relative/song.mp3",
            "https://user:pass@example.test/song", "https://example.test/song#fragment").forEach { value ->
            try { validateStreamUrl(value); fail("Expected invalid source for $value") }
            catch (error: SonosException) { assertEquals(SonosErrorCode.INVALID_SOURCE, error.code) }
        }
    }
    @Test fun pausedHandoffSeeksBeforeStartingAndDoesNotEmitPlay() = runBlocking {
        val f = Fixture()
        f.engine.play(room, AudioSource.Url("http://example.test/song.flac"),
            SonosMetadata("Titel", durationSeconds = 30), initialPositionSeconds = 12, playWhenReady = false)
        assertEquals(12, f.client.lastSeek)
        assertEquals(0, f.client.starts)
        assertFalse(f.client.transport.playing)
        assertTrue(f.engine.state.value.session)
        f.engine.shutdown()
    }

    @Test fun playingHandoffPreservesItsPosition() = runBlocking {
        val f = Fixture()
        f.engine.play(room, AudioSource.Url("http://example.test/song.flac"),
            SonosMetadata("Titel", durationSeconds = 30), initialPositionSeconds = 12, playWhenReady = true)
        assertEquals(12, f.client.lastSeek)
        assertEquals(1, f.client.starts)
        assertTrue(f.client.transport.playing)
        f.engine.shutdown()
    }
}
