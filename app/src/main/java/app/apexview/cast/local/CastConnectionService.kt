package app.apexview.cast.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.apexview.MainActivity
import app.apexview.R

/** Keeps an explicitly selected Cast sender's CPU/network alive. Media ownership
 * remains with the playback coordinator until an independent producer is installed. */
class CastConnectionService : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "TV casting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 41, Intent(this, CastConnectionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_pip_play)
            .setContentTitle("Pitwall TV casting").setContentText("Keeping the TV connection and phone controls available")
            .setContentIntent(open).setOngoing(true).setSilent(true).addAction(0, "Stop casting", stop).build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pitwall:TVConnection").apply {
            setReferenceCounted(false); acquire()
        }
        @Suppress("DEPRECATION")
        wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Pitwall:TVConnection")?.apply {
            setReferenceCounted(false); acquire()
        }
        android.util.Log.i("PitwallCast", "CAST_BACKGROUND_SERVICE_STARTED cpuHeld=${cpu?.isHeld == true} wifiHeld=${wifi?.isHeld == true}")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == STOP) {
            if (intent?.action == STOP && !app.apexview.PlaybackLifetime.stopFromService())
                sendBroadcast(Intent(STOP_CONNECTION).setPackage(packageName))
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (wifi?.isHeld == true) wifi?.release(); wifi = null
        if (cpu?.isHeld == true) cpu?.release(); cpu = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        android.util.Log.i("PitwallCast", "CAST_BACKGROUND_SERVICE_STOPPED")
        app.apexview.PlaybackLifetime.serviceRetired()
        super.onDestroy()
    }
    companion object {
        const val STOP_CONNECTION = "app.apexview.STOP_LOCAL_CAST"
        private const val STOP = "app.apexview.cast.STOP_CONNECTION_SERVICE"
        private const val CHANNEL = "tv-casting"
        private const val ID = 41
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, CastConnectionService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, CastConnectionService::class.java)) }
    }
}
