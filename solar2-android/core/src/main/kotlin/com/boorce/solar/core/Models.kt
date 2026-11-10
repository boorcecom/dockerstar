package com.boorce.solar.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Dernière mesure de l'onduleur (`GET /api/v1/current`). */
@Serializable
data class Current(
    @SerialName("t") val at: Long,
    @SerialName("age_seconds") val ageSeconds: Long,
    val watts: Int,
    @SerialName("energy_today_kwh") val energyTodayKwh: Double = 0.0,
    @SerialName("energy_total_kwh") val energyTotalKwh: Double = 0.0,
    @SerialName("temperature_c") val temperatureC: Double = 0.0,
    @SerialName("sun_elevation_deg") val sunElevationDeg: Double? = null,
)

@Serializable
data class DaySummary(
    @SerialName("energy_kwh") val energyKwh: Double = 0.0,
    @SerialName("peak_w") val peakW: Int = 0,
    @SerialName("peak_at") val peakAt: Long? = null,
    @SerialName("first_at") val firstAt: Long? = null,
    @SerialName("last_at") val lastAt: Long? = null,
    @SerialName("sun_max_elevation_deg") val sunMaxElevationDeg: Double? = null,
)

/** Une mesure de puissance : instant (secondes unix UTC) et watts. */
data class Point(val t: Long, val watts: Int)

/** Courbe de puissance d'un jour local (`GET /api/v1/day`). */
@Serializable
data class DayResponse(
    val date: String,
    val tz: String,
    @SerialName("day_start") val dayStart: Long,
    @SerialName("day_end") val dayEnd: Long,
    val points: List<List<Long>> = emptyList(),
    val summary: DaySummary = DaySummary(),
) {
    fun toPoints(): List<Point> = points.filter { it.size >= 2 }.map { Point(it[0], it[1].toInt()) }
}

@Serializable
data class DayTotal(
    val date: String,
    @SerialName("energy_kwh") val energyKwh: Double,
    @SerialName("peak_w") val peakW: Int = 0,
)

@Serializable
data class DaysResponse(
    val tz: String,
    val from: String,
    val to: String,
    val days: List<DayTotal> = emptyList(),
)

@Serializable
data class SummaryResponse(
    val tz: String,
    @SerialName("today_kwh") val todayKwh: Double = 0.0,
    @SerialName("last7_kwh") val last7Kwh: Double = 0.0,
    @SerialName("last30_kwh") val last30Kwh: Double = 0.0,
    @SerialName("last365_kwh") val last365Kwh: Double = 0.0,
    @SerialName("total_kwh") val totalKwh: Double = 0.0,
)
