package at.techtown.sonoscontroller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

internal interface HostedAudio : Closeable {
    val url: String
    val mimeType: String
    fun checkConnection()
}
internal fun interface AudioHosting {
    suspend fun prepare(source: AudioSource, metadata: SonosMetadata?): HostedAudio
}

/** Serialized session logic; Android resources are injected and can be tested independently. */
internal class SessionEngine(
    private val client: SonosControl,
    private val discovery: DeviceDiscovery,
    private val hosting: AudioHosting,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMillis: Long = 2000
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SonosState())
    val state = mutableState.asStateFlow()
    private data class Session(val device: SonosDevice, val audio: HostedAudio)
    private var session: Session? = null
    private var closed = false
    private var pollJob: Job? = null

    private suspend fun <T> operation(action: suspend () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            if (closed) throw SonosException(SonosErrorCode.CLOSED, "SonosController ist geschlossen.")
            mutableState.update { it.copy(busy = true, error = null) }
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = asSonosException(e)
                mutableState.update { it.copy(error = SonosError(failure.code, failure.message.orEmpty())) }
                throw failure
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    suspend fun discover(): List<SonosDevice> = operation {
        mutableState.update { it.copy(scanning = true) }
        try {
            discovery.discover().also { found ->
                mutableState.update { it.copy(devices = (found + listOfNotNull(it.selected)).distinctBy { d -> d.id }) }
            }
        } finally { mutableState.update { it.copy(scanning = false) } }
    }
    suspend fun connect(ipAddress: String): SonosDevice = operation {
        val ip = ipAddress.trim()
        if (!Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(ip) ||
            ip.split('.').any { it.toInt() !in 0..255 })
            throw SonosException(SonosErrorCode.INVALID_ADDRESS, "Bitte eine gültige IPv4-Adresse eingeben.")
        val device = client.device(ip)
        selectInternal(device)
        device
    }
    suspend fun select(device: SonosDevice) = operation { selectInternal(device) }
    private fun selectInternal(device: SonosDevice) {
        if (session != null && session?.device?.id != device.id)
            throw SonosException(SonosErrorCode.NO_SESSION, "Vor einem Raumwechsel die Sitzung beenden oder play aufrufen.")
        mutableState.update { it.copy(selected = device, connected = false,
            devices = (it.devices + device).distinctBy { d -> d.id }) }
        refreshInternal(device)
        startPolling()
    }
    suspend fun play(device: SonosDevice, source: AudioSource, metadata: SonosMetadata?, initialPositionSeconds: Int = 0, playWhenReady: Boolean = true) = operation {
        // Prepare first, so a bad source does not interrupt an existing session.
        val next = hosting.prepare(source, metadata)
        var accepted = false
        try {
            endInternal()
            val coordinator = client.coordinator(device)
            session = Session(device, next)
            mutableState.update { it.copy(selected = device, session = true, connected = false,
                devices = (it.devices + device).distinctBy { d -> d.id }) }
            client.load(coordinator, next.url, metadata, next.mimeType)
            if (initialPositionSeconds > 0) {
                val loaded = client.state(coordinator)
                val duration = loaded.duration.takeIf { it > 0 } ?: metadata?.durationSeconds ?: 0
                if (duration <= 0) throw SonosException(SonosErrorCode.SEEK_UNSUPPORTED, "Startposition kann nicht übernommen werden.")
                client.seek(coordinator, initialPositionSeconds.coerceIn(0, (duration - 1).coerceAtLeast(0)))
            }
            if (playWhenReady) client.play(coordinator)
            accepted = true
            startPolling()
            refreshInternal(device)
        } finally {
            if (!accepted) withContext(NonCancellable) {
                // Loading or Play may have succeeded before an HTTP response was lost.
                runCatching { stopIfOwned(session?.takeIf { it.audio === next }) }
                if (session?.audio === next) session = null
                next.close()
                mutableState.update { it.copy(session = session != null, ownTrack = false, playing = false) }
            }
        }
    }
    private fun owned(device: SonosDevice): Pair<SonosDevice, TransportState> {
        val active = session ?: throw SonosException(SonosErrorCode.NO_SESSION, "Keine eigene Sonos-Sitzung aktiv.")
        if (active.device.id != device.id)
            throw SonosException(SonosErrorCode.NO_SESSION, "Dieser Raum gehört nicht zur aktiven Sitzung.")
        active.audio.checkConnection()
        val coordinator = client.coordinator(device)
        val transport = client.state(coordinator)
        if (transport.uri != active.audio.url) {
            mutableState.update { it.copy(ownTrack = false, playing = transport.playing) }
            throw SonosException(SonosErrorCode.FOREIGN_PLAYBACK, "Auf Sonos läuft inzwischen eine andere Wiedergabe.")
        }
        return coordinator to transport
    }
    suspend fun pause(device: SonosDevice) = operation {
        client.pause(owned(device).first); refreshInternal(device)
    }
    suspend fun resume(device: SonosDevice) = operation {
        client.play(owned(device).first); refreshInternal(device)
    }
    suspend fun seekTo(device: SonosDevice, seconds: Int) = operation { seekInternal(device, seconds.toLong()) }
    suspend fun seekBy(device: SonosDevice, offset: Int) = operation {
        val (coordinator, transport) = owned(device)
        seekOwned(coordinator, transport, transport.position.toLong() + offset)
        refreshInternal(device)
    }
    private fun seekInternal(device: SonosDevice, seconds: Long) {
        val (coordinator, transport) = owned(device)
        seekOwned(coordinator, transport, seconds)
        refreshInternal(device)
    }
    private fun seekOwned(coordinator: SonosDevice, transport: TransportState, seconds: Long) {
        if (transport.duration <= 0)
            throw SonosException(SonosErrorCode.SEEK_UNSUPPORTED, "Dieser Stream meldet keine spulbare Dauer.")
        client.seek(coordinator, seconds.coerceIn(0, (transport.duration - 1).toLong()).toInt())
    }
    suspend fun setVolume(device: SonosDevice, volume: Int) = operation {
        client.setVolume(device, volume.coerceIn(0, 100)); refreshInternal(device)
    }
    suspend fun changeVolume(device: SonosDevice, step: Int) = operation {
        val current = client.volume(device)
        client.setVolume(device, (current.toLong() + step).coerceIn(0, 100).toInt())
        refreshInternal(device)
    }
    suspend fun refresh(device: SonosDevice) = operation { refreshInternal(device) }
    private fun refreshInternal(device: SonosDevice) {
        session?.takeIf { it.device.id == device.id }?.audio?.checkConnection()
        val transport = client.state(client.coordinator(device))
        val volume = client.volume(device)
        mutableState.update { it.copy(selected = device, connected = true,
            error = it.error?.takeUnless { error -> error.code in setOf(SonosErrorCode.DEVICE_UNREACHABLE, SonosErrorCode.NO_WIFI, SonosErrorCode.NETWORK_CHANGED) },
            ownTrack = session?.let { s -> s.device.id == device.id && s.audio.url == transport.uri } == true,
            playing = transport.playing, transportStatus = transport.status, position = transport.position, duration = transport.duration, volume = volume) }
    }
    private fun startPolling() {
        if (pollIntervalMillis <= 0 || pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                mutex.withLock {
                    if (closed) return@launch
                    val device = state.value.selected ?: return@withLock
                    try { refreshInternal(device) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val failure = asSonosException(e)
                        mutableState.update { it.copy(connected = false,
                            error = SonosError(failure.code, failure.message.orEmpty())) }
                    }
                }
            }
        }
    }
    private fun stopIfOwned(active: Session?) {
        active ?: return
        val coordinator = client.coordinator(active.device)
        if (client.state(coordinator).uri == active.audio.url) client.stop(coordinator)
    }
    private fun endInternal() {
        val previous = session ?: return
        try { stopIfOwned(previous) }
        finally {
            session = null
            previous.audio.close()
            mutableState.update { it.copy(session = false, ownTrack = false, playing = false, position = 0, duration = 0) }
        }
    }
    suspend fun endSession() = operation { withContext(NonCancellable) { endInternal() } }
    suspend fun shutdown() = withContext(dispatcher + NonCancellable) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            pollJob?.cancel()
            try { endInternal() } finally { scope.cancel() }
        }
    }
}
