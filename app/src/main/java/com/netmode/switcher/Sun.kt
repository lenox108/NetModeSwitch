package com.netmode.switcher

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for a place the user typed in (the app asks for no location access): the
 * almanac algorithm, good to a minute or two, which is all "after sunset" needs.
 */
object Sun {

    /** What the sun does on a day: rises and sets, or stays up or down the whole day. */
    sealed class Day {
        data class Normal(val rise: ZonedDateTime, val set: ZonedDateTime) : Day()
        data object AlwaysUp : Day()
        data object AlwaysDown : Day()
    }

    private const val ZENITH = 90.833

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double) = ((v % m) + m) % m

    /** UTC hours of sunrise ([rising]) or sunset; +inf when the sun stays down, -inf when up. */
    private fun utcHours(date: LocalDate, lat: Double, lon: Double, rising: Boolean): Double {
        val n = date.dayOfYear
        val lngHour = lon / 15.0
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(ZENITH)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1) return Double.POSITIVE_INFINITY
        if (cosH < -1) return Double.NEGATIVE_INFINITY
        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        return norm(localT - lngHour, 24.0)
    }

    fun day(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Day {
        val rise = utcHours(date, lat, lon, rising = true)
        val set = utcHours(date, lat, lon, rising = false)
        if (rise == Double.POSITIVE_INFINITY) return Day.AlwaysDown
        if (rise == Double.NEGATIVE_INFINITY) return Day.AlwaysUp
        fun at(hours: Double): ZonedDateTime {
            var t = date.atStartOfDay(ZoneOffset.UTC).plusSeconds((hours * 3600).toLong()).withZoneSameInstant(zone)
            // UTC hours belong to the UTC date; far from Greenwich they can land on the next or
            // the previous local day.
            if (t.toLocalDate().isAfter(date)) t = t.minusDays(1)
            if (t.toLocalDate().isBefore(date)) t = t.plusDays(1)
            return t
        }
        return Day.Normal(at(rise), at(set))
    }

    /** Whether it is dark (between sunset and sunrise) at [now]. */
    fun dark(now: ZonedDateTime, lat: Double, lon: Double): Boolean = when (val d = day(now.toLocalDate(), lat, lon, now.zone)) {
        is Day.Normal -> now.isBefore(d.rise) || !now.isBefore(d.set)
        Day.AlwaysDown -> true
        Day.AlwaysUp -> false
    }

    /** The next sunrise or sunset after [now], within two days; null in a polar day or night. */
    fun nextEdge(now: ZonedDateTime, lat: Double, lon: Double): ZonedDateTime? =
        (0L..2L).flatMap { i ->
            when (val d = day(now.toLocalDate().plusDays(i), lat, lon, now.zone)) {
                is Day.Normal -> listOf(d.rise, d.set)
                else -> emptyList()
            }
        }.filter { it.isAfter(now) }.minOrNull()
}
