package com.boorce.solar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boorce.solar.core.Bar
import com.boorce.solar.core.DayResponse
import com.boorce.solar.core.Fmt
import com.boorce.solar.core.nearestIndex
import com.boorce.solar.core.niceScale
import com.boorce.solar.core.segments
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private class ChartColors(
    val series: Color,
    val grid: Color,
    val axis: Color,
    val text: Color,
    val surface: Color,
    val ink: Color,
)

@Composable
private fun chartColors(): ChartColors {
    val cs = MaterialTheme.colorScheme
    return ChartColors(cs.primary, cs.outlineVariant, cs.outline, cs.onSurfaceVariant, cs.surface, cs.onSurface)
}

/** Zone de tracé (en pixels) à l'intérieur des marges. */
private class Plot(val width: Float, val height: Float, val l: Float, val r: Float, val t: Float, val b: Float) {
    val iw: Float get() = width - l - r
    val ih: Float get() = height - t - b
}

private fun Density.plotFor(size: Size) =
    Plot(size.width, size.height, 46.dp.toPx(), 12.dp.toPx(), 22.dp.toPx(), 28.dp.toPx())

private val ChartHeight = 240.dp

@Composable
fun EmptyChart(text: String) {
    Box(Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/* ---------- axe vertical commun ---------- */

private fun DrawScope.drawYAxis(p: Plot, ticks: List<Double>, maxV: Double, c: ChartColors, m: TextMeasurer, fmt: (Double) -> String) {
    val style = TextStyle(fontSize = 11.sp, color = c.text)
    ticks.forEach { v ->
        val y = p.t + p.ih - (v / maxV).toFloat() * p.ih
        drawLine(if (v == 0.0) c.axis else c.grid, Offset(p.l, y), Offset(p.l + p.iw, y), strokeWidth = 1.dp.toPx())
        val tl = m.measure(fmt(v), style)
        drawText(tl, topLeft = Offset(p.l - 6.dp.toPx() - tl.size.width, y - tl.size.height / 2f))
    }
}

/** Bulle d'information : lignes de texte (la première en gras), placée près de x sans sortir du cadre. */
private fun DrawScope.drawTooltip(p: Plot, x: Float, lines: List<TextLayoutResult>, c: ChartColors) {
    val padH = 8.dp.toPx()
    val padV = 6.dp.toPx()
    val w = (lines.maxOfOrNull { it.size.width } ?: 0) + 2 * padH
    val h = lines.sumOf { it.size.height } + 2 * padV
    var left = x + 12.dp.toPx()
    if (left + w > p.width) left = x - 12.dp.toPx() - w
    left = max(0f, left)
    val top = 4.dp.toPx()
    val radius = CornerRadius(8.dp.toPx())
    drawRoundRect(c.surface, Offset(left, top), Size(w, h), radius)
    drawRoundRect(c.axis, Offset(left, top), Size(w, h), radius, style = Stroke(1.dp.toPx()))
    var y = top + padV
    lines.forEach {
        drawText(it, topLeft = Offset(left + padH, y))
        y += it.size.height
    }
}

/* ---------- courbe de puissance d'une journée ---------- */

@Composable
fun DayChart(day: DayResponse, zone: ZoneId, modifier: Modifier = Modifier) {
    val pts = remember(day) { day.toPoints() }
    if (pts.isEmpty()) {
        EmptyChart("Aucune mesure pour ce jour.")
        return
    }
    val c = chartColors()
    val measurer = rememberTextMeasurer()
    var sel by remember(day) { mutableIntStateOf(-1) }

    val peak = remember(pts) { pts.maxBy { it.watts } }
    val scale = remember(pts) { niceScale(peak.watts.toDouble()) }
    // domaine horaire : de la première à la dernière mesure, arrondi à l'heure, borné au jour
    val t0 = remember(day) { max(day.dayStart, Math.floorDiv(pts.first().t - 1800, 3600L) * 3600L) }
    val t1 = remember(day) { min(day.dayEnd, -Math.floorDiv(-(pts.last().t + 1800), 3600L) * 3600L) }
    val description = "Puissance au fil de la journée. Pic de ${Fmt.power(peak.watts)} à ${Fmt.time(peak.t, zone)}."

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .semantics { contentDescription = description }
            .pointerInput(pts, t0, t1) {
                awaitPointerEventScope {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            val p = plotFor(Size(size.width.toFloat(), size.height.toFloat()))
                            val t = t0 + ((change.position.x - p.l) / p.iw) * (t1 - t0)
                            sel = nearestIndex(pts, t.toDouble())
                        }
                    }
                }
            },
    ) {
        val p = plotFor(size)
        fun x(t: Long) = p.l + (t - t0).toFloat() / (t1 - t0) * p.iw
        fun y(w: Double) = p.t + p.ih - (w / scale.max).toFloat() * p.ih

        drawYAxis(p, scale.ticks(), scale.max, c, measurer) { Fmt.num(it) }

        // graduations horaires, assez espacées pour ne pas se chevaucher
        val tickStyle = TextStyle(fontSize = 11.sp, color = c.text)
        val hours = (t1 - t0) / 3600.0
        val stepH = listOf(1, 2, 3, 4, 6).firstOrNull { (hours / it) * 48.dp.toPx() <= p.iw } ?: 6
        var t = t0
        while (t <= t1 && t < day.dayEnd) { // 24:00 = 00:00 du lendemain : pas d'étiquette ambiguë
            val tl = measurer.measure(Fmt.time(t, zone), tickStyle)
            val tx = (x(t) - tl.size.width / 2f).coerceIn(0f, size.width - tl.size.width)
            drawText(tl, topLeft = Offset(tx, size.height - 6.dp.toPx() - tl.size.height))
            t += stepH * 3600L
        }

        // courbe + aplat léger ; un trou de plus de 20 min interrompt la ligne
        val base = y(0.0)
        segments(pts).forEach { seg ->
            val line = Path()
            val area = Path()
            seg.forEachIndexed { i, pt ->
                val px = x(pt.t)
                val py = y(pt.watts.toDouble())
                if (i == 0) {
                    line.moveTo(px, py)
                    area.moveTo(px, base)
                    area.lineTo(px, py)
                } else {
                    line.lineTo(px, py)
                    area.lineTo(px, py)
                }
            }
            area.lineTo(x(seg.last().t), base)
            area.close()
            drawPath(area, c.series.copy(alpha = 0.10f))
            drawPath(line, c.series, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // étiquette directe sur le pic (la seule)
        val peakLabel = measurer.measure(Fmt.power(peak.watts), TextStyle(fontSize = 11.sp, color = c.text))
        val px = x(peak.t)
        val labelLeft = when {
            px > size.width - 80.dp.toPx() -> px - peakLabel.size.width
            px < p.l + 60.dp.toPx() -> px
            else -> px - peakLabel.size.width / 2f
        }
        drawText(peakLabel, topLeft = Offset(max(0f, labelLeft), max(0f, y(peak.watts.toDouble()) - 8.dp.toPx() - peakLabel.size.height)))

        // curseur : trait vertical + point cerclé de la couleur de fond + bulle
        if (sel in pts.indices) {
            val pt = pts[sel]
            val sx = x(pt.t)
            drawLine(c.axis, Offset(sx, p.t), Offset(sx, p.t + p.ih), strokeWidth = 1.dp.toPx())
            drawCircle(c.surface, radius = 6.dp.toPx(), center = Offset(sx, y(pt.watts.toDouble())))
            drawCircle(c.series, radius = 4.dp.toPx(), center = Offset(sx, y(pt.watts.toDouble())))
            drawTooltip(
                p, sx,
                listOf(
                    measurer.measure(Fmt.power(pt.watts), TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)),
                    measurer.measure(Fmt.time(pt.t, zone), TextStyle(fontSize = 12.sp, color = c.text)),
                ),
                c,
            )
        }
    }
}

