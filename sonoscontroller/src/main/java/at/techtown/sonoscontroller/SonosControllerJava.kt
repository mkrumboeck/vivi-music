package at.techtown.sonoscontroller

import android.content.Context
import java.io.Closeable
import kotlinx.coroutines.*

/** Java callbacks are delivered on the Android main thread. */
class SonosControllerJava(context: Context) : Closeable {
    interface Callback<T> {
        fun onSuccess(value: T)
        fun onError(error: SonosException)
    }
    fun interface StateListener { fun onState(state: SonosState) }
    private val controller = SonosController.create(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun <T> launch(callback: Callback<T>, action: suspend () -> T) {
        if (!scope.isActive) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback.onError(SonosException(SonosErrorCode.CLOSED, "SonosController-Adapter ist geschlossen."))
            }
            return
        }
        scope.launch {
            val result = try { Result.success(action()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(asSonosException(e)) }
            result.fold(callback::onSuccess) { callback.onError(it as SonosException) }
        }
    }
    private fun command(callback: Callback<Void?>, action: suspend () -> Unit) =
        launch(callback) { action(); null }

    fun discover(callback: Callback<List<SonosDevice>>) = launch(callback) { controller.discover() }
    fun connect(ipAddress: String, callback: Callback<SonosDevice>) = launch(callback) { controller.connect(ipAddress) }
    fun select(device: SonosDevice, callback: Callback<Void?>) = command(callback) { controller.select(device) }
    fun play(device: SonosDevice, source: AudioSource, callback: Callback<Void?>) = play(device, source, null, callback)
    fun play(device: SonosDevice, source: AudioSource, metadata: SonosMetadata?, callback: Callback<Void?>) =
        command(callback) { controller.play(device, source, metadata) }
    fun play(device: SonosDevice, source: AudioSource, metadata: SonosMetadata?,
             initialPositionSeconds: Int, playWhenReady: Boolean, callback: Callback<Void?>) =
        command(callback) { controller.play(device, source, metadata, initialPositionSeconds, playWhenReady) }
    fun pause(device: SonosDevice, callback: Callback<Void?>) = command(callback) { controller.pause(device) }
    fun resume(device: SonosDevice, callback: Callback<Void?>) = command(callback) { controller.resume(device) }
    fun seekTo(device: SonosDevice, seconds: Int, callback: Callback<Void?>) = command(callback) { controller.seekTo(device, seconds) }
    fun seekBy(device: SonosDevice, seconds: Int, callback: Callback<Void?>) = command(callback) { controller.seekBy(device, seconds) }
    fun setVolume(device: SonosDevice, volume: Int, callback: Callback<Void?>) = command(callback) { controller.setVolume(device, volume) }
    @JvmOverloads fun volumeUp(device: SonosDevice, callback: Callback<Void?>, step: Int = 2) =
        command(callback) { controller.volumeUp(device, step) }
    @JvmOverloads fun volumeDown(device: SonosDevice, callback: Callback<Void?>, step: Int = 2) =
        command(callback) { controller.volumeDown(device, step) }
    fun refresh(device: SonosDevice, callback: Callback<Void?>) = command(callback) { controller.refresh(device) }
    fun endSession(callback: Callback<Void?>) = command(callback) { controller.endSession() }
    fun observe(listener: StateListener): Closeable {
        val job = scope.launch { controller.state.collect { listener.onState(it) } }
        return Closeable { job.cancel() }
    }
    /** Cancels listeners and closes the application-scoped controller. */
    override fun close() { scope.cancel(); controller.close() }
    fun close(callback: Callback<Void?>) {
        scope.cancel()
        controller.close()
        val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        completionScope.launch {
            val result = try { controller.awaitClosed(); Result.success<Void?>(null) }
            catch (e: Exception) { Result.failure<Void?>(asSonosException(e)) }
            try { result.fold(callback::onSuccess) { callback.onError(it as SonosException) } }
            finally { completionScope.cancel() }
        }
    }
}
