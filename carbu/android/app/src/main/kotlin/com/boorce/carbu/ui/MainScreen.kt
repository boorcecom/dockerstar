package com.boorce.carbu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boorce.carbu.CarbuViewModel
import com.boorce.carbu.PositionState
import com.boorce.carbu.SortMode
import com.boorce.carbu.UiState
import com.boorce.carbu.core.Costing
import com.boorce.carbu.core.FuelView
import com.boorce.carbu.core.GeoPoint
import com.boorce.carbu.core.StationView
import com.boorce.carbu.core.TripParams
import com.boorce.carbu.core.costing
import com.boorce.carbu.core.distanceTo
import com.boorce.carbu.core.formatAge
import com.boorce.carbu.core.formatEuro
import com.boorce.carbu.core.formatKm
import com.boorce.carbu.core.formatPrice
import com.boorce.carbu.core.levelLabel
import com.boorce.carbu.core.parsePriceInput

/** Une station prête à afficher pour le carburant choisi. */
private data class Row(val station: StationView, val price: FuelView?, val km: Double?, val cost: Costing?)

/** Distance utilisée pour les coûts : depuis la position du téléphone, sinon depuis l'origine du serveur. */
private fun distanceOf(st: StationView, position: GeoPoint?): Double? =
    (position?.let { distanceTo(st, it) }) ?: st.distanceKm

