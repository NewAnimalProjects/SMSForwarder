package dev.zaplatin.smsforwarder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Always-on foreground service. It keeps the process exempt from battery saver restrictions
 * (so SMS forwarding gets network access right away) and watches the battery level in real time.
 */
class ForwarderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPercent = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val percent = BatteryAlerts.percentFrom(intent) ?: return
            // ACTION_BATTERY_CHANGED also fires on voltage/temperature changes; react to % only.
            if (percent == lastPercent) return
            lastPercent = percent
            scope.launch { BatteryAlerts.process(applicationContext, percent) }
        }
    }

    // No replay: the callback's initial onAvailable fires before any backoff and must not end it.
    private val networkAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable.tryEmit(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        OperationLogger.d(TAG, "Service created")
        try {
            startInForeground()
        } catch (e: Exception) {
            OperationLogger.e(TAG, "Unable to enter foreground", e)
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startedAt = System.currentTimeMillis()
        getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(networkCallback)
        startBotPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        OperationLogger.d(TAG, "Service destroyed")
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        startedAt = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBotPolling() {
        val poller = BotUpdatePoller(
            api = TelegramClient,
            handleCommand = BotCommandHandler(AndroidDeviceStatusProvider(applicationContext))::handle,
            commands = BotCommandHandler.COMMANDS,
            offsetStore = SharedPreferencesOffsetStore(applicationContext),
            authorizedChatIds = TelegramClient.authorizedChatIds,
            clock = System::currentTimeMillis,
            networkAvailable = networkAvailable,
        )
        scope.launch { poller.run() }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_forwarder)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "forwarder_service"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ForwarderService"

        /** When the service entered the foreground; null while it is not running. */
        @Volatile
        var startedAt: Long? = null
            private set

        /** Safe to call repeatedly; the system may refuse a start from the background. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ForwarderService::class.java),
                )
            } catch (e: Exception) {
                OperationLogger.e(TAG, "Unable to start service", e)
            }
        }
    }
}
