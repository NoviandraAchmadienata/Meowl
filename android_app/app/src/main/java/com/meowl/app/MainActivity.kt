package com.meowl.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import com.meowl.app.utils.CityLocationHelper
import com.meowl.app.utils.LdrLocationHelper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meowl.app.audio.AudioPlayer
import com.meowl.app.audio.AudioRecorder
import com.meowl.app.data.PreferencesManager
import com.meowl.app.network.NetworkRelay
import com.meowl.app.widget.MeowlCatWidgetProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class AppVisualState {
    IDLE, RECORDING, PLAYING, PAUSED, PING, NOTIFY, DIZZY
}

class MainActivity : ComponentActivity() {

    private lateinit var prefsManager: PreferencesManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer

    private var visualState by mutableStateOf(AppVisualState.IDLE)
    private var showSettingsDialog by mutableStateOf(false)
    private var unreadCount by mutableStateOf(0)
    private var voicemailFiles by mutableStateOf(listOf<File>())
    private var playingFilePath by mutableStateOf<String?>(null)
    private var currentRecordingFile by mutableStateOf<File?>(null)
    private var isDarkModeState by mutableStateOf(true)
    private var incomingConnectRequesterId by mutableStateOf<String?>(null)

    // LDR Feature Suite States (Toggleable)
    private var enableDistanceCounterState by mutableStateOf(true)
    private var enableTimeCapsuleState by mutableStateOf(true)
    private var enableHeartbeatHugState by mutableStateOf(true)
    private var enableMoodIndicatorState by mutableStateOf(true)
    private var myCurrentMoodState by mutableStateOf("HAPPY")
    private var partnerCurrentMoodState by mutableStateOf("HAPPY")
    private var enableDailyPromptState by mutableStateOf(true)
    private var enableVoiceReactionsState by mutableStateOf(true)
    private var enableMoodLightState by mutableStateOf(true)

    // Motion & Shake Physics Eyes
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0L
    private var shakeOffsetX by mutableStateOf(0f)
    private var shakeOffsetY by mutableStateOf(0f)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Dynamic eye tilt offset following phone shake/tilt
                shakeOffsetX = (-x * 2.5f).coerceIn(-18f, 18f)
                shakeOffsetY = (y * 2.0f).coerceIn(-10f, 10f)

                val gForce = Math.sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()

                if (gForce > 2.3 && (now - lastShakeTime > 1200L)) {
                    lastShakeTime = now
                    triggerDizzyState()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun triggerDizzyState() {
        if (visualState == AppVisualState.RECORDING || visualState == AppVisualState.PLAYING) return

        visualState = AppVisualState.DIZZY
        Toast.makeText(this, "Aww Meowl Pusing! ~", Toast.LENGTH_SHORT).show()
        MeowlCatWidgetProvider.updateAllWidgets(this, "DIZZY", "DIZZY ~ MEOWL", unreadCount)

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(200)
            }
        } catch (e: Exception) {}

