package com.boorce.solar.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId

enum class HistoryRange(val days: Int, val label: String) {
    D30(30, "30 jours"),
    D90(90, "90 jours"),
    Y1(365, "12 mois"),
}

data class Kpis(val current: Current?, val today: DayResponse?, val summary: SummaryResponse?)

data class History(val bars: List<Bar>, val totalKwh: Double, val monthly: Boolean, val rows: List<DayTotal>)

/** Assemble les appels de l'API en données prêtes à afficher. */
class SolarRepository(private val api: SolarApi) {
    /** Fuseau du serveur (celui des heures affichées), appris des réponses. */
    @Volatile
    var zone: ZoneId = ZoneId.of("Europe/Paris")
        private set

    private fun learn(tz: String) {
        runCatching { ZoneId.of(tz) }.onSuccess { zone = it }
    }

    fun today(): LocalDate = LocalDate.now(zone)

    /** Les trois appels en parallèle ; n'échoue que si aucun n'a abouti. */
    suspend fun kpis(): Result<Kpis> = coroutineScope {
        val cur = async { runCatching { api.current() } }
        val day = async { runCatching { api.day() } }
        val sum = async { runCatching { api.summary() } }
        val (c, d, s) = Triple(cur.await(), day.await(), sum.await())
        d.getOrNull()?.let { learn(it.tz) }
        s.getOrNull()?.let { learn(it.tz) }
        if (c.isFailure && d.isFailure && s.isFailure) {
            Result.failure(c.exceptionOrNull() ?: ApiException.BadResponse())
        } else {
            Result.success(Kpis(c.getOrNull(), d.getOrNull(), s.getOrNull()))
        }
    }

    /** Un jour donné (null = aujourd'hui). Renvoie null quand le serveur n'a aucune donnée. */
    suspend fun day(date: LocalDate?): Result<DayResponse?> = runCatching {
        api.day(date)?.also { learn(it.tz) }
    }

    suspend fun history(range: HistoryRange): Result<History> = runCatching {
        val to = today()
        val from = to.minusDays(range.days - 1L)
        val resp = api.days(from, to)
        resp?.let { learn(it.tz) }
        val days = resp?.days.orEmpty()
        val monthly = range == HistoryRange.Y1
        val bars = if (monthly) monthlyBars(days) else dailyBars(from, to, days)
        History(bars = bars, totalKwh = bars.sumOf { it.value }, monthly = monthly, rows = days.asReversed())
    }
}
