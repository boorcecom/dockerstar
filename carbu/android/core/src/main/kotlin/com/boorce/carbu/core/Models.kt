package com.boorce.carbu.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Modèles de l'API carbu (`GET /api/v1/prices`). Les champs inconnus sont ignorés. */
@Serializable
data class Origin(val name: String, val lat: Double, val lon: Double)

@Serializable
data class FuelInfo(val key: String, val label: String)

@Serializable
data class FuelView(
    val price: Double,
    val updated: String = "",
    @SerialName("age_hours") val ageHours: Double = 0.0,
    val reliability: Int = 0,
    val level: String = "low", // good | medium | low
    val origin: String = "source", // source | manual
) {
    val isManual get() = origin == "manual"
}

@Serializable
data class StationView(
    val id: String,
    val name: String,
    val town: String = "",
    val country: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerialName("distance_km") val distanceKm: Double? = null, // depuis l'origine du serveur (Le Doulieu)
    val prices: Map<String, FuelView> = emptyMap(),
) {
    val hasPosition get() = lat != 0.0 || lon != 0.0
}

@Serializable
data class SourceView(
    val label: String,
    val ok: Boolean = false,
    @SerialName("last_success") val lastSuccess: String? = null,
    @SerialName("next_run") val nextRun: String? = null,
)

@Serializable
data class PricesResponse(
    @SerialName("generated_at") val generatedAt: String = "",
    val origin: Origin,
    val fuels: List<FuelInfo> = emptyList(),
    val stations: List<StationView> = emptyList(),
    val best: Map<String, String> = emptyMap(),
    val sources: Map<String, SourceView> = emptyMap(),
)

/** Réponse de la gestion des clés. `key` n'est renvoyée qu'une fois : à stocker immédiatement. */
@Serializable
data class IssuedKey(val id: String, val name: String, val key: String)

@Serializable
internal data class IssueRequest(@SerialName("device_id") val deviceId: String, val name: String)

@Serializable
internal data class PutPricesRequest(val prices: Map<String, Double>)