        mainHandler.postDelayed({
            if (visualState == AppVisualState.DIZZY) {
                visualState = AppVisualState.IDLE
                MeowlCatWidgetProvider.updateAllWidgets(this, "IDLE", "ONLINE · READY", unreadCount)
            }
        }, 3500)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!micGranted) {
            Toast.makeText(this, "Izin Mikrofon diperlukan untuk voicemail", Toast.LENGTH_LONG).show()
        }

        // Auto-detect city as soon as location permission is granted by user!
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            CityLocationHelper.detectLocalCity(this) { detectedCity ->
                val host = prefsManager.vpsServerHost
                val myId = prefsManager.myId
                if (host.isNotEmpty() && myId.isNotEmpty()) {
                    NetworkRelay.registerId(
                        vpsHost = host,
                        myId = myId,
                        deviceId = prefsManager.deviceId,
                        city = detectedCity
                    ) { _, _ -> }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefsManager = PreferencesManager(this)
        audioRecorder = AudioRecorder(this)
        audioPlayer = AudioPlayer(this)
        isDarkModeState = prefsManager.isDarkMode

        enableDistanceCounterState = prefsManager.enableDistanceCounter
        enableTimeCapsuleState = prefsManager.enableTimeCapsule
        enableHeartbeatHugState = prefsManager.enableHeartbeatHug
        enableMoodIndicatorState = prefsManager.enableMoodIndicator
        myCurrentMoodState = prefsManager.myCurrentMood
        partnerCurrentMoodState = prefsManager.partnerCurrentMood
        enableDailyPromptState = prefsManager.enableDailyPrompt
        enableVoiceReactionsState = prefsManager.enableVoiceReactions
        enableMoodLightState = prefsManager.enableMoodLight

        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val ungrantedPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungrantedPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(ungrantedPermissions.toTypedArray())
        }

        refreshVoicemailList()
        startPeriodicVpsSync()
        com.meowl.app.network.MeowlRelayService.startService(this)

        // Intelligent Auto-Detect Real City Location & Auto-Register to VPS
        CityLocationHelper.detectLocalCity(this) { detectedCity ->
            val host = prefsManager.vpsServerHost
            val myId = prefsManager.myId
            if (host.isNotEmpty() && myId.isNotEmpty()) {
                NetworkRelay.registerId(
                    vpsHost = host,
                    myId = myId,
                    deviceId = prefsManager.deviceId,
                    city = detectedCity
                ) { _, _ -> }
            }
        }

        setContent {
            MeowlDigitalAppUI()
        }
    }

    private val voicemailReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.meowl.app.ACTION_NEW_VOICEMAIL_DOWNLOADED") {
                refreshVoicemailList()
                visualState = AppVisualState.NOTIFY
                Toast.makeText(this@MainActivity, "Pesan Voicemail Baru Masuk!", Toast.LENGTH_SHORT).show()
                MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "NOTIFY", "PESAN BARU", unreadCount)

                mainHandler.postDelayed({
                    if (visualState == AppVisualState.NOTIFY) {
                        visualState = AppVisualState.IDLE
                        MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                    }
                }, 4000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.meowl.app.ACTION_NEW_VOICEMAIL_DOWNLOADED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voicemailReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(voicemailReceiver, filter)
        }
        refreshVoicemailList()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(sensorListener)
        try {
            unregisterReceiver(voicemailReceiver)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        syncRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    /**
     * Periodic 5s Network Polling: Syncs incoming Pings and Voicemails from VPS
     */
    private fun startPeriodicVpsSync() {
        syncRunnable?.let { mainHandler.removeCallbacks(it) }
        syncRunnable = object : Runnable {
            override fun run() {
                val host = prefsManager.vpsServerHost
                val myId = prefsManager.myId

                // Always refresh local voicemail files to detect new background downloads immediately
                refreshVoicemailList()

                if (host.isNotEmpty() && myId.isNotEmpty() && visualState == AppVisualState.IDLE) {
                    // 1. Check for incoming Heart Pings
                    NetworkRelay.checkPing(host, myId) { hasPing ->
                        if (hasPing) {
                            visualState = AppVisualState.PING
                            Toast.makeText(this@MainActivity, "Menerima Ping Hati!", Toast.LENGTH_SHORT).show()
                            MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "PING", "PING RECEIVED!", unreadCount)
                            
                            mainHandler.postDelayed({
                                visualState = AppVisualState.IDLE
                                MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                            }, 3000)
                        }
                    }

                    // 2. Check for incoming Voicemails from VPS
                    NetworkRelay.checkIncomingFiles(host, myId) { remoteFiles ->
                        if (remoteFiles.isNotEmpty()) {
                            val localFolder = File(filesDir, "voicemails").apply { if (!exists()) mkdirs() }

                            for (remoteFile in remoteFiles) {
                                val cleanName = remoteFile.replace(Regex("^(baru_|kirim_|lama_|fav_)+"), "")
                                val existingMatch = localFolder.listFiles()?.any { it.name.endsWith(cleanName) } ?: false

                                if (!existingMatch) {
                                    val targetName = "baru_${System.currentTimeMillis()}_${cleanName}"
                                    val destFile = File(localFolder, targetName)
                                    NetworkRelay.downloadAudioFile(host, myId, remoteFile, destFile) { success ->
                                        if (success && destFile.length() > 0L) {
                                            refreshVoicemailList()
                                            
                                            // Trigger NOTIFY OLED state for 4 seconds
                                            visualState = AppVisualState.NOTIFY
                                            Toast.makeText(this@MainActivity, "Pesan Voicemail Baru Masuk!", Toast.LENGTH_SHORT).show()
                                            MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "NOTIFY", "NEW MESSAGE", unreadCount)

                                            mainHandler.postDelayed({
                                                visualState = AppVisualState.IDLE
                                                MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                                            }, 4000)
                                        } else if (destFile.exists()) {
                                            destFile.delete()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Check for incoming Partner Connection Requests
                    NetworkRelay.checkConnectRequest(host, myId) { hasReq, fromId ->
                        if (hasReq && !fromId.isNullOrEmpty()) {
                            incomingConnectRequesterId = fromId
                        }
                    }

                    // 4. Auto-Sync Partner's Timezone & City from VPS Server
                    val targetId = prefsManager.targetId
                    if (targetId.isNotEmpty()) {
                        NetworkRelay.fetchPartnerInfo(host, targetId) { partnerTz, partnerCity ->
                            if (!partnerTz.isNullOrEmpty() && !partnerCity.isNullOrEmpty()) {
                                prefsManager.partnerTimezone = partnerTz
                                prefsManager.partnerCity = partnerCity
                            }
                        }
                    }

                    // 4. Check if my connection request was accepted by partner
                    NetworkRelay.checkConnectStatus(host, myId) { connected, partnerId ->
                        if (connected && !partnerId.isNullOrEmpty()) {
                            Toast.makeText(this@MainActivity, "Koneksi diterima oleh $partnerId!", Toast.LENGTH_LONG).show()
                            if (prefsManager.targetId.isEmpty() || prefsManager.targetId != partnerId) {
                                prefsManager.targetId = partnerId
                                MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 5000)
            }
        }
        mainHandler.postDelayed(syncRunnable!!, 2000)
    }

    private fun refreshVoicemailList() {
        val folder = File(filesDir, "voicemails").apply { if (!exists()) mkdirs() }
        val files = folder.listFiles()?.filter { 
            (it.extension == "wav" || it.extension == "3gp") && !it.name.startsWith("kirim_")
        } ?: emptyList()
        voicemailFiles = files.sortedByDescending { it.lastModified() }
        unreadCount = files.count { it.name.startsWith("baru_") }
    }

    private fun cancelRecording() {
        if (visualState == AppVisualState.RECORDING) {
            val recordedFile = audioRecorder.stopRecording()
            recordedFile?.let { if (it.exists()) it.delete() }
            currentRecordingFile?.let { if (it.exists()) it.delete() }
            currentRecordingFile = null
            visualState = AppVisualState.IDLE
            refreshVoicemailList()
            Toast.makeText(this, "Rekaman dibatalkan", Toast.LENGTH_SHORT).show()
            MeowlCatWidgetProvider.updateAllWidgets(this, "IDLE", "ONLINE · READY", unreadCount)
        }
    }

    private fun toggleFavorite(file: File) {
        val parent = file.parentFile ?: return
        val newName = if (file.name.startsWith("fav_")) {
            file.name.replace("fav_", "lama_")
        } else {
            file.name.replace("baru_", "fav_").replace("lama_", "fav_")
        }
        val targetFile = File(parent, newName)
        if (file.renameTo(targetFile)) {
            refreshVoicemailList()
            val msg = if (targetFile.name.startsWith("fav_")) "Disimpan ke favorit" else "Batal favorit"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Delete Voicemail permanently from both Local Storage and VPS Server
     */
    private fun deleteVoicemail(file: File) {
        if (playingFilePath == file.absolutePath) {
            stopPlayback()
        }

        val originalName = file.name
            .replace("lama_", "baru_")
            .replace("fav_", "baru_")

        if (file.exists() && file.delete()) {
            refreshVoicemailList()
            Toast.makeText(this, "Voicemail dihapus", Toast.LENGTH_SHORT).show()

            NetworkRelay.deleteRemoteFile(prefsManager.vpsServerHost, prefsManager.myId, originalName) {}
            NetworkRelay.deleteRemoteFile(prefsManager.vpsServerHost, prefsManager.myId, file.name) {}
        }
    }

    private fun playSpecificVoicemail(file: File) {
        if (!file.exists() || file.length() <= 0L) {
            Toast.makeText(this, "File audio tidak valid", Toast.LENGTH_SHORT).show()
            return
        }

        val targetFile = if (file.name.startsWith("baru_")) {
            val parent = file.parentFile
            val tf = File(parent, file.name.replace("baru_", "lama_"))
            file.renameTo(tf)
            tf
        } else file

        playingFilePath = targetFile.absolutePath
        visualState = AppVisualState.PLAYING
        MeowlCatWidgetProvider.updateAllWidgets(this, "PLAYING", "PLAYING AUDIO", unreadCount)
        refreshVoicemailList()

        audioPlayer.playAudioFile(targetFile) {
            visualState = AppVisualState.IDLE
            playingFilePath = null
            refreshVoicemailList()
            MeowlCatWidgetProvider.updateAllWidgets(this, "IDLE", "ONLINE · READY", unreadCount)
        }
    }

    private fun playAllUnreadVoicemails() {
        val unreadFiles = voicemailFiles.filter { it.name.startsWith("baru_") }.sortedBy { it.lastModified() }
        if (unreadFiles.isEmpty()) {
            if (voicemailFiles.isNotEmpty()) playSpecificVoicemail(voicemailFiles.first())
            else Toast.makeText(this, "Tidak ada pesan", Toast.LENGTH_SHORT).show()
            return
        }
        playVoicemailQueue(unreadFiles, 0)
    }

    private fun playVoicemailQueue(files: List<File>, index: Int) {
        if (index >= files.size) {
            visualState = AppVisualState.IDLE
            playingFilePath = null
            refreshVoicemailList()
            MeowlCatWidgetProvider.updateAllWidgets(this, "IDLE", "ONLINE · READY", unreadCount)
            return
        }

        val file = files[index]
        if (!file.exists() || file.length() <= 0L) {
            playVoicemailQueue(files, index + 1)
            return
        }

        val targetFile = if (file.name.startsWith("baru_")) {
            val tf = File(file.parentFile, file.name.replace("baru_", "lama_"))
            file.renameTo(tf)
            tf
        } else file

        playingFilePath = targetFile.absolutePath
        visualState = AppVisualState.PLAYING
        MeowlCatWidgetProvider.updateAllWidgets(this, "PLAYING", "PLAYING AUDIO", unreadCount)
        refreshVoicemailList()

        audioPlayer.playAudioFile(targetFile) {
            // Only continue queue if still playing (not manually stopped/paused)
            if (visualState == AppVisualState.PLAYING) {
                playVoicemailQueue(files, index + 1)
            }
        }
    }

    private fun togglePausePlayback() {
        if (visualState == AppVisualState.PLAYING) {
            audioPlayer.pauseAudio()
            visualState = AppVisualState.PAUSED
            MeowlCatWidgetProvider.updateAllWidgets(this, "PAUSED", "AUDIO PAUSED", unreadCount)
        } else if (visualState == AppVisualState.PAUSED) {
            audioPlayer.resumeAudio()
            visualState = AppVisualState.PLAYING
            MeowlCatWidgetProvider.updateAllWidgets(this, "PLAYING", "PLAYING AUDIO", unreadCount)
        }
    }

    private fun stopPlayback() {
        audioPlayer.stopAudio()
        visualState = AppVisualState.IDLE
        playingFilePath = null
        refreshVoicemailList()
        MeowlCatWidgetProvider.updateAllWidgets(this, "IDLE", "ONLINE · READY", unreadCount)
    }

    private fun updateAppIcon(themeName: String) {
        val pm = packageManager
        val aliases = mapOf(
            "Blue" to "com.meowl.app.AliasBlue",
            "Purple" to "com.meowl.app.AliasPurple",
            "Yellow" to "com.meowl.app.AliasYellow"
        )
        
        val activeAlias = aliases[themeName]

        aliases.values.forEach { alias ->
            val state = if (alias == activeAlias) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            
            pm.setComponentEnabledSetting(
                ComponentName(this, alias),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun formatVoicemailTitle(file: File): String {
        return try {
            val digits = Regex("(\\d{10,13})").find(file.name)?.value
            if (digits != null) {
                var ts = digits.toLong()
                if (ts > 9999999999L) ts /= 1000L
                val sdf = java.text.SimpleDateFormat("dd MMM · HH:mm", java.util.Locale.getDefault())
                "Pesan Voice (" + sdf.format(java.util.Date(ts * 1000L)) + ")"
            } else {
                val sdf = java.text.SimpleDateFormat("dd MMM · HH:mm", java.util.Locale.getDefault())
                "Pesan Voice (" + sdf.format(java.util.Date(file.lastModified())) + ")"
            }
        } catch (e: Exception) {
            val sdf = java.text.SimpleDateFormat("dd MMM · HH:mm", java.util.Locale.getDefault())
            "Pesan Voice (" + sdf.format(java.util.Date(file.lastModified())) + ")"
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MeowlDigitalAppUI() {
        var myIdText by remember { mutableStateOf(prefsManager.myId) }
        var targetIdText by remember { mutableStateOf(prefsManager.targetId) }
        var vpsHostText by remember { mutableStateOf(prefsManager.vpsServerHost) }
        var gainSlider by remember { mutableStateOf(prefsManager.speakerGain.toFloat()) }
        var themeColor by remember { mutableStateOf(prefsManager.casingTheme) }
        var currentLangState by remember { mutableStateOf(prefsManager.appLanguage) }
        var isRefreshingState by remember { mutableStateOf(false) }

        var partnerCityText by remember { mutableStateOf(prefsManager.partnerCity) }
        var partnerTzText by remember { mutableStateOf(prefsManager.partnerTimezone) }
        var partnerLiveTime by remember { mutableStateOf("--:--") }

        LaunchedEffect(Unit) {
            while (isActive) {
                partnerCityText = prefsManager.partnerCity
                partnerTzText = prefsManager.partnerTimezone
                val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                sdf.timeZone = java.util.TimeZone.getTimeZone(partnerTzText)
                partnerLiveTime = sdf.format(java.util.Date())
                kotlinx.coroutines.delay(1000L)
            }
        }

        // Dynamic Color Tokens for Light Mode vs Dark Mode
        val appBgColor = if (isDarkModeState) Color(0xFF0D0F14) else Color(0xFFF3F4F6)
        val panelBgColor = if (isDarkModeState) Color(0xFF1A1E2E) else Color(0xFFFFFFFF)
        val textPrimaryColor = if (isDarkModeState) Color(0xFFF0F4FF) else Color(0xFF111827)
        val textMutedColor = if (isDarkModeState) Color(0xFF6B7280) else Color(0xFF4B5563)
        val cardBorderColor = if (isDarkModeState) Color(0x26FFFFFF) else Color(0x1F000000)

        val casingGradient = when (themeColor) {
            "Blue" -> listOf(Color(0xFFD6EAFF), Color(0xFFA0C4F0), Color(0xFF6090D0))
            "Purple" -> listOf(Color(0xFFEADBFF), Color(0xFFC4A0F0), Color(0xFF9060D0))
            "Yellow" -> listOf(Color(0xFFFFFAD6), Color(0xFFF0E4A0), Color(0xFFD0C060))
            else -> listOf(Color(0xFFFFD6E5), Color(0xFFF0A0C0), Color(0xFFD48090))
        }

        val logoColor = when (themeColor) {
            "Blue" -> Color(0xFF6090D0)
            "Purple" -> Color(0xFF9060D0)
            "Yellow" -> Color(0xFFD0C060)
            else -> Color(0xFFFF6EB4)
        }

        val buttonAccentColor = when (themeColor) {
            "Blue" -> Color(0xFF3C78B4)
            "Purple" -> Color(0xFF643CB4)
            "Yellow" -> Color(0xFFB4963C)
            else -> Color(0xFFB45064)
        }

        val infiniteTransition = rememberInfiniteTransition(label = "sim_anims")

        val leftEyeHeight by infiniteTransition.animateFloat(
            initialValue = 36f,
            targetValue = 36f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 16000
                    36f at 0
                    36f at 4500
                    4f at 4650   // Single blink
                    36f at 4800
                    36f at 8800  // Open gaze while right eye winks
                    36f at 9450
                    36f at 13500
                    4f at 13700  // LEFT EYE WINK! (Left eye winks, right stays open)
                    4f at 14000
                    36f at 14150
                    36f at 15000
                    4f at 15200  // Double blink 1
                    36f at 15350
                    4f at 15550  // Double blink 2
                    36f at 15700
                    36f at 16000
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "left_eye_anim"
        )

        val rightEyeHeight by infiniteTransition.animateFloat(
            initialValue = 36f,
            targetValue = 36f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 16000
                    36f at 0
                    36f at 4500
                    4f at 4650   // Single blink
                    36f at 4800
                    36f at 8800
                    4f at 9000   // RIGHT EYE WINK! (Right eye winks, left stays open)
                    4f at 9300
                    36f at 9450
                    36f at 13500 // Open gaze while left eye winks
                    36f at 14150
                    36f at 15000
                    4f at 15200  // Double blink 1
                    36f at 15350
                    4f at 15550  // Double blink 2
                    36f at 15700
                    36f at 16000
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "right_eye_anim"
        )

        val eyeGazeOffsetX by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 16000
                    0f at 0
                    0f at 2000
                    -5f at 2400   // Look Left!
                    -5f at 4200
                    0f at 4500    // Center
                    0f at 6200
                    5f at 6600    // Look Right!
                    5f at 8400
                    0f at 8700    // Center for Winks
                    0f at 16000
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "gaze_anim"
        )

        val viz1 by infiniteTransition.animateFloat(initialValue = 8f, targetValue = 24f, animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse), label = "v1")
        val viz2 by infiniteTransition.animateFloat(initialValue = 24f, targetValue = 10f, animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "v2")
        val viz3 by infiniteTransition.animateFloat(initialValue = 12f, targetValue = 30f, animationSpec = infiniteRepeatable(tween(250, easing = LinearEasing), RepeatMode.Reverse), label = "v3")
        val viz4 by infiniteTransition.animateFloat(initialValue = 26f, targetValue = 8f,  animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "v4")
        val viz5 by infiniteTransition.animateFloat(initialValue = 10f, targetValue = 22f, animationSpec = infiniteRepeatable(tween(300, easing = LinearEasing), RepeatMode.Reverse), label = "v5")

        val recBar1 by infiniteTransition.animateFloat(initialValue = 4f, targetValue = 14f, animationSpec = infiniteRepeatable(tween(300, easing = LinearEasing), RepeatMode.Reverse), label = "r1")
        val recBar2 by infiniteTransition.animateFloat(initialValue = 14f, targetValue = 6f,  animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "r2")
        val recBar3 by infiniteTransition.animateFloat(initialValue = 6f, targetValue = 18f,  animationSpec = infiniteRepeatable(tween(250, easing = LinearEasing), RepeatMode.Reverse), label = "r3")

        val heartScale by infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "heart_pulse"
        )

        var elapsedSeconds by remember { mutableStateOf(0) }

        LaunchedEffect(visualState) {
            if (visualState == AppVisualState.RECORDING) {
                elapsedSeconds = 0
                while (isActive) {
                    delay(1000L)
                    elapsedSeconds += 1
                }
            } else if (visualState == AppVisualState.PLAYING) {
                while (isActive) {
                    val posSec = audioPlayer.getCurrentPosition() / 1000
                    if (posSec >= 0) {
                        elapsedSeconds = posSec
                    }
                    delay(200L)
                }
            } else if (visualState == AppVisualState.PAUSED) {
                val posSec = audioPlayer.getCurrentPosition() / 1000
                if (posSec > 0) {
                    elapsedSeconds = posSec
                }
            } else {
                elapsedSeconds = 0
            }
        }

        val formattedTimer = String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = appBgColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "MEOWL",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = logoColor
                        )
                        Text(
                            text = "Aplikasi & Home Screen AppWidget",
                            fontSize = 10.sp,
                            color = textMutedColor
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Light / Dark Theme Switch Button (Custom Vector Sun/Moon Icon)
                        IconButton(
                            onClick = {
                                isDarkModeState = !isDarkModeState
                                prefsManager.isDarkMode = isDarkModeState
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(panelBgColor)
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isDarkModeState) R.drawable.ic_moon else R.drawable.ic_sun
                                ),
                                contentDescription = "Switch Theme",
                                tint = if (isDarkModeState) Color(0xFF00F5FF) else Color(0xFFFBBF24),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Settings Dialog Button
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(panelBgColor)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = "Settings",
                                tint = textPrimaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // DIGITAL CAT ENCLOSURE VISUAL
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Cat Ears Top
                    Row(
                        modifier = Modifier
                            .width(190.dp)
                            .offset(y = (-85).dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 38.dp, height = 38.dp)
                                .clip(GenericShape { size, _ ->
                                    moveTo(0f, size.height)
                                    lineTo(size.width / 2, 0f)
                                    lineTo(size.width, size.height)
                                    close()
                                })
                                .background(casingGradient[0])
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 38.dp, height = 38.dp)
                                .clip(GenericShape { size, _ ->
                                    moveTo(0f, size.height)
                                    lineTo(size.width / 2, 0f)
                                    lineTo(size.width, size.height)
                                    close()
                                })
                                .background(casingGradient[0])
                        )
                    }

                    // Main Squircle Cat Casing Box
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .height(165.dp)
                            .clip(RoundedCornerShape(48.dp))
                            .background(Brush.linearGradient(casingGradient))
                            .border(
                                3.dp,
                                if (visualState == AppVisualState.PING) Color(0xFFFF6EB4) else cardBorderColor,
                                RoundedCornerShape(48.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // LEFT CAT WHISKERS
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = (-8).dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 28.dp, height = 2.dp)
                                    .rotate(8f)
                                    .background(Color(0x80FFFFFF), RoundedCornerShape(2.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .size(width = 24.dp, height = 2.dp)
                                    .rotate(-5f)
                                    .background(Color(0x80FFFFFF), RoundedCornerShape(2.dp))
                            )
                        }

                        // RIGHT CAT WHISKERS
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 28.dp, height = 2.dp)
                                    .rotate(-8f)
                                    .background(Color(0x80FFFFFF), RoundedCornerShape(2.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .size(width = 24.dp, height = 2.dp)
                                    .rotate(5f)
                                    .background(Color(0x80FFFFFF), RoundedCornerShape(2.dp))
                            )
                        }

                        // Side PTT Paw Button
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 10.dp)
                                .width(20.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                                .background(casingGradient[2]),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_paw),
                                contentDescription = "PTT Paw",
                                tint = Color.White,
                                modifier = Modifier.size(10.dp)
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // OLED Screen
                            Box(
                                modifier = Modifier
                                    .width(135.dp)
                                    .height(68.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black)
                                    .border(2.dp, Color(0xFF1A1A1A), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                // Top Overlay Header: Partner City, Distance KM & Timezone Clock
                                val distanceKm = LdrLocationHelper.calculateDistanceKm(CityLocationHelper.currentCityName, partnerCityText)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                        .align(Alignment.TopCenter),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (enableDistanceCounterState && distanceKm > 0) "${distanceKm}KM • ${partnerCityText.uppercase(java.util.Locale.getDefault())}" else partnerCityText.uppercase(java.util.Locale.getDefault()),
                                        color = Color(0xFF00F5FF).copy(alpha = 0.75f),
                                        fontSize = 6.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.3.sp
                                    )
                                    Text(
                                        text = partnerLiveTime,
                                        color = Color(0xFF00F5FF).copy(alpha = 0.75f),
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                when (visualState) {
                                    AppVisualState.IDLE -> {
                                        Row(
                                            modifier = Modifier.offset(
                                                x = (eyeGazeOffsetX + shakeOffsetX).dp,
                                                y = shakeOffsetY.dp
                                            ),
                                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(width = 20.dp, height = leftEyeHeight.dp)
                                                    .background(Color(0xFF00F5FF), RoundedCornerShape(10.dp))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(width = 20.dp, height = rightEyeHeight.dp)
                                                    .background(Color(0xFF00F5FF), RoundedCornerShape(10.dp))
                                            )
                                        }
                                    }
                                    AppVisualState.RECORDING -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Box(modifier = Modifier.size(8.dp).background(Color(0xFFF87171), CircleShape))
                                                Text("REC $formattedTimer", color = Color(0xFFF87171), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                listOf(recBar1, recBar2, recBar3, recBar1, recBar2, recBar3, recBar1).forEach { h ->
                                                    Box(modifier = Modifier.size(width = 3.dp, height = h.dp).background(Color(0xFFF87171), RoundedCornerShape(2.dp)))
                                                }
                                            }
                                        }
                                    }
                                    AppVisualState.PLAYING -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(formattedTimer, color = Color(0xFF00F5FF), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(20.dp)) {
                                                Box(modifier = Modifier.size(width = 6.dp, height = viz1.dp).background(Color(0xFF00F5FF), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                                Box(modifier = Modifier.size(width = 6.dp, height = viz2.dp).background(Color(0xFF00F5FF), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                                Box(modifier = Modifier.size(width = 6.dp, height = viz3.dp).background(Color(0xFF00F5FF), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                                Box(modifier = Modifier.size(width = 6.dp, height = viz4.dp).background(Color(0xFF00F5FF), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                                Box(modifier = Modifier.size(width = 6.dp, height = viz5.dp).background(Color(0xFF00F5FF), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                                            }
                                        }
                                    }
                                    AppVisualState.PAUSED -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(formattedTimer, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Box(modifier = Modifier.size(width = 6.dp, height = 18.dp).background(Color(0xFFFBBF24), RoundedCornerShape(3.dp)))
                                                Box(modifier = Modifier.size(width = 6.dp, height = 18.dp).background(Color(0xFFFBBF24), RoundedCornerShape(3.dp)))
                                            }
                                        }
                                    }
                                    AppVisualState.PING -> {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_heart),
                                            contentDescription = null,
                                            tint = Color(0xFFFF6EB4),
                                            modifier = Modifier.size((32 * heartScale).dp)
                                        )
                                    }
                                    AppVisualState.NOTIFY -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_history),
                                                contentDescription = null,
                                                tint = Color(0xFFFBBF24),
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "$unreadCount PESAN BARU",
                                                color = Color(0xFFFBBF24),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    AppVisualState.DIZZY -> {
                                        val dizzyAngle by rememberInfiniteTransition(label = "dizzy").animateFloat(
                                            initialValue = 0f,
                                            targetValue = 360f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(350, easing = LinearEasing),
                                                repeatMode = RepeatMode.Restart
                                            ),
                                            label = "dizzyAngle"
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .rotate(dizzyAngle)
                                                    .background(Color(0xFF00F5FF), CircleShape)
                                                    .border(2.dp, Color.Black, CircleShape),
                                                contentAlignment = Alignment.TopCenter
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(Color.Black, CircleShape)
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .rotate(-dizzyAngle)
                                                    .background(Color(0xFF00F5FF), CircleShape)
                                                    .border(2.dp, Color.Black, CircleShape),
                                                contentAlignment = Alignment.BottomCenter
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(Color.Black, CircleShape)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // SINGLE MIDDLE BUTTON
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(buttonAccentColor.copy(alpha = 0.5f))
                                    .clickable {
                                        if (voicemailFiles.isNotEmpty()) playAllUnreadVoicemails()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("·", color = Color.White, fontSize = 9.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Speaker Grill Lines
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(7) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 2.dp, height = 10.dp)
                                            .background(buttonAccentColor.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                        }
                    }
                }

                // Dynamic Controls Row (NORMAL vs RECORDING vs PLAYING/PAUSED)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        visualState == AppVisualState.RECORDING -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val recordedFile = audioRecorder.stopRecording()
                                        currentRecordingFile = null
                                        refreshVoicemailList()

                                        if (recordedFile != null && recordedFile.exists()) {
                                            NetworkRelay.uploadAudioFile(prefsManager.vpsServerHost, prefsManager.targetId, recordedFile) { success ->
                                                if (success) {
                                                    Toast.makeText(this@MainActivity, "Pesan voicemail terkirim", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(this@MainActivity, "Gagal mengunggah ke VPS", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }

                                        MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "SENT", "SENT SUCCESS", unreadCount)
                                        
                                        mainHandler.postDelayed({
                                            visualState = AppVisualState.IDLE
                                            MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                                        }, 2500)
                                    },
                                    modifier = Modifier.weight(1.5f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF87171), contentColor = Color.White)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painter = painterResource(id = R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(12.dp))
                                        Text("STOP & SEND", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }

                                Button(
                                    onClick = { cancelRecording() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151), contentColor = Color.White)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painter = painterResource(id = R.drawable.ic_cancel), contentDescription = null, modifier = Modifier.size(12.dp))
                                        Text("BATAL", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        (visualState == AppVisualState.PLAYING || visualState == AppVisualState.PAUSED) -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { togglePausePlayback() },
                                    modifier = Modifier.weight(1.2f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24), contentColor = Color.Black)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painter = painterResource(id = R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(12.dp))
                                        Text(if (visualState == AppVisualState.PAUSED) "RESUME" else "PAUSE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }

                                Button(
                                    onClick = { stopPlayback() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151), contentColor = Color.White)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painter = painterResource(id = R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(12.dp))
                                        Text("STOP", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { if (voicemailFiles.isNotEmpty()) playAllUnreadVoicemails() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5FF), contentColor = Color.Black)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painter = painterResource(id = R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(12.dp))
                                        Text("PLAY RECENT", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        currentRecordingFile = audioRecorder.startRecording("kirim_${System.currentTimeMillis() / 1000}.wav")
                                        visualState = AppVisualState.RECORDING
                                        MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "RECORDING", "RECORDING...", unreadCount)
                                    },
                                    modifier = Modifier.weight(1.2f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF87171), contentColor = Color.White)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painter = painterResource(id = R.drawable.ic_paw), contentDescription = null, modifier = Modifier.size(12.dp))
                                        Text("REC VOICEMAIL", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        visualState = AppVisualState.PING
                                        NetworkRelay.sendPing(prefsManager.vpsServerHost, prefsManager.targetId) { success ->
                                            if (success) {
                                                Toast.makeText(this@MainActivity, "Ping terkirim", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Gagal mengirim ping ke VPS", Toast.LENGTH_SHORT).show()
                                            }
                                        }

                                        MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "PING", "PING SENT!", unreadCount)
                                        
                                        mainHandler.postDelayed({
                                            visualState = AppVisualState.IDLE
                                            MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                                        }, 3000)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6EB4))
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painter = painterResource(id = R.drawable.ic_heart), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        Text("PING HATI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        visualState = AppVisualState.IDLE
                                        refreshVoicemailList()
                                        MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                                        Toast.makeText(this@MainActivity, "Widget diperbarui", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1.1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = panelBgColor)
                                ) {
                                    Text("SYNC WIDGET", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                // INCOMING VOICEMAIL HISTORY & FAVORITES LIST PANEL
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(panelBgColor)
                        .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_history),
                                contentDescription = null,
                                tint = textMutedColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (currentLangState == "EN") "INBOX (${voicemailFiles.size})" else "PESAN MASUK (${voicemailFiles.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = textMutedColor
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isRefreshingState) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Color(0xFF00F5FF),
                                    strokeWidth = 2.dp
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (!isRefreshingState) {
                                        isRefreshingState = true
                                        refreshVoicemailList()
                                        val host = prefsManager.vpsServerHost
                                        val myId = prefsManager.myId
                                        if (host.isNotEmpty() && myId.isNotEmpty()) {
                                            NetworkRelay.checkIncomingFiles(host, myId) { remoteFiles ->
                                                if (remoteFiles.isNotEmpty()) {
                                                    val voicemailDir = File(filesDir, "voicemails")
                                                    for (remoteFile in remoteFiles) {
                                                        val cleanName = remoteFile.replace(Regex("^(baru_|kirim_|lama_|fav_)+"), "")
                                                        val targetName = "baru_${System.currentTimeMillis()}_${cleanName}"
                                                        val destFile = File(voicemailDir, targetName)
                                                        if (!destFile.exists()) {
                                                            NetworkRelay.downloadAudioFile(host, myId, remoteFile, destFile) { success ->
                                                                if (success && destFile.length() > 0L) {
                                                                    refreshVoicemailList()
                                                                } else if (destFile.exists()) {
                                                                    destFile.delete()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                mainHandler.postDelayed({ isRefreshingState = false }, 1200)
                                            }
                                        } else {
                                            mainHandler.postDelayed({ isRefreshingState = false }, 800)
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_refresh),
                                        contentDescription = "Refresh",
                                        tint = if (isRefreshingState) Color(0xFF00F5FF) else Color(0xFFFF6EB4),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (isRefreshingState) "Syncing..." else "Refresh",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRefreshingState) Color(0xFF00F5FF) else Color(0xFFFF6EB4)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (voicemailFiles.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (currentLangState == "EN") "No Voicemail Messages" else "Belum Ada Pesan Masuk", color = textMutedColor, fontSize = 11.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                            ) {
                                items(voicemailFiles) { file ->
                                    val isFav = file.name.startsWith("fav_")
                                    val isNew = file.name.startsWith("baru_")
                                    val isPlayingThis = (playingFilePath == file.absolutePath)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                when {
                                                    isFav -> Color(0x33FF6EB4)
                                                    isNew -> Color(0x33FBBF24)
                                                    else -> if (isDarkModeState) Color(0x0FFFFFF) else Color(0xFFF3F4F6)
                                                }
                                            )
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                val badgeText = when {
                                                    isFav -> "FAVORIT"
                                                    isNew -> "BARU"
                                                    else -> "DIBACA"
                                                }
                                                val badgeColor = when {
                                                    isFav -> Color(0xFFFF6EB4)
                                                    isNew -> Color(0xFFFBBF24)
                                                    else -> textMutedColor
                                                }
                                                Text(
                                                    text = badgeText,
                                                    color = badgeColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )

                                                val formattedTitle = formatVoicemailTitle(file)
                                                Text(formattedTitle, color = textPrimaryColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                            }
                                        }

                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = { playSpecificVoicemail(file) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_play),
                                                contentDescription = "Play",
                                                tint = if (isPlayingThis) Color(0xFF00F5FF) else textPrimaryColor,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { toggleFavorite(file) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_star),
                                                contentDescription = "Favorite",
                                                tint = if (isFav) Color(0xFFFF6EB4) else textMutedColor,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { deleteVoicemail(file) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_trash),
                                                contentDescription = "Delete",
                                                tint = Color(0xFFF87171),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }

                // Settings Dialog
                if (showSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text(if (currentLangState == "EN") "Meowl App Settings" else "Opsi Pengaturan Meowl", color = textPrimaryColor, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = myIdText,
                                    onValueChange = { myIdText = it },
                                    label = { Text("My Device ID") },
                                    placeholder = { Text("Isikan ID Anda") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = targetIdText,
                                    onValueChange = { targetIdText = it },
                                    label = { Text("Target Partner ID") },
                                    placeholder = { Text("Isikan ID Tujuan") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = vpsHostText,
                                    onValueChange = { vpsHostText = it },
                                    label = { Text("VPS Server Address") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Mode Tampilan:", color = textMutedColor, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilterChip(
                                            selected = isDarkModeState,
                                            onClick = {
                                                isDarkModeState = true
                                                prefsManager.isDarkMode = true
                                            },
                                            label = { Text("Gelap", fontSize = 10.sp) }
                                        )
                                        FilterChip(
                                            selected = !isDarkModeState,
                                            onClick = {
                                                isDarkModeState = false
                                                prefsManager.isDarkMode = false
                                            },
                                            label = { Text("Terang", fontSize = 10.sp) }
                                        )
                                    }
                                }

                                Text("Volume Speaker Gain: ${gainSlider.toInt()}%", color = textMutedColor, fontSize = 12.sp)
                                Slider(
                                    value = gainSlider,
                                    onValueChange = { gainSlider = it },
                                    valueRange = 10f..100f
                                )
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(if (currentLangState == "EN") "Casing Theme:" else "Tema Casing:", color = textMutedColor, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf("Pink", "Blue", "Purple", "Yellow").forEach { color ->
                                            FilterChip(
                                                selected = (themeColor == color),
                                                onClick = { themeColor = color },
                                                label = { Text(color, fontSize = 9.sp) }
                                            )
                                        }
                                    }
                                }

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(if (currentLangState == "EN") "App Language / Bahasa:" else "Bahasa Aplikasi / Language:", color = textMutedColor, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        FilterChip(
                                            selected = (currentLangState == "ID"),
                                            onClick = { currentLangState = "ID" },
                                            label = { Text("ID - Indonesia", fontSize = 10.sp) }
                                        )
                                        FilterChip(
                                            selected = (currentLangState == "EN"),
                                            onClick = { currentLangState = "EN" },
                                            label = { Text("EN - English", fontSize = 10.sp) }
                                        )
                                    }
                                }

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(if (currentLangState == "EN") "Partner City & Timezone:" else "Kota & Timezone Partner:", color = textMutedColor, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = partnerCityText,
                                        onValueChange = { partnerCityText = it },
                                        label = { Text(if (currentLangState == "EN") "Partner City Name" else "Nama Kota Partner") },
                                        placeholder = { Text("TOKYO / JAKARTA") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        val tzPresets = listOf(
                                            "JKT" to "Asia/Jakarta",
                                            "TKY" to "Asia/Tokyo",
                                            "LDN" to "Europe/London",
                                            "NYC" to "America/New_York",
                                            "SGP" to "Asia/Singapore"
                                        )
                                        tzPresets.forEach { (label, tz) ->
                                            FilterChip(
                                                selected = (partnerTzText == tz),
                                                onClick = {
                                                    partnerTzText = tz
                                                    partnerCityText = label
                                                },
                                                label = { Text(label, fontSize = 9.sp) }
                                            )
                                        }
                                    }
                                }

                                // 💖 MODULAR LDR FEATURE SUITE TOGGLES
                                HorizontalDivider(color = textMutedColor.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
                                Text(if (currentLangState == "EN") "💖 LDR Special Features (Option Toggles)" else "💖 Fitur Spesial LDR (Sakelar Opsi)", color = Color(0xFFFF6EB4), fontWeight = FontWeight.Bold, fontSize = 12.sp)

                                // 1. Distance Counter & Meetup Countdown
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("✈️ Hitung Jarak (KM) & Meetup", color = textPrimaryColor, fontSize = 11.sp)
                                    Switch(checked = enableDistanceCounterState, onCheckedChange = { enableDistanceCounterState = it })
                                }

                                // 2. Time Capsule Voicemail
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔒 Time Capsule Voicemail", color = textPrimaryColor, fontSize = 11.sp)
                                    Switch(checked = enableTimeCapsuleState, onCheckedChange = { enableTimeCapsuleState = it })
                                }

                                // 3. Heartbeat Hug Vibration
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("💓 Getaran Heartbeat Hug", color = textPrimaryColor, fontSize = 11.sp)
                                    Switch(checked = enableHeartbeatHugState, onCheckedChange = { enableHeartbeatHugState = it })
                                }

                                // 4. Shared Mood Indicator
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("😊 Shared Mood Indicator", color = textPrimaryColor, fontSize = 11.sp)
                                    Switch(checked = enableMoodIndicatorState, onCheckedChange = { enableMoodIndicatorState = it })
                                }

                                // Mood Selector
                                if (enableMoodIndicatorState) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val moods = listOf("HAPPY" to "😊", "BUSY" to "🤫", "NEED_HUGS" to "🤗", "MISS_YOU" to "💖", "SLEEPY" to "😴")
                                        moods.forEach { (mKey, mIcon) ->
                                            FilterChip(
                                                selected = (myCurrentMoodState == mKey),
                                                onClick = { myCurrentMoodState = mKey },
                                                label = { Text("$mIcon $mKey", fontSize = 8.sp) }
                                            )
                                        }
                                    }
                                }

                                // 5. Daily Love Prompt
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("💌 Pertanyaan Cinta Harian", color = textPrimaryColor, fontSize = 11.sp)
                                    Switch(checked = enableDailyPromptState, onCheckedChange = { enableDailyPromptState = it })
                                }

                                // 6. Quick Voice Reactions
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("🐱 Reaksi Suara Cepat", color = textPrimaryColor, fontSize = 11.sp)
                                    Switch(checked = enableVoiceReactionsState, onCheckedChange = { enableVoiceReactionsState = it })
                                }

                                // 7. Ambient Mood Light
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("🕯️ Ambient Mood Light", color = textPrimaryColor, fontSize = 11.sp)
                                    Switch(checked = enableMoodLightState, onCheckedChange = { enableMoodLightState = it })
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val cleanMyId = myIdText.trim()
                                    val cleanTargetId = targetIdText.trim()
                                    val host = vpsHostText.trim()

                                    // Save LDR Toggles
                                    prefsManager.enableDistanceCounter = enableDistanceCounterState
                                    prefsManager.enableTimeCapsule = enableTimeCapsuleState
                                    prefsManager.enableHeartbeatHug = enableHeartbeatHugState
                                    prefsManager.enableMoodIndicator = enableMoodIndicatorState
                                    prefsManager.myCurrentMood = myCurrentMoodState
                                    prefsManager.enableDailyPrompt = enableDailyPromptState
                                    prefsManager.enableVoiceReactions = enableVoiceReactionsState
                                    prefsManager.enableMoodLight = enableMoodLightState

                                    if (cleanMyId.isNotEmpty() && host.isNotEmpty()) {
                                        CityLocationHelper.detectLocalCity(this@MainActivity) { detectedCity ->
                                            NetworkRelay.registerId(host, cleanMyId, prefsManager.deviceId, city = detectedCity, mood = myCurrentMoodState) { success, errorMsg ->
                                                if (success) {
                                                    prefsManager.myId = cleanMyId
                                                    prefsManager.targetId = cleanTargetId
                                                    prefsManager.vpsServerHost = host
                                                    prefsManager.speakerGain = gainSlider.toInt()
                                                    prefsManager.casingTheme = themeColor
                                                    prefsManager.appLanguage = currentLangState
                                                    prefsManager.partnerCity = partnerCityText.trim()
                                                    prefsManager.partnerTimezone = partnerTzText.trim()
                                                    showSettingsDialog = false

                                                    updateAppIcon(themeColor)

                                                    if (cleanTargetId.isNotEmpty()) {
                                                        NetworkRelay.sendConnectRequest(host, cleanMyId, cleanTargetId) { reqSent ->
                                                            if (reqSent) {
                                                                val msg = if (currentLangState == "EN") "Connection request sent to $cleanTargetId" else "Permintaan koneksi dikirim ke $cleanTargetId"
                                                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    Toast.makeText(this@MainActivity, errorMsg ?: "ID sudah digunakan!", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    } else {
                                        prefsManager.myId = cleanMyId
                                        prefsManager.targetId = cleanTargetId
                                        prefsManager.vpsServerHost = host
                                        prefsManager.speakerGain = gainSlider.toInt()
                                        prefsManager.casingTheme = themeColor
                                        prefsManager.appLanguage = currentLangState
                                        showSettingsDialog = false

                                        updateAppIcon(themeColor)

                                        refreshVoicemailList()
                                        MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                                        val saveMsg = if (currentLangState == "EN") "Settings Saved!" else "Pengaturan tersimpan!"
                                        Toast.makeText(this@MainActivity, saveMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text(if (currentLangState == "EN") "Save" else "Simpan")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSettingsDialog = false }) {
                                Text(if (currentLangState == "EN") "Cancel" else "Batal", color = textMutedColor)
                            }
                        },
                        containerColor = panelBgColor
                    )
                }

                // Incoming Connection Request Acceptance Dialog
                if (incomingConnectRequesterId != null) {
                    val requesterId = incomingConnectRequesterId!!
                    AlertDialog(
                        onDismissRequest = { incomingConnectRequesterId = null },
                        title = { Text("Permintaan Koneksi Partner", fontWeight = FontWeight.Bold) },
                        text = { Text("Terima koneksi dari $requesterId?", fontSize = 14.sp) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    NetworkRelay.respondConnectRequest(prefsManager.vpsServerHost, prefsManager.myId, requesterId, accepted = true) { success ->
                                        if (success) {
                                            prefsManager.targetId = requesterId
                                            targetIdText = requesterId
                                            refreshVoicemailList()
                                            MeowlCatWidgetProvider.updateAllWidgets(this@MainActivity, "IDLE", "ONLINE · READY", unreadCount)
                                            Toast.makeText(this@MainActivity, "Berhasil terhubung dengan $requesterId!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    incomingConnectRequesterId = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ADE80), contentColor = Color.Black)
                            ) {
                                Text("Terima")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    NetworkRelay.respondConnectRequest(prefsManager.vpsServerHost, prefsManager.myId, requesterId, accepted = false) { }
                                    incomingConnectRequesterId = null
                                }
                            ) {
                                Text("Tolak", color = textMutedColor)
                            }
                        },
                        containerColor = panelBgColor
                    )
                }
            }
        }
    }
}
