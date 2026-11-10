package com.boorce.solar.core

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats d'affichage (français). Les heures sont toujours celles du fuseau du serveur. */
object Fmt {
    private val locale: Locale = Locale.FRANCE

    fun num(v: Double, maxDecimals: Int = 0, minDecimals: Int = 0): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = minDecimals
            maximumFractionDigits = maxDecimals
        }.format(v)

    fun power(watts: Int): String =
        if (watts >= 1000) num(watts / 1000.0, 2, 2) + " kW" else num(watts.toDouble()) + " W"

    fun kwh(v: Double, decimals: Int = 2): String = num(v, decimals, decimals) + " kWh"

    fun time(epochSeconds: Long, tz: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(tz).format(Instant.ofEpochSecond(epochSeconds))

    fun dateLong(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale))

    fun dateShort(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("dd/MM", locale))

    fun monthShort(m: YearMonth): String = m.format(DateTimeFormatter.ofPattern("MMM", locale)).replace(".", "")

    fun monthLong(m: YearMonth): String = m.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))

    /** « à l'instant », « il y a 12 min », « il y a 3 h 05 », « il y a 2 j ». */
    fun ago(seconds: Long): String {
        if (seconds < 90) return "à l'instant"
        if (seconds >= 86400) return "il y a ${seconds / 86400} j"
        val minutes = Math.round(seconds / 60.0) // arrondi sur le total : jamais « 17 h 60 »
        return if (minutes < 60) "il y a $minutes min"
        else "il y a ${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    }
}
