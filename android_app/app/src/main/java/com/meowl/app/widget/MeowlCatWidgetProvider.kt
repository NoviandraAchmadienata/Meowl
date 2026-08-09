package com.meowl.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.meowl.app.MainActivity
import com.meowl.app.R
import com.meowl.app.audio.AudioPlayer
import com.meowl.app.audio.AudioRecorder
import com.meowl.app.data.PreferencesManager
import com.meowl.app.network.NetworkRelay
import java.io.File
import java.util.Locale

/**
 * Interactive Cat Eyes AppWidget Provider for Android Home Screen.
 * Fully synchronized with crash-free audio player and NOTIFY OLED incoming message display.
 */
class MeowlCatWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TAP_PLAY      = "com.meowl.app.ACTION_WIDGET_TAP_PLAY"
        const val ACTION_WIDGET_TAP_PAUSE     = "com.meowl.app.ACTION_WIDGET_TAP_PAUSE"
        const val ACTION_WIDGET_STOP_PLAY     = "com.meowl.app.ACTION_WIDGET_STOP_PLAY"
        const val ACTION_WIDGET_SEND_PING     = "com.meowl.app.ACTION_WIDGET_SEND_PING"
        const val ACTION_WIDGET_RECORD_TOGGLE = "com.meowl.app.ACTION_WIDGET_RECORD_TOGGLE"
        const val ACTION_WIDGET_RECORD_CANCEL = "com.meowl.app.ACTION_WIDGET_RECORD_CANCEL"

        private var isWidgetRecording = false
        private var isWidgetPlaying = false
        private var widgetRecorder: AudioRecorder? = null
        private var widgetPlayer: AudioPlayer? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun updateAllWidgets(context: Context, state: String = "IDLE", statusMsg: String = "ONLINE · READY", unreadCount: Int = 0) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, MeowlCatWidgetProvider::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                for (widgetId in allWidgetIds) {
                    updateAppWidget(context, appWidgetManager, widgetId, state, statusMsg, unreadCount)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: String,
            statusMsg: String,
            unreadCount: Int
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_meowl_cat)
                val prefs = PreferencesManager(context)

                // 1. Synchronize Casing Theme Color & Cat Ear Colors
                val (casingRes, earLeftRes, earRightRes) = when (prefs.casingTheme) {
                    "Blue" -> Triple(R.drawable.bg_cat_casing_blue, R.drawable.ic_cat_ear_left_blue, R.drawable.ic_cat_ear_right_blue)
                    "Purple" -> Triple(R.drawable.bg_cat_casing_purple, R.drawable.ic_cat_ear_left_purple, R.drawable.ic_cat_ear_right_purple)
                    "Yellow" -> Triple(R.drawable.bg_cat_casing_yellow, R.drawable.ic_cat_ear_left_yellow, R.drawable.ic_cat_ear_right_yellow)
                    else -> Triple(R.drawable.bg_cat_casing_pink, R.drawable.ic_cat_ear_left, R.drawable.ic_cat_ear_right)
                }

                views.setInt(R.id.widget_cat_casing, "setBackgroundResource", casingRes)
                views.setImageViewResource(R.id.img_widget_ear_left, earLeftRes)
                views.setImageViewResource(R.id.img_widget_ear_right, earRightRes)

                // 2. Synchronize Title & Unread Badge
                views.setTextViewText(R.id.txt_widget_title, prefs.myId.uppercase(Locale.getDefault()))
                views.setTextViewText(
                    R.id.txt_unread_badge,
                    if (unreadCount > 0) "$unreadCount UNREAD" else "0 UNREAD"
                )

                // 3. Dynamic State Eye Visuals & Containers
                views.setViewVisibility(R.id.flipper_idle_eyes, if (state == "IDLE") View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.layout_recording_wave, if (state == "RECORDING") View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.layout_playing_viz, if (state == "PLAYING") View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.layout_sent_success, if (state == "SENT") View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.layout_paused_bars, if (state == "PAUSED") View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.img_ping_heart, if (state == "PING") View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.layout_incoming_notify, if (state == "NOTIFY") View.VISIBLE else View.GONE)

                if (state == "NOTIFY") {
                    views.setTextViewText(R.id.txt_notify_label, "$unreadCount PESAN BARU")
                }

                // Dynamic Action Button Rows
                val isRec = (state == "RECORDING")
                val isPlay = (state == "PLAYING" || state == "PAUSED")
                
                views.setViewVisibility(R.id.layout_widget_normal_btns, if (!isRec && !isPlay) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.layout_widget_play_btns, if (isPlay) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.layout_widget_rec_btns, if (isRec) View.VISIBLE else View.GONE)

                // Live Chronometer Timers for Recording & Playing
                if (isRec) {
                    views.setChronometer(R.id.chrono_widget_rec, android.os.SystemClock.elapsedRealtime(), "%s", true)
                } else {
                    views.setChronometer(R.id.chrono_widget_rec, android.os.SystemClock.elapsedRealtime(), "%s", false)
                }

                if (state == "PLAYING") {
                    views.setChronometer(R.id.chrono_widget_play, android.os.SystemClock.elapsedRealtime(), "%s", true)
                } else {
                    views.setChronometer(R.id.chrono_widget_play, android.os.SystemClock.elapsedRealtime(), "%s", false)
                }

                // Change PAUSE button text dynamically
                views.setTextViewText(R.id.btn_widget_pause, if (state == "PAUSED") "RESUME" else "PAUSE")

                // Intent Play Action
                val playIntent = Intent(context, MeowlCatWidgetProvider::class.java).apply { action = ACTION_WIDGET_TAP_PLAY }
                val playPendingIntent = PendingIntent.getBroadcast(context, 101, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_play, playPendingIntent)

                // Intent Pause Action
                val pauseIntent = Intent(context, MeowlCatWidgetProvider::class.java).apply { action = ACTION_WIDGET_TAP_PAUSE }
                val pausePendingIntent = PendingIntent.getBroadcast(context, 102, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_pause, pausePendingIntent)

                // Intent Stop Play Action
                val stopPlayIntent = Intent(context, MeowlCatWidgetProvider::class.java).apply { action = ACTION_WIDGET_STOP_PLAY }
                val stopPlayPendingIntent = PendingIntent.getBroadcast(context, 103, stopPlayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_stop_play, stopPlayPendingIntent)

                // Intent Ping Action
                val pingIntent = Intent(context, MeowlCatWidgetProvider::class.java).apply { action = ACTION_WIDGET_SEND_PING }
                val pingPendingIntent = PendingIntent.getBroadcast(context, 104, pingIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_ping, pingPendingIntent)

                // DIRECT WIDGET RECORD / STOP ACTION
                val recIntent = Intent(context, MeowlCatWidgetProvider::class.java).apply { action = ACTION_WIDGET_RECORD_TOGGLE }
                val recPendingIntent = PendingIntent.getBroadcast(context, 105, recIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_record, recPendingIntent)
                views.setOnClickPendingIntent(R.id.btn_widget_stop, recPendingIntent)

                // DIRECT WIDGET CANCEL RECORDING ACTION
                val cancelIntent = Intent(context, MeowlCatWidgetProvider::class.java).apply { action = ACTION_WIDGET_RECORD_CANCEL }
                val cancelPendingIntent = PendingIntent.getBroadcast(context, 106, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_widget_cancel, cancelPendingIntent)

                // Tapping OLED screen opens main App
                val appIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
                val appPendingIntent = PendingIntent.getActivity(context, 107, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_oled_screen, appPendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun getLatestVoicemail(context: Context): File? {
            val folder = File(context.filesDir, "voicemails")
            if (!folder.exists()) return null
            val files = folder.listFiles()?.filter {
                (it.extension == "wav" || it.extension == "3gp") && !it.name.startsWith("kirim_")
            } ?: return null
            return files.maxByOrNull { it.lastModified() }
        }

        private fun getAllUnreadVoicemails(context: Context): List<File> {
            val folder = File(context.filesDir, "voicemails")
            if (!folder.exists()) return emptyList()
            val files = folder.listFiles()?.filter {
                (it.extension == "wav" || it.extension == "3gp")
            } ?: return emptyList()
            return files.filter { it.name.startsWith("baru_") }.sortedBy { it.lastModified() }
        }

        private fun playWidgetQueue(context: Context, files: List<File>, index: Int) {
            if (index >= files.size || !isWidgetPlaying) {
                isWidgetPlaying = false
                mainHandler.post {
                    updateAllWidgets(context, "IDLE", "ONLINE · READY", 0)
                }
                return
            }

            val file = files[index]
            
            // Mark as read immediately when played
            val fileToPlay = if (file.name.startsWith("baru_")) {
                val targetFile = File(file.parentFile, file.name.replace("baru_", "lama_"))
                file.renameTo(targetFile)
                targetFile
            } else file

            widgetPlayer?.playAudioFile(fileToPlay) {
                playWidgetQueue(context, files, index + 1)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, "IDLE", "ONLINE · READY", 0)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = PreferencesManager(context)

        when (intent.action) {
            ACTION_WIDGET_TAP_PLAY -> {
                val unreadFiles = getAllUnreadVoicemails(context)
                if (unreadFiles.isNotEmpty()) {
                    Toast.makeText(context, "Memutar pesan baru...", Toast.LENGTH_SHORT).show()
                    isWidgetPlaying = true
                    updateAllWidgets(context, "PLAYING", "PLAYING AUDIO", 0)
                    if (widgetPlayer == null) widgetPlayer = AudioPlayer(context)

                    playWidgetQueue(context, unreadFiles, 0)
                } else {
                    val latestFile = getLatestVoicemail(context)
                    if (latestFile != null) {
                        Toast.makeText(context, "Memutar voicemail...", Toast.LENGTH_SHORT).show()
                        isWidgetPlaying = true
                        updateAllWidgets(context, "PLAYING", "PLAYING AUDIO", 0)

                        if (widgetPlayer == null) widgetPlayer = AudioPlayer(context)
                        widgetPlayer?.playAudioFile(latestFile) {
                            isWidgetPlaying = false
                            mainHandler.post {
                                updateAllWidgets(context, "IDLE", "ONLINE · READY", 0)
                            }
                        }
                    } else {
                        Toast.makeText(context, "Tidak ada pesan voicemail", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ACTION_WIDGET_TAP_PAUSE -> {
                if (isWidgetPlaying) {
                    widgetPlayer?.pauseAudio()
                    isWidgetPlaying = false
                    Toast.makeText(context, "Pemutaran dijeda", Toast.LENGTH_SHORT).show()
                    updateAllWidgets(context, "PAUSED", "AUDIO PAUSED", 0)
                } else {
                    widgetPlayer?.resumeAudio()
                    isWidgetPlaying = true
                    Toast.makeText(context, "Lanjut memutar", Toast.LENGTH_SHORT).show()
                    updateAllWidgets(context, "PLAYING", "PLAYING AUDIO", 0)
                }
            }
            ACTION_WIDGET_STOP_PLAY -> {
                widgetPlayer?.stopAudio()
                isWidgetPlaying = false
                Toast.makeText(context, "Pemutaran dihentikan", Toast.LENGTH_SHORT).show()
                updateAllWidgets(context, "IDLE", "ONLINE · READY", 0)
            }
            ACTION_WIDGET_SEND_PING -> {
                NetworkRelay.sendPing(prefs.vpsServerHost, prefs.targetId) { success ->
                    if (success) {
                        Toast.makeText(context, "Ping terkirim", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Gagal terhubung ke VPS", Toast.LENGTH_SHORT).show()
                    }
                }
                updateAllWidgets(context, "PING", "PING SENT!", 0)
                
                mainHandler.postDelayed({
                    updateAllWidgets(context, "IDLE", "ONLINE · READY", 0)
                }, 3000)
            }
            ACTION_WIDGET_RECORD_TOGGLE -> {
                if (!isWidgetRecording) {
                    if (widgetRecorder == null) widgetRecorder = AudioRecorder(context)
                    widgetRecorder?.startRecording("kirim_${System.currentTimeMillis() / 1000}.wav")
                    isWidgetRecording = true
                    Toast.makeText(context, "Merekam voicemail...", Toast.LENGTH_SHORT).show()
                    updateAllWidgets(context, "RECORDING", "RECORDING...", 0)
                } else {
                    val recordedFile = widgetRecorder?.stopRecording()
                    isWidgetRecording = false

                    if (recordedFile != null && recordedFile.exists()) {
                        NetworkRelay.uploadAudioFile(prefs.vpsServerHost, prefs.targetId, recordedFile) { success ->
                            if (success) {
                                Toast.makeText(context, "Pesan voicemail terkirim", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Gagal mengunggah ke VPS", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    updateAllWidgets(context, "SENT", "SENT SUCCESS", 0)
                    
                    mainHandler.postDelayed({
                        updateAllWidgets(context, "IDLE", "ONLINE · READY", 0)
                    }, 2500)
                }
            }
            ACTION_WIDGET_RECORD_CANCEL -> {
                if (isWidgetRecording) {
                    val recordedFile = widgetRecorder?.stopRecording()
                    recordedFile?.let { if (it.exists()) it.delete() }
                    isWidgetRecording = false
                    Toast.makeText(context, "Rekaman dibatalkan", Toast.LENGTH_SHORT).show()
                    updateAllWidgets(context, "IDLE", "ONLINE · READY", 0)
                }
            }
        }
    }
}
