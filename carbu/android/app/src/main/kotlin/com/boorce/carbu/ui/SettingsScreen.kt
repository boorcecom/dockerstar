package com.boorce.carbu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boorce.carbu.CarbuViewModel
import com.boorce.carbu.PositionState
import com.boorce.carbu.UiState
import com.boorce.carbu.core.TripParams
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private fun num(v: Double) = java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols(Locale.FRANCE)).format(v)
private fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

private fun keyDateText(iso: String?): String? = iso?.let {
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.FRANCE).withZone(ZoneId.systemDefault()).format(Instant.parse(it))
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: CarbuViewModel, state: UiState, onAskLocation: () -> Unit) {
    var dataUrl by remember { mutableStateOf(state.dataUrl ?: "") }
    var keyUrl by remember { mutableStateOf(state.keyUrl ?: "") }
    var consumption by remember { mutableStateOf(num(state.trip.consumption)) }
    var tank by remember { mutableStateOf(num(state.trip.tank)) }
    var roadFactor by remember { mutableStateOf(num(state.trip.roadFactor)) }
    var roundTrip by remember { mutableStateOf(state.trip.roundTrip) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    if (state.dataUrl != null) IconButton(onClick = vm::closeSettings) { Icon(Icons.Default.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Serveur", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = dataUrl, onValueChange = { dataUrl = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("URL d'accès aux données") },
                placeholder = { Text("https://carburants.example.com") },
                supportingText = { Text("Adresse « classique » (HTTPS), joignable aussi depuis internet.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = keyUrl, onValueChange = { keyUrl = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("URL de gestion des clés (réseau local)") },
                placeholder = { Text("https://carburants.example.com:8443") },
                supportingText = { Text("N'est joignable que depuis votre réseau local.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            Text("Clé d'API", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.hasKey) "Clé enregistrée" + (keyDateText(state.keyDate)?.let { " le $it" } ?: "") +
                    " (stockée chiffrée sur ce téléphone). Elle autorise les corrections de prix."
                else "Aucune clé : l'affichage fonctionne, mais les corrections sont désactivées.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.syncKey(keyUrl) }, enabled = !state.sync.running) {
                    if (state.sync.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Synchro clé")
                }
                if (state.hasKey) TextButton(onClick = vm::forgetKey) { Text("Oublier la clé") }
            }
            state.sync.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.sync.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Text(
                "« Synchro clé » doit être lancée depuis le réseau local (Wi-Fi de la maison). La nouvelle clé remplace l'ancienne de ce téléphone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Estimation du coût", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = consumption, onValueChange = { consumption = it }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("Conso (L/100)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = tank, onValueChange = { tank = it }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("Plein (L)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            OutlinedTextField(
                value = roadFactor, onValueChange = { roadFactor = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Coefficient route / vol d'oiseau") },
                supportingText = { Text("La distance est mesurée à vol d'oiseau ; la route est plus longue (≈ 1,3).") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Aller-retour (sinon aller simple)")
                Switch(checked = roundTrip, onCheckedChange = { roundTrip = it })
            }

            HorizontalDivider()
            Text("Position", style = MaterialTheme.typography.titleMedium)
            Text(
                "La position sert uniquement à calculer les distances, sur ce téléphone : elle n'est jamais envoyée au serveur.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.positionState) {
                PositionState.OK -> Text("Localisation active.", style = MaterialTheme.typography.bodyMedium)
                else -> OutlinedButton(onClick = onAskLocation) { Text("Autoriser la localisation") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val c = parseNum(consumption)
                    val t = parseNum(tank)
                    val r = parseNum(roadFactor)
                    error = when {
                        c == null || c !in 1.0..40.0 -> "Consommation invalide (1 à 40 L/100 km)."
                        t == null || t !in 1.0..200.0 -> "Volume du plein invalide (1 à 200 L)."
                        r == null || r !in 1.0..2.0 -> "Coefficient de route invalide (1,0 à 2,0)."
                        else -> vm.saveSettings(dataUrl, keyUrl, TripParams(c, t, r, roundTrip))
                    }
                },
            ) { Text("Enregistrer") }
        }
    }
}
