package com.boorce.solar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boorce.solar.BuildConfig
import com.boorce.solar.core.SolarApi
import com.boorce.solar.core.normalizeBaseUrl
import kotlinx.coroutines.launch

/** Premier lancement : saisie de l'adresse du serveur solar2, vérifiée avant d'être enregistrée. */
@Composable
fun SetupScreen(onConfirmed: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val url = normalizeBaseUrl(input)
        when {
            url == null -> message = "Adresse invalide."
            url.startsWith("http://") && !BuildConfig.DEBUG -> message = "Android refuse les connexions non chiffrées : utilisez une adresse en https://."
            else -> {
                busy = true
                message = null
                scope.launch {
                    val ok = runCatching { SolarApi(url).health() }
                    busy = false
                    ok.onSuccess { healthy ->
                        if (healthy) onConfirmed(url)
                        else message = "Le serveur répond, mais ce n'est pas un serveur solar2."
                    }.onFailure { message = it.message ?: "Connexion impossible." }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Production solaire", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Saisissez l'adresse de votre serveur solar2.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; message = null },
            label = { Text("Adresse du serveur") },
            placeholder = { Text("solar.exemple.fr") },
            singleLine = true,
            isError = message != null,
            supportingText = if (message != null) { { Text(message.orEmpty()) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = ::submit, enabled = !busy && input.isNotBlank()) { Text("Se connecter") }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}
