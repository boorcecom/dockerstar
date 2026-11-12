package com.boorce.solar.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.boorce.solar.SolarViewModel
import com.boorce.solar.Tab as ViewTab
import com.boorce.solar.UiState
import com.boorce.solar.core.HistoryRange
import com.boorce.solar.core.Fmt
import com.boorce.solar.core.Kpis
import com.boorce.solar.core.staleWarning
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val REFRESH_MS = 60_000L
private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun DashboardScreen(vm: SolarViewModel, state: UiState) {
    // Rafraîchissement toutes les 60 s, uniquement quand l'application est visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshLive()
                delay(REFRESH_MS)
            }
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    val kpis = state.kpis
    val warning = kpis?.current?.let { staleWarning(it) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(kpis, state.zone, onSettings = { showSettings = true }) }
        state.error?.let { msg -> item { Notice("⚠ $msg") } }
        warning?.let { msg -> item { Notice("⚠ $msg") } }
        item { KpiGrid(kpis, state.zone) }
        item { Tabs(state.tab, onSelect = vm::selectTab) }
        when (state.tab) {
            ViewTab.Day -> dayItems(vm, state)
            ViewTab.History -> historyItems(vm, state)
        }
        kpis?.summary?.let { s ->
            item {
                Text(
                    "30 jours : ${Fmt.num(s.last30Kwh)} kWh · 12 mois : ${Fmt.num(s.last365Kwh)} kWh · total : ${Fmt.num(s.totalKwh)} kWh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Serveur") },
            text = { Text(state.baseUrl ?: "") },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Fermer") } },
            dismissButton = {
                TextButton(onClick = { showSettings = false; vm.resetServer() }) { Text("Changer de serveur") }
            },
        )
    }
}

@Composable
private fun Header(kpis: Kpis?, zone: ZoneId, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Production solaire", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val c = kpis?.current
            Text(
                text = if (c != null) "Dernière mesure ${Fmt.ago(c.ageSeconds)}" else "Chargement…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Réglages du serveur") }
    }
}

@Composable
private fun Notice(text: String) {
    val (bg, fg) = warningColors()
    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/* ---------- tuiles ---------- */

@Composable
private fun KpiGrid(k: Kpis?, zone: ZoneId) {
    val cur = k?.current
    val day = k?.today
    val sum = day?.summary
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                "Puissance actuelle", cur?.let { Fmt.power(it.watts) } ?: "–",
                cur?.let { Fmt.time(it.at, zone) + " · " + Fmt.ago(it.ageSeconds) } ?: "",
                Modifier.weight(1f),
            )
            Tile(
                "Énergie aujourd'hui", sum?.let { Fmt.kwh(it.energyKwh) } ?: "–",
                k?.summary?.let { "7 derniers jours : ${Fmt.num(it.last7Kwh, 1)} kWh" } ?: "",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                "Pic du jour", if (sum?.peakAt != null) Fmt.power(sum.peakW) else "–",
                sum?.peakAt?.let { "à " + Fmt.time(it, zone) } ?: "pas encore de mesure",
                Modifier.weight(1f),
            )
            Tile(
                "Soleil max", sum?.sunMaxElevationDeg?.let { Fmt.num(it, 1) + "°" } ?: "–",
                "hauteur du jour", Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text(sub.ifEmpty { " " }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tabs(selected: ViewTab, onSelect: (ViewTab) -> Unit) {
    TabRow(selectedTabIndex = selected.ordinal, containerColor = Color.Transparent) {
        Tab(selected = selected == ViewTab.Day, onClick = { onSelect(ViewTab.Day) }, text = { Text("Jour") })
        Tab(selected = selected == ViewTab.History, onClick = { onSelect(ViewTab.History) }, text = { Text("Historique") })
    }
}

/* ---------- vue « Jour » ---------- */

private val DateButtonFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

private fun LazyListScope.dayItems(vm: SolarViewModel, state: UiState) {
    item {
        SectionCard {
            val context = LocalContext.current
            val shown = state.date ?: LocalDate.now(state.zone)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.shiftDay(-1) }) { Text("‹") }
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d -> vm.selectDate(LocalDate.of(y, m + 1, d)) }, shown.year, shown.monthValue - 1, shown.dayOfMonth)
                            .apply { datePicker.maxDate = System.currentTimeMillis() }
                            .show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(shown.format(DateButtonFormat)) }
                OutlinedButton(onClick = { vm.shiftDay(1) }, enabled = state.date != null) { Text("›") }
            }
            if (state.date != null) {
                TextButton(onClick = { vm.selectDate(null) }) { Text("Aujourd'hui") }
            }
            val day = state.day
            Text(
                if (state.date == null) "Puissance aujourd'hui" else "Puissance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            val sub = if (day != null) {
                val s = day.summary
                Fmt.dateLong(LocalDate.parse(day.date)) +
                    (s.peakAt?.let { " · ${Fmt.kwh(s.energyKwh)} · pic ${Fmt.power(s.peakW)} à ${Fmt.time(it, state.zone)}" } ?: "")
            } else Fmt.dateLong(shown)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.dayLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            if (day != null) {
                DayChart(day, state.zone)
                if (day.points.isNotEmpty()) {
                    DataTable(
                        headers = listOf("Heure", "Puissance (W)"),
                        rows = day.toPoints().map { listOf(Fmt.time(it.t, state.zone), Fmt.num(it.watts.toDouble())) },
                    )
                }
            } else if (!state.dayLoading) {
                EmptyChart("Aucune mesure pour ce jour.")
            }
        }
    }
}

/* ---------- vue « Historique » ---------- */

private fun LazyListScope.historyItems(vm: SolarViewModel, state: UiState) {
    item {
        SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { r ->
                    FilterChip(selected = state.range == r, onClick = { vm.selectRange(r) }, label = { Text(r.label) })
                }
            }
            val h = state.history
            Text(
                "Énergie produite par " + if (state.range == HistoryRange.Y1) "mois" else "jour",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                if (h != null) "Total sur la période : ${Fmt.num(h.totalKwh)} kWh" else " ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            if (h != null) {
                BarChart(h.bars)
                if (h.monthly) {
                    DataTable(listOf("Mois", "Énergie (kWh)"), h.bars.map { listOf(it.full, Fmt.num(it.value, 1)) })
                } else {
                    DataTable(
                        listOf("Date", "Énergie (kWh)", "Pic (W)"),
                        h.rows.map { listOf(it.date, Fmt.num(it.energyKwh, 2, 2), Fmt.num(it.peakW.toDouble())) },
                    )
                }
            } else if (!state.historyLoading) {
                EmptyChart("Aucune donnée sur cette période.")
            }
        }
    }
}

/* ---------- briques communes ---------- */

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** Les mêmes valeurs que le graphique, accessibles sans toucher la courbe. */
@Composable
private fun DataTable(headers: List<String>, rows: List<List<String>>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Masquer les données" else "Voir les données (tableau)") }
    if (open) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            TableRow(headers, header = true)
            rows.forEach { TableRow(it) }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEachIndexed { i, text ->
            Text(
                text,
                modifier = Modifier.weight(1f),
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
