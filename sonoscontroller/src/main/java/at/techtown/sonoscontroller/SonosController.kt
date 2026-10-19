package at.techtown.sonoscontroller

import android.content.Context
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-scoped controller. Obtain once with create(context); never retain an Activity.
 * Local playback must be initiated while the host app is visible (Android FGS requirement).
 */
class SonosController private constructor(context: Context) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifi = WifiConnection(context)
    private val client = SonosClient(wifi::open)
    private val engine = SessionEngine(client, SsdpDiscovery(wifi, client), AndroidAudioHosting(context, wifi))
    private val closed = CompletableDeferred<Unit>()
    @Volatile private var closing = false
    val state: StateFlow<SonosState> = engine.state

    suspend fun discover(): List<SonosDevice> { ensureOpen(); return engine.discover() }
    suspend fun connect(ipAddress: String): SonosDevice { ensureOpen(); return engine.connect(ipAddress) }
    suspend fun select(device: SonosDevice) { ensureOpen(); engine.select(device) }
    suspend fun play(device: SonosDevice, source: AudioSource, metadata: SonosMetadata? = null) {
        ensureOpen(); engine.play(device, source, metadata)
    }
    suspend fun play(device: SonosDevice, source: AudioSource, metadata: SonosMetadata?,
                     initialPositionSeconds: Int, playWhenReady: Boolean) {
        ensureOpen(); engine.play(device, source, metadata, initialPositionSeconds, playWhenReady)
    }
    suspend fun pause(device: SonosDevice) { ensureOpen(); engine.pause(device) }
    suspend fun resume(device: SonosDevice) { ensureOpen(); engine.resume(device) }
    suspend fun seekTo(device: SonosDevice, positionSeconds: Int) { ensureOpen(); engine.seekTo(device, positionSeconds) }
    suspend fun seekBy(device: SonosDevice, offsetSeconds: Int) { ensureOpen(); engine.seekBy(device, offsetSeconds) }
    suspend fun setVolume(device: SonosDevice, volume: Int) { ensureOpen(); engine.setVolume(device, volume) }
    suspend fun volumeUp(device: SonosDevice, step: Int = 2) {
        ensureOpen(); engine.changeVolume(device, step.coerceAtLeast(0))
    }
    suspend fun volumeDown(device: SonosDevice, step: Int = 2) {
        ensureOpen(); engine.changeVolume(device, -step.coerceAtLeast(0))
    }
    suspend fun refresh(device: SonosDevice) { ensureOpen(); engine.refresh(device) }
    suspend fun endSession() { ensureOpen(); engine.endSession() }
    private fun ensureOpen() {
        if (closing) throw SonosException(SonosErrorCode.CLOSED, "SonosController ist geschlossen.")
    }
    internal fun endFromNotification() {
        scope.launch { runCatching { engine.endSession() } }
    }
    /** Non-blocking. Use awaitClosed to wait until Stop and resource cleanup have finished. */
    @Synchronized override fun close() {
        if (closing) return
        closing = true
        scope.launch {
            try { engine.shutdown(); closed.complete(Unit) }
            catch (e: Exception) { closed.completeExceptionally(asSonosException(e)) }
            finally {
                synchronized(Companion) { if (instance === this@SonosController) instance = null }
                scope.cancel()
            }
        }
    }
    suspend fun awaitClosed() = closed.await()

    companion object {
        @Volatile private var instance: SonosController? = null
        @JvmStatic @Synchronized fun create(context: Context): SonosController {
            val existing = instance
            if (existing != null) {
                existing.ensureOpen()
                return existing
            }
            return SonosController(context.applicationContext).also { instance = it }
        }
        internal fun current(): SonosController? = instance
    }
}
