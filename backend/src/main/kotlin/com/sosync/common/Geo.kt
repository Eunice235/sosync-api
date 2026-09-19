package com.sosync.common

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Straight-line distance between two coordinates.
 *
 * Haversine, computed in the application rather than in the database. PostGIS would be the
 * right answer for a national responder network, but the matching set here is the handful of
 * verified responders who are currently available, and adding a spatial extension to the
 * prototype buys nothing a demo can show.
 *
 * This is distance as a crow flies, not driving distance. It is used to decide who gets alerted
 * and to sort the responder list, never to promise an arrival time.
 */
object Geo {
    private const val EARTH_RADIUS_KM = 6371.0

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Distance rounded to one decimal place, for display. */
    fun distanceKmRounded(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        (distanceKm(lat1, lng1, lat2, lng2) * 10).roundToInt() / 10.0

    /**
     * Rejects coordinates outside the valid range, and the exact origin.
     *
     * (0, 0) is in the Gulf of Guinea and is almost always a device that failed to get a fix
     * and reported zeroes. Sending responders there would be worse than admitting there is no
     * location yet.
     */
    fun isPlausible(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false
        if (lat == 0.0 && lng == 0.0) return false
        return true
    }

    /** A link any responder can open, on any phone, without the app installed. */
    fun mapsLink(lat: Double, lng: Double): String =
        "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
}
