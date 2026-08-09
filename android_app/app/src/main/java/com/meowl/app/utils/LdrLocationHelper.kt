package com.meowl.app.utils

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * LDR Location Helper: Calculates Haversine distance in KM between partner cities
 * and computes Meetup Countdown days.
 */
object LdrLocationHelper {

    private val CITY_COORDINATES = mapOf(
        "BANDUNG" to Pair(-6.9175, 107.6191),
        "JAKARTA" to Pair(-6.2088, 106.8456),
        "SURABAYA" to Pair(-7.2575, 112.7521),
        "YOKOHAMA" to Pair(35.4437, 139.6380),
        "TOKYO" to Pair(35.6762, 139.6503),
        "SEOUL" to Pair(37.5665, 126.9780),
        "SINGAPORE" to Pair(1.3521, 103.8198),
        "SYDNEY" to Pair(-33.8688, 151.2093),
        "LONDON" to Pair(51.5074, -0.1278),
        "OXFORD" to Pair(51.7520, -1.2577),
        "NEW YORK" to Pair(40.7128, -74.0060),
        "LOS ANGELES" to Pair(34.0522, -118.2437),
        "MALANG" to Pair(-7.9666, 112.6326),
        "JOGJA" to Pair(-7.7956, 110.3695),
        "SEMARANG" to Pair(-6.9666, 110.4167),
        "DENPASAR" to Pair(-8.6705, 115.2126),
        "MEDAN" to Pair(3.5952, 98.6722),
        "MAKASSAR" to Pair(-5.1477, 119.4327)
    )

    fun calculateDistanceKm(city1: String, city2: String): Int {
        val c1Clean = sanitizeCity(city1)
        val c2Clean = sanitizeCity(city2)

        val coord1 = CITY_COORDINATES[c1Clean] ?: Pair(-6.9175, 107.6191) // Default Bandung
        val coord2 = CITY_COORDINATES[c2Clean] ?: Pair(35.6762, 139.6503) // Default Tokyo

        return haversineKm(coord1.first, coord1.second, coord2.first, coord2.second)
    }

    fun getDaysUntilMeetup(targetMillis: Long): Int {
        if (targetMillis <= 0L) return -1
        val now = System.currentTimeMillis()
        val diff = targetMillis - now
        if (diff <= 0L) return 0
        return TimeUnit.MILLISECONDS.toDays(diff).toInt() + 1
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat lat1(lat1))
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (r * c).toInt()
    }

    private fun lat1(lat: Double): Double = lat

    private fun sanitizeCity(raw: String): String {
        return raw.uppercase(Locale.getDefault())
            .replace("KAB. ", "")
            .replace("KOTA ", "")
            .trim()
    }
}
