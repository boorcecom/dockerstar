package com.boorce.carbu.core

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hypothèses d'estimation d'un déplacement vers une station.
 *
 * - [consumption] : L/100 km du véhicule ;
 * - [tank] : volume d'un plein, en litres ;
 * - [roadFactor] : la distance est mesurée à vol d'oiseau ; la route est plus longue (≈ ×1,3) ;
 * - [roundTrip] : aller-retour (on revient chez soi) ou aller simple.
 */
data class TripParams(
    val consumption: Double = 7.0,
    val tank: Double = 45.0,
    val roadFactor: Double = 1.3,
    val roundTrip: Boolean = true,
) {
    /** Valeurs aberrantes ramenées dans des bornes raisonnables (saisie utilisateur). */
    fun sanitized() = TripParams(
        consumption = consumption.coerceIn(1.0, 40.0),
        tank = tank.coerceIn(1.0, 200.0),
        roadFactor = roadFactor.coerceIn(1.0, 2.0),
        roundTrip = roundTrip,
    )
}

/**
 * Coût d'un plein dans une station, avec et sans le déplacement.
 * Le carburant du trajet est valorisé au prix de CETTE station (celui qu'on achète).
 */
data class Costing(
    val straightKm: Double,
    val drivenKm: Double, // route estimée, aller-retour compris
    val tripLiters: Double,
    val fillCost: Double, // plein seul
    val tripCost: Double, // coût du déplacement
) {
    val total get() = fillCost + tripCost // plein + déplacement
}

fun costing(price: Double, straightKm: Double, p: TripParams = TripParams()): Costing {
    val q = p.sanitized()
    val driven = straightKm * q.roadFactor * (if (q.roundTrip) 2 else 1)
    val liters = driven * q.consumption / 100.0
    return Costing(
        straightKm = straightKm,
        drivenKm = driven,
        tripLiters = liters,
        fillCost = q.tank * price,
        tripCost = liters * price,
    )
}

/** Distance à vol d'oiseau (haversine), en km. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val rad = Math.PI / 180
    val dLat = (lat2 - lat1) * rad
    val dLon = (lon2 - lon1) * rad
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * rad) * cos(lat2 * rad) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a))
}

data class GeoPoint(val lat: Double, val lon: Double)

/** Distance d'une station depuis [from] ; null si la station n'a pas de coordonnées. */
fun distanceTo(station: StationView, from: GeoPoint): Double? =
    if (station.hasPosition) haversineKm(from.lat, from.lon, station.lat, station.lon) else null

// --- Formats d'affichage (fr-FR) --------------------------------------------------------

fun formatEuro(v: Double): String = String.format(Locale.FRANCE, "%.2f €", v)
fun formatPrice(v: Double): String = String.format(Locale.FRANCE, "%.3f", v)
fun formatKm(v: Double): String = String.format(Locale.FRANCE, "%.1f km", v)

fun formatAge(hours: Double): String = when {
    hours < 1 -> "il y a moins d'1 h"
    hours < 48 -> "il y a ${Math.round(hours)} h"
    else -> "il y a ${Math.round(hours / 24)} j"
}

fun levelLabel(level: String): String = when (level) {
    "good" -> "Fiable"
    "medium" -> "À vérifier"
    else -> "Ancien"
}

/** Lit un prix saisi (« 2,05 » ou « 2.05 ») ; null si invalide ou hors des bornes acceptées par le serveur. */
fun parsePriceInput(text: String): Double? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (v in 0.3..5.0) Math.round(v * 1000) / 1000.0 else null
}

/** Plus petit coût (plein + trajet) : sert à classer les stations. */
fun cheapestTotal(a: Costing?, b: Costing?): Int = compareValues(a?.total ?: Double.MAX_VALUE, b?.total ?: Double.MAX_VALUE)

