package at.techtown.sonoscontroller

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Internal manifest entry point. Application code uses SonosController. */
class SonosHostingService : Service() {
    private var expectedStop = false
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val renew = object : Runnable {
        override fun run() {
            wakeLock?.acquire(10 * 60 * 1000L)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "END") {
            SonosController.current()?.endFromNotification()
            return START_NOT_STICKY
        }
        instance = this
        try {
            if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Sonos-Audiobereitstellung", NotificationManager.IMPORTANCE_LOW))
            val end = PendingIntent.getService(this, 1, Intent(this, SonosHostingService::class.java)
                .setAction("END"), PendingIntent.FLAG_IMMUTABLE)
            val builder = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(intent?.getStringExtra("title") ?: "Sonos-Wiedergabe")
                .setContentText("Lokale Audiodatei wird im WLAN bereitgestellt")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", end)
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
            ServiceCompat.startForeground(this, 7631, builder.build(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            if (wakeLock == null) {
                wifiLock = WifiConnection(this).streamingLock().also { it.acquire() }
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "SonosController:hosting").apply { setReferenceCounted(false) }
                renew.run()
            }
            ready?.complete(Unit)
        } catch (error: Exception) {
            ready?.completeExceptionally(error)
            expectedStop = true
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(renew)
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        if (instance === this) instance = null
        if (!expectedStop) SonosController.current()?.endFromNotification()
        super.onDestroy()
    }
    internal companion object {
        private const val CHANNEL = "sonoscontroller.hosting"
        @Volatile private var instance: SonosHostingService? = null
        @Volatile private var ready: CompletableDeferred<Unit>? = null
        suspend fun start(context: Context, title: String?) {
            val signal = CompletableDeferred<Unit>()
            ready = signal
            try {
                ContextCompat.startForegroundService(context, Intent(context, SonosHostingService::class.java).putExtra("title", title))
                withTimeout(10_000) { signal.await() }
            } catch (error: Exception) {
                stop(context)
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                throw SonosException(SonosErrorCode.SERVICE_UNAVAILABLE,
                    "Der Sonos-Hintergrunddienst konnte nicht starten. Lokale Wiedergabe aus einer sichtbaren Activity starten.", error)
            } finally { if (ready === signal) ready = null }
        }
        fun stop(context: Context) {
            instance?.expectedStop = true
            context.stopService(Intent(context, SonosHostingService::class.java))
        }
    }
}
