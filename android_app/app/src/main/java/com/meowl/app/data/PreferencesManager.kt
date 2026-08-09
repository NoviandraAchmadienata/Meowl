package com.meowl.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences Manager for Meowl App Settings & Configuration.
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meowl_prefs", Context.MODE_PRIVATE)

    var myId: String
        get() = prefs.getString("my_id", "andra_cat") ?: "andra_cat"
        set(value) = prefs.edit().putString("my_id", value).apply()

    var targetId: String
        get() = prefs.getString("target_id", "partner_cat") ?: "partner_cat"
        set(value) = prefs.edit().putString("target_id", value).apply()

    var vpsServerHost: String
        get() = prefs.getString("vps_host", "139.59.220.150") ?: "139.59.220.150"
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

    var isEphemeralEnabled: Boolean
        get() = prefs.getBoolean("ephemeral_enabled", true)
        set(value) = prefs.edit().putBoolean("ephemeral_enabled", value).apply()
}
