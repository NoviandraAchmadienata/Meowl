package com.meowl.app.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.meowl.app.MainActivity
import com.meowl.app.R
import com.meowl.app.data.PreferencesManager
import com.meowl.app.widget.MeowlCatWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 24/7 Real-Time Background Relay Service for Meowl LDR App & Home Screen Widget.
 * Ensures pings, incoming voicemails, and connect requests are delivered live even when the main app is closed.
 */
class MeowlRelayService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isPolling = false

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val gForce = Math.sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()

                if (gForce > 2.4 && (now - lastShakeTime > 1200L)) {
                    lastShakeTime = now
                    triggerWidgetDizzyState()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun triggerWidgetDizzyState() {
        val currentState = MeowlCatWidgetProvider.currentWidgetState
        if (currentState == "RECORDING" || currentState == "PLAYING") return

        val unreadCount = getUnreadVoicemailCount()
        MeowlCatWidgetProvider.updateAllWidgets(applicationContext, "DIZZY", "DIZZY ~ MEOWL", unreadCount)

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(180)
            }
        } catch (e: Exception) {}

        serviceScope.launch {
            delay(3500)
            if (MeowlCatWidgetProvider.currentWidgetState == "DIZZY") {
                val latestUnread = getUnreadVoicemailCount()
                MeowlCatWidgetProvider.updateAllWidgets(applicationContext, "IDLE", "ONLINE · READY", latestUnread)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "meowl_relay_channel"
        private const val NOTIFY_CHANNEL_ID = "meowl_voicemail_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 901
        private const val MESSAGE_NOTIFICATION_ID = 902

        fun startService(context: Context) {
            try {
                val intent = Intent(context, MeowlRelayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannels()
            val notification = createForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isPolling) {
            isPolling = true
            startRealtimePollingLoop()
        }
        return START_STICKY
    }

    private fun startRealtimePollingLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val prefs = PreferencesManager(applicationContext)
                    val host = prefs.vpsServerHost
                    val myId = prefs.myId.lowercase(Locale.getDefault())

                    if (host.isNotEmpty() && myId.isNotEmpty()) {
                        // 1. Check for incoming PING (Heart alert)
                        NetworkRelay.checkPing(host, myId) { pingReceived ->
                            if (pingReceived) {
                                triggerPingVibrationAndAlert()
                            }
                        }

                        // 2. Check for incoming Voicemails (.wav audio)
                        checkAndFetchIncomingVoicemails(host, myId)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
                delay(3500) // 3.5s real-time poll interval
            }
        }
    }

    private fun checkAndFetchIncomingVoicemails(host: String, myId: String) {
        val voicemailDir = File(applicationContext.filesDir, "voicemails")
        if (!voicemailDir.exists()) voicemailDir.mkdirs()

        NetworkRelay.checkIncomingFiles(host, myId) { remoteFiles ->
            val unreadCount = getUnreadVoicemailCount()
            val currentWidgetState = MeowlCatWidgetProvider.currentWidgetState

            if (remoteFiles.isNotEmpty()) {
                for (remoteFile in remoteFiles) {
                    // Sanitize filename: Strip pre-existing prefixes (baru_, kirim_, lama_, fav_) to avoid baru_123_baru_456.wav
                    val cleanName = remoteFile.replace(Regex("^(baru_|kirim_|lama_|fav_)+"), "")
                    val existingMatch = voicemailDir.listFiles()?.any { it.name.endsWith(cleanName) } ?: false

                    if (!existingMatch) {
                        val targetName = "baru_${System.currentTimeMillis()}_${cleanName}"
                        val destFile = File(voicemailDir, targetName)

                        NetworkRelay.downloadAudioFile(host, myId, remoteFile, destFile) { success ->
                            if (success && destFile.length() > 0L) {
                                val updatedUnread = getUnreadVoicemailCount()
                                showVoicemailNotification(updatedUnread)

                                // Broadcast to MainActivity to automatically refresh inbox UI list & trigger NOTIFY state
                                sendBroadcast(Intent("com.meowl.app.ACTION_NEW_VOICEMAIL_DOWNLOADED"))

                                // Only switch widget to NOTIFY if currently IDLE or NOTIFY (never disrupt RECORD / PLAY!)
                                if (currentWidgetState == "IDLE" || currentWidgetState == "NOTIFY") {
                                    MeowlCatWidgetProvider.updateAllWidgets(applicationContext, "NOTIFY", "PESAN BARU", updatedUnread)
                                    
                                    // Auto-dismiss NOTIFY banner after 4 seconds back to IDLE
                                    serviceScope.launch {
                                        delay(4000)
                                        if (MeowlCatWidgetProvider.currentWidgetState == "NOTIFY") {
                                            val latestUnread = getUnreadVoicemailCount()
                                            MeowlCatWidgetProvider.updateAllWidgets(applicationContext, "IDLE", "ONLINE · READY", latestUnread)
                                        }
                                    }
                                }
                            } else {
                                if (destFile.exists()) destFile.delete() // Cleanup empty download
                            }
                        }
                    }
                }
            } else {
                // Background poll tick: Only update unread badge text if widget is IDLE (do NOT force NOTIFY screen!)
                if (currentWidgetState == "IDLE") {
                    MeowlCatWidgetProvider.updateAllWidgets(applicationContext, "IDLE", "ONLINE · READY", unreadCount)
                }
            }
        }
    }

    private fun getUnreadVoicemailCount(): Int {
        val voicemailDir = File(applicationContext.filesDir, "voicemails")
        if (!voicemailDir.exists()) return 0
        return voicemailDir.listFiles()?.count { it.name.startsWith("baru_") } ?: 0
    }

    private fun triggerPingVibrationAndAlert() {
        try {
            // Vibrate heart rhythm safely
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 250), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 150, 100, 250), -1)
            }

            val currentState = MeowlCatWidgetProvider.currentWidgetState
            if (currentState == "IDLE" || currentState == "NOTIFY" || currentState == "PING") {
                // Update Widget to PING state for 3.5s
                MeowlCatWidgetProvider.updateAllWidgets(applicationContext, "PING", "PING SENT!", getUnreadVoicemailCount())
                serviceScope.launch {
                    delay(3500)
                    if (MeowlCatWidgetProvider.currentWidgetState == "PING") {
                        val unreadCount = getUnreadVoicemailCount()
                        MeowlCatWidgetProvider.updateAllWidgets(
                            applicationContext,
                            "IDLE",
                            "ONLINE · READY",
                            unreadCount
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun showVoicemailNotification(unreadCount: Int) {
        try {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Meowl — Voicemail Baru")
                .setContentText("Ada $unreadCount pesan voicemail baru dari partner Anda.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(MESSAGE_NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Meowl LDR Service")
            .setContentText("Terhubung ke server VPS · Relaying Pings & Voicemail")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val relayChannel = NotificationChannel(
                CHANNEL_ID,
                "Meowl LDR Relay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notifyChannel = NotificationChannel(
                NOTIFY_CHANNEL_ID,
                "Voicemail & Ping Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(relayChannel)
            manager.createNotificationChannel(notifyChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(sensorListener)
        serviceJob.cancel()
        isPolling = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