/* ---------- colonnes d'énergie (jours ou mois) ---------- */

@Composable
fun BarChart(bars: List<Bar>, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) {
        EmptyChart("Aucune donnée sur cette période.")
        return
    }
    val c = chartColors()
    val measurer = rememberTextMeasurer()
    var sel by remember(bars) { mutableIntStateOf(-1) }

    val scale = remember(bars) { niceScale(bars.maxOf { it.value }) }
    val best = remember(bars) { bars.indices.maxBy { bars[it].value } }
    val description = "Énergie produite, ${bars.size} colonnes. Meilleure : ${bars[best].full}, ${Fmt.num(bars[best].value, 1)} kWh."

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .semantics { contentDescription = description }
            .pointerInput(bars) {
                awaitPointerEventScope {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            val p = plotFor(Size(size.width.toFloat(), size.height.toFloat()))
                            val slot = p.iw / bars.size
                            sel = floor((change.position.x - p.l) / slot).toInt().coerceIn(0, bars.size - 1)
                        }
                    }
                }
            },
    ) {
        val p = plotFor(size)
        val slot = p.iw / bars.size
        val bw = (slot - 2.dp.toPx()).coerceIn(1.5f, 24.dp.toPx()) // ≤ 24 dp, 2 dp d'air entre les colonnes
        fun y(v: Double) = p.t + p.ih - (v / scale.max).toFloat() * p.ih

        drawYAxis(p, scale.ticks(), scale.max, c, measurer) { if (scale.step < 1) Fmt.num(it, 1) else Fmt.num(it) }

        val tickStyle = TextStyle(fontSize = 11.sp, color = c.text)
        val every = max(1, kotlin.math.ceil(52.dp.toPx() / slot).toInt())
        bars.forEachIndexed { i, b ->
            val left = p.l + i * slot + (slot - bw) / 2f
            if (i % every == 0) {
                val tl = measurer.measure(b.label, tickStyle)
                val tx = (left + bw / 2f - tl.size.width / 2f).coerceIn(0f, size.width - tl.size.width)
                drawText(tl, topLeft = Offset(tx, size.height - 8.dp.toPx() - tl.size.height))
            }
            if (b.value > 0) {
                val top = y(b.value)
                val bottom = y(0.0)
                val r = min(4.dp.toPx(), min(bw / 2f, bottom - top))
                // arrondi de 4 dp côté valeur, base carrée
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(left, top, left + bw, bottom),
                            topLeft = CornerRadius(r),
                            topRight = CornerRadius(r),
                            bottomRight = CornerRadius.Zero,
                            bottomLeft = CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(path, c.series, alpha = if (i == sel) 0.7f else 1f)
            }
        }

        // étiquette directe sur la meilleure colonne
        val bestLabel = measurer.measure(Fmt.num(bars[best].value, 1), TextStyle(fontSize = 11.sp, color = c.text))
        val bx = p.l + best * slot + slot / 2f
        val bl = (bx - bestLabel.size.width / 2f).coerceIn(0f, size.width - bestLabel.size.width)
        drawText(bestLabel, topLeft = Offset(bl, max(0f, y(bars[best].value) - 6.dp.toPx() - bestLabel.size.height)))

        if (sel in bars.indices) {
            val b = bars[sel]
            val lines = buildList {
                add(measurer.measure(Fmt.kwh(b.value, 1), TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)))
                add(measurer.measure(b.full, TextStyle(fontSize = 12.sp, color = c.text)))
                b.extra?.let { add(measurer.measure(it, TextStyle(fontSize = 12.sp, color = c.text))) }
            }
            drawTooltip(p, p.l + sel * slot + slot / 2f, lines, c)
        }
    }
}
