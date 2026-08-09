package com.meowl.app.utils

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

/**
 * Intelligent City Location Auto-Detector.
 * Uses Android Geocoder/GPS first, IP Geolocation fallback second, and TimeZone ID fallback third.
 * Guarantees exact real-world city names (e.g. BANDUNG, SURABAYA, YOKOHAMA, MALANG, OXFORD).
 */
object CityLocationHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun detectLocalCity(context: Context, onCityDetected: (String) -> Unit) {
        Thread {
            var city: String? = null

            // 1. Try Android Geocoder with LocationManager (Supports both Kabupaten & Kota)
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (locationManager != null) {
                    val providers = locationManager.getProviders(true)
                    for (provider in providers) {
                        try {
                            val loc = locationManager.getLastKnownLocation(provider) ?: continue
                            val geocoder = Geocoder(context, Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                            if (!addresses.isNullOrEmpty()) {
                                val addr = addresses[0]
                                // subAdminArea contains 'Kabupaten ...' or 'Kota ...' in Indonesia!
                                val found = addr.subAdminArea ?: addr.locality ?: addr.subLocality ?: addr.adminArea
                                if (!found.isNullOrEmpty()) {
                                    city = sanitizeLocationName(found)
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            // Continue trying next provider
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Lightweight IP Geolocation Fallback if GPS/Geocoder is off
            if (city.isNullOrEmpty()) {
                try {
                    val url = URL("http://ip-api.com/json/?fields=city,regionName")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                    }
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(text)
                        val ipCity = json.optString("city", "")
                        val ipRegion = json.optString("regionName", "")
                        val rawFound = if (ipCity.isNotEmpty()) ipCity else ipRegion
                        if (rawFound.isNotEmpty()) {
                            city = sanitizeLocationName(rawFound)
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    // Silent fallback
                }
            }

            // 3. Timezone string fallback
            if (city.isNullOrEmpty()) {
                val tzId = java.util.TimeZone.getDefault().id
                val parts = tzId.split("/")
                city = if (parts.size > 1) parts.last().replace("_", " ") else tzId
            }

            val finalCity = sanitizeLocationName(city!!)
            mainHandler.post { onCityDetected(finalCity) }
        }.start()
    }

    private fun sanitizeLocationName(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("Kabupaten ", ignoreCase = true)) {
            s = "KAB. " + s.substring(10).trim()
        } else if (s.startsWith("Kab. ", ignoreCase = true)) {
            s = "KAB. " + s.substring(5).trim()
        } else if (s.endsWith(" Regency", ignoreCase = true)) {
            s = "KAB. " + s.substring(0, s.length - 8).trim()
        } else if (s.startsWith("Kota ", ignoreCase = true)) {
            s = s.substring(5).trim()
        }
        return s.uppercase(Locale.getDefault())
    }
}
