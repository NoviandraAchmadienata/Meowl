package com.meowl.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences Manager for Meowl App Settings, Hardcoded IP, and Theme Configuration.
 * Blank/empty ID form defaults so users can input their own IDs cleanly.
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meowl_prefs", Context.MODE_PRIVATE)

    // Blank default ID fields so forms start empty
    var myId: String
        get() = prefs.getString("my_id", "") ?: ""
        set(value) = prefs.edit().putString("my_id", value).apply()

    var targetId: String
        get() = prefs.getString("target_id", "") ?: ""
        set(value) = prefs.edit().putString("target_id", value).apply()

    // Default Hardcoded VPS Server Address
    var vpsServerHost: String
        get() = prefs.getString("vps_host", "http://103.112.163.154:3000") ?: "http://103.112.163.154:3000"
        set(value) = prefs.edit().putString("vps_host", value).apply()

    var vpsServerPort: Int
        get() = prefs.getInt("vps_port", 3000)
        set(value) = prefs.edit().putInt("vps_port", value).apply()

    var speakerGain: Int
        get() = prefs.getInt("speaker_gain", 80)
        set(value) = prefs.edit().putInt("speaker_gain", value).apply()

    var casingTheme: String
        get() = prefs.getString("casing_theme", "Pink") ?: "Pink"
        set(value) = prefs.edit().putString("casing_theme", value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("is_dark_mode", true)
        set(value) = prefs.edit().putBoolean("is_dark_mode", value).apply()

    var isEphemeralEnabled: Boolean
        get() = prefs.getBoolean("ephemeral_enabled", true)
        set(value) = prefs.edit().putBoolean("ephemeral_enabled", value).apply()

    var appLanguage: String
        get() = prefs.getString("app_language", "ID") ?: "ID"
        set(value) = prefs.edit().putString("app_language", value).apply()

    var partnerCity: String
        get() = prefs.getString("partner_city", "TOKYO") ?: "TOKYO"
        set(value) = prefs.edit().putString("partner_city", value).apply()

    var partnerTimezone: String
        get() = prefs.getString("partner_timezone", "Asia/Tokyo") ?: "Asia/Tokyo"
        set(value) = prefs.edit().putString("partner_timezone", value).apply()

    // Persistent Unique Device UUID for ID Ownership Registration
    val deviceId: String
        get() {
            var id = prefs.getString("device_id", "")
            if (id.isNullOrEmpty()) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            return id!!
        }

    // =============================================
    // MODULAR LDR FEATURE SUITE TOGGLES
    // =============================================
    var enableDistanceCounter: Boolean
        get() = prefs.getBoolean("ldr_distance_counter", true)
        set(value) = prefs.edit().putBoolean("ldr_distance_counter", value).apply()

    var meetupDateMillis: Long
        get() = prefs.getLong("ldr_meetup_date_millis", 0L)
        set(value) = prefs.edit().putLong("ldr_meetup_date_millis", value).apply()

    var enableTimeCapsule: Boolean
        get() = prefs.getBoolean("ldr_time_capsule", true)
        set(value) = prefs.edit().putBoolean("ldr_time_capsule", value).apply()

    var enableHeartbeatHug: Boolean
        get() = prefs.getBoolean("ldr_heartbeat_hug", true)
        set(value) = prefs.edit().putBoolean("ldr_heartbeat_hug", value).apply()

    var enableMoodIndicator: Boolean
        get() = prefs.getBoolean("ldr_mood_indicator", true)
        set(value) = prefs.edit().putBoolean("ldr_mood_indicator", value).apply()

    var myCurrentMood: String
        get() = prefs.getString("ldr_my_mood", "HAPPY") ?: "HAPPY"
        set(value) = prefs.edit().putString("ldr_my_mood", value).apply()

    var partnerCurrentMood: String
        get() = prefs.getString("ldr_partner_mood", "HAPPY") ?: "HAPPY"
        set(value) = prefs.edit().putString("ldr_partner_mood", value).apply()

    var enableDailyPrompt: Boolean
        get() = prefs.getBoolean("ldr_daily_prompt", true)
        set(value) = prefs.edit().putBoolean("ldr_daily_prompt", value).apply()

    var enableVoiceReactions: Boolean
        get() = prefs.getBoolean("ldr_voice_reactions", true)
        set(value) = prefs.edit().putBoolean("ldr_voice_reactions", value).apply()

    var enableMoodLight: Boolean
        get() = prefs.getBoolean("ldr_mood_light", true)
        set(value) = prefs.edit().putBoolean("ldr_mood_light", value).apply()
}