private fun buildRows(state: UiState): List<Row> {
    val data = state.data ?: return emptyList()
    val rows = data.stations.map { st ->
        val p = st.prices[state.fuel]
        val km = distanceOf(st, state.position)
        Row(st, p, km, if (p != null && km != null) costing(p.price, km, state.trip) else null)
    }
    val (withPrice, without) = rows.partition { it.price != null }
    val sorted = when (state.sort) {
        SortMode.PRICE -> withPrice.sortedBy { it.price!!.price }
        SortMode.TOTAL -> withPrice.sortedWith(compareBy({ it.cost?.total ?: Double.MAX_VALUE }, { it.price!!.price }))
        SortMode.DISTANCE -> withPrice.sortedBy { it.km ?: Double.MAX_VALUE }
    }
    return sorted + without.sortedBy { it.km ?: Double.MAX_VALUE }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: CarbuViewModel, state: UiState, onAskLocation: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Prix carburants") },
                actions = {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Actualiser") }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, contentDescription = "Paramètres") }
                },
            )
        },
    ) { pad ->
        val rows = buildRows(state)
        val bestTotal = rows.filter { it.cost != null && it.price!!.level != "low" }.minByOrNull { it.cost!!.total }?.station?.id
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { item { Notice(it) } }
            state.data?.sources?.values?.filter { !it.ok }?.takeIf { it.isNotEmpty() }?.let { bad ->
                item { Notice("Dernière mise à jour en échec pour : ${bad.joinToString { it.label }}. Les prix peuvent être anciens.") }
            }
            item { PositionBar(state, onAskLocation, vm::refreshPosition) }
            state.data?.let { data ->
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.fuels.forEach { f ->
                            FilterChip(selected = f.key == state.fuel, onClick = { vm.selectFuel(f.key) }, label = { Text(f.label) })
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SortMode.entries.forEach { m ->
                                FilterChip(selected = m == state.sort, onClick = { vm.setSort(m) }, label = { Text(m.label) })
                            }
                        }
                        Text(assumptions(state.trip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(rows, key = { it.station.id }) { r ->
                    StationCard(r, state, isBestTotal = r.station.id == bestTotal, onClick = { vm.startEdit(r.station) })
                }
                item {
                    Text(
                        "Fiabilité = ancienneté du prix déclaré par la source. Les coûts sont des estimations " +
                            "(distance à vol d'oiseau × coefficient de route). Touchez une station pour corriger un prix.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.editing?.let { st ->
            EditDialog(vm, st, state)
        }
    }
}

private fun assumptions(t: TripParams): String =
    "Plein de ${fmtNum(t.tank)} L · ${fmtNum(t.consumption)} L/100 km · " +
        (if (t.roundTrip) "aller-retour" else "aller simple") + " · route ≈ ×${fmtNum(t.roadFactor)}"

private fun fmtNum(v: Double): String = java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols(java.util.Locale.FRANCE)).format(v)

@Composable
private fun Notice(text: String) {
    val (bg, fg) = warningColors()
    Box(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PositionBar(state: UiState, onAsk: () -> Unit, onRefresh: () -> Unit) {
    val origin = state.data?.origin?.name ?: "l'origine du serveur"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (state.positionState) {
                    PositionState.OK -> "Distances depuis votre position"
                    PositionState.LOCATING -> "Localisation en cours…"
                    PositionState.DENIED -> "Position non autorisée : distances depuis $origin"
                    PositionState.UNAVAILABLE -> "Position introuvable : distances depuis $origin"
                    PositionState.UNKNOWN -> "Distances depuis $origin"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (state.positionState) {
            PositionState.OK, PositionState.LOCATING -> TextButton(onClick = onRefresh) { Text("Actualiser") }
            PositionState.UNAVAILABLE -> TextButton(onClick = onRefresh) { Text("Réessayer") }
            else -> TextButton(onClick = onAsk) { Text("Me localiser") }
        }
    }
}

@Composable
private fun StationCard(r: Row, state: UiState, isBestTotal: Boolean, onClick: () -> Unit) {
    val st = r.station
    val p = r.price
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (isBestTotal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountryBadge(st.country)
                        Spacer(Modifier.width(6.dp))
                        Text(st.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    }
                    val dist = r.km?.let { formatKm(it) + if (state.position != null) " de vous" else " du ${state.data?.origin?.name ?: "serveur"}" }
                    Text(dist ?: "Distance inconnue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p != null) {
                    Text(formatPrice(p.price) + " €/L", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("–", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (p == null) {
                Text(
                    if (st.country == "BE" && state.fuel == "e85") "E85 non vendu en Belgique" else "Prix non disponible — touchez pour le saisir",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Reliability(p)
                r.cost?.let { c ->
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CostLine("Plein de ${fmtNum(state.trip.tank)} L", formatEuro(c.fillCost))
                        CostLine(
                            "+ trajet (≈ ${formatKm(c.drivenKm)} ${if (state.trip.roundTrip) "A/R" else "aller"}, ${fmtNum(c.tripLiters)} L)",
                            "+ " + formatEuro(c.tripCost),
                        )
                        CostLine("Plein + trajet", formatEuro(c.total), bold = true)
                    }
                    if (isBestTotal) Text("Meilleur coût total", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                } ?: Text(
                    "Plein de ${fmtNum(state.trip.tank)} L : " + formatEuro(state.trip.tank * p.price),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CostLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Reliability(p: FuelView) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(levelColor(p.level), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            "${levelLabel(p.level)} · ${p.reliability} · ${formatAge(p.ageHours)}" + if (p.isManual) " · saisi à la main" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CountryBadge(country: String) {
    val be = country == "BE"
    Box(
        Modifier.background(if (be) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) { Text(country, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

// --- Correction manuelle ----------------------------------------------------------------

@Composable
private fun EditDialog(vm: CarbuViewModel, st: StationView, state: UiState) {
    val fuels = state.data?.fuels.orEmpty()
    val texts = remember(st.id) {
        mutableStateMapOf<String, String>().apply {
            fuels.forEach { f -> put(f.key, st.prices[f.key]?.let { formatPrice(it.price) } ?: "") }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!state.saving) vm.cancelEdit() },
        title = { Text(st.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.hasKey) {
                    Notice("Aucune clé d'API : faites « Synchro clé » dans les paramètres (depuis votre réseau local) pour pouvoir corriger.")
                }
                Text(
                    "Saisissez le prix vu à la pompe (€/L). Il sera remplacé automatiquement dès que la source publiera un prix plus récent.",
                    style = MaterialTheme.typography.bodySmall,
                )
                fuels.forEach { f ->
                    val cur = st.prices[f.key]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = texts[f.key] ?: "",
                            onValueChange = { texts[f.key] = it },
                            label = { Text(f.label) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            supportingText = if (cur?.isManual == true) ({ Text("saisi à la main") }) else null,
                            modifier = Modifier.weight(1f),
                        )
                        if (cur?.isManual == true) {
                            TextButton(enabled = !state.saving, onClick = { vm.removeManual(st.id, f.key) }) { Text("Retirer") }
                        }
                    }
                }
                state.editError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.hasKey && !state.saving,
                onClick = {
                    // Seuls les prix modifiés sont envoyés ; une saisie invalide est signalée sans appel réseau.
                    val changed = mutableMapOf<String, Double>()
                    for (f in fuels) {
                        val t = texts[f.key].orEmpty().trim()
                        val old = st.prices[f.key]?.let { formatPrice(it.price) } ?: ""
                        if (t.isEmpty() || t == old) continue
                        val v = parsePriceInput(t)
                        if (v == null) {
                            vm.reportEditError("Prix invalide pour ${f.label} (attendu entre 0,30 et 5,00 €/L).")
                            return@TextButton
                        }
                        changed[f.key] = v
                    }
                    vm.savePrices(st.id, changed)
                },
            ) { Text(if (state.saving) "Envoi…" else "Enregistrer") }
        },
        dismissButton = { TextButton(enabled = !state.saving, onClick = vm::cancelEdit) { Text("Fermer") } },
    )
}
