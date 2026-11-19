package com.boorce.carbu

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boorce.carbu.ui.CarbuTheme
import com.boorce.carbu.ui.MainScreen
import com.boorce.carbu.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarbuTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: CarbuViewModel = viewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                        vm.onLocationPermission(res.values.any { it })
                    }
                    val askLocation = {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                    when (state.screen) {
                        Screen.MAIN -> MainScreen(vm, state, onAskLocation = askLocation)
                        Screen.SETTINGS -> {
                            BackHandler(enabled = state.dataUrl != null) { vm.closeSettings() }
                            SettingsScreen(vm, state, onAskLocation = askLocation)
                        }
                    }
                }
            }
        }
    }
}
