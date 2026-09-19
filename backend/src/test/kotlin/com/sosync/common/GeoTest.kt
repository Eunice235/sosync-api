package com.sosync.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GeoTest {

    @Test
    @DisplayName("distance between two known points is right to within a kilometre")
    fun distanceIsAccurate() {
        // Nairobi to Mombasa, roughly 440 km as a crow flies.
        val km = Geo.distanceKm(-1.2921, 36.8219, -4.0435, 39.6682)
        assertThat(km).isBetween(430.0, 450.0)
    }

    @Test
    @DisplayName("a short urban hop comes out in the right order of magnitude")
    fun shortDistancesAreSensible() {
        // The two points the demo uses for reporter and responder.
        val km = Geo.distanceKm(-1.2921, 36.8219, -1.2833, 36.8172)
        assertThat(km).isBetween(0.5, 2.0)
    }

    @Test
    @DisplayName("distance from a point to itself is zero")
    fun samePointIsZero() {
        assertThat(Geo.distanceKm(-1.2921, 36.8219, -1.2921, 36.8219)).isZero()
    }

    @Test
    @DisplayName("exactly (0, 0) is rejected, because it is a failed fix and not a place")
    fun nullIslandIsImplausible() {
        // (0, 0) is in the Gulf of Guinea and is almost always a device that failed to get a
        // fix and reported zeroes. Sending responders there would be worse than admitting
        // there is no location yet.
        assertThat(Geo.isPlausible(0.0, 0.0)).isFalse()
    }

    @Test
    @DisplayName("out-of-range coordinates are rejected")
    fun outOfRangeIsImplausible() {
        assertThat(Geo.isPlausible(91.0, 0.0)).isFalse()
        assertThat(Geo.isPlausible(-91.0, 0.0)).isFalse()
        assertThat(Geo.isPlausible(0.0, 181.0)).isFalse()
        assertThat(Geo.isPlausible(0.0, -181.0)).isFalse()
    }

    @Test
    @DisplayName("a missing coordinate is not plausible")
    fun nullsAreImplausible() {
        assertThat(Geo.isPlausible(null, 36.8219)).isFalse()
        assertThat(Geo.isPlausible(-1.2921, null)).isFalse()
        assertThat(Geo.isPlausible(null, null)).isFalse()
    }

    @Test
    @DisplayName("a real position is plausible, including one on a meridian")
    fun realPositionsArePlausible() {
        assertThat(Geo.isPlausible(-1.2921, 36.8219)).isTrue()
        // On the equator or the prime meridian, but not both: a genuine place.
        assertThat(Geo.isPlausible(0.0, 36.8219)).isTrue()
        assertThat(Geo.isPlausible(-1.2921, 0.0)).isTrue()
    }

    @Test
    @DisplayName("the maps link opens on any phone without SOSync installed")
    fun mapsLinkIsUniversal() {
        val link = Geo.mapsLink(-1.2921, 36.8219)
        assertThat(link).isEqualTo(
            "https://www.google.com/maps/search/?api=1&query=-1.2921,36.8219",
        )
    }

    @Test
    @DisplayName("displayed distance is rounded to one decimal place")
    fun roundedDistanceIsTidy() {
        val rounded = Geo.distanceKmRounded(-1.2921, 36.8219, -1.2833, 36.8172)
        assertThat(rounded.toString()).matches("\\d+\\.\\d")
    }
}
