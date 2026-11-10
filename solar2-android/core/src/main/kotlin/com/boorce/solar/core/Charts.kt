package com.boorce.solar.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Échelle « ronde » d'un axe : maximum et pas (1, 2, 5 ou 10 × 10^n). */
data class Scale(val max: Double, val step: Double) {
    fun ticks(): List<Double> {
        val out = ArrayList<Double>()
        var v = 0.0
        while (v <= max + 1e-9) {
            out += v
            v += step
        }
        return out
    }
}

fun niceScale(max: Double, target: Int = 4): Scale {
    if (!(max > 0)) return Scale(max = 1.0, step = 0.25)
    val raw = max / target
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val step = (if (norm <= 1) 1.0 else if (norm <= 2) 2.0 else if (norm <= 5) 5.0 else 10.0) * mag
    return Scale(max = ceil(max / step - 1e-9) * step, step = step)
}

/** Indice du point le plus proche de l'instant t (les points sont triés par instant croissant). */
fun nearestIndex(points: List<Point>, t: Double): Int {
    if (points.isEmpty()) return -1
    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].t < t) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && abs(points[lo - 1].t - t) < abs(points[lo].t - t)) lo--
    return lo
}

/** Découpe la courbe : un trou de plus de [maxGapSeconds] (onduleur éteint) interrompt la ligne. */
fun segments(points: List<Point>, maxGapSeconds: Long = 1200): List<List<Point>> {
    if (points.isEmpty()) return emptyList()
    val out = ArrayList<List<Point>>()
    var cur = ArrayList<Point>()
    points.forEachIndexed { i, p ->
        if (i > 0 && p.t - points[i - 1].t > maxGapSeconds) {
            out += cur
            cur = ArrayList()
        }
        cur += p
    }
    out += cur
    return out
}

/** Une colonne de l'histogramme d'énergie. */
data class Bar(val value: Double, val label: String, val full: String, val extra: String? = null)

/** Une colonne par jour de [from] à [to] inclus ; un jour sans mesure vaut 0. */
fun dailyBars(from: LocalDate, to: LocalDate, days: List<DayTotal>): List<Bar> {
    val byDate = days.associateBy { it.date }
    val out = ArrayList<Bar>()
    var d = from
    while (!d.isAfter(to)) {
        val e = byDate[d.toString()]
        out += Bar(
            value = e?.energyKwh ?: 0.0,
            label = Fmt.dateShort(d),
            full = Fmt.dateLong(d),
            extra = e?.let { "pic " + Fmt.power(it.peakW) } ?: "pas de mesure",
        )
        d = d.plusDays(1)
    }
    return out
}

/** Une colonne par mois (somme de l'énergie quotidienne), dans l'ordre chronologique. */
fun monthlyBars(days: List<DayTotal>): List<Bar> =
    days.groupBy { YearMonth.parse(it.date.take(7)) }
        .toSortedMap()
        .map { (m, list) ->
            Bar(value = list.sumOf { it.energyKwh }, label = Fmt.monthShort(m), full = Fmt.monthLong(m))
        }

/** Avertissement si l'onduleur devrait produire (soleil levé) mais que la dernière mesure est ancienne. */
fun staleWarning(c: Current, maxAgeSeconds: Long = 1800): String? =
    if ((c.sunElevationDeg ?: -90.0) > 5 && c.ageSeconds > maxAgeSeconds)
        "Pas de nouvelle mesure ${Fmt.ago(c.ageSeconds)} alors que le soleil est levé."
    else null
