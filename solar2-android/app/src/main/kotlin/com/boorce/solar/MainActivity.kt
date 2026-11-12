package com.boorce.solar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boorce.solar.ui.DashboardScreen
import com.boorce.solar.ui.SetupScreen
import com.boorce.solar.ui.SolarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SolarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: SolarViewModel = viewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    if (state.baseUrl == null) {
                        SetupScreen(onConfirmed = vm::saveBaseUrl)
                    } else {
                        DashboardScreen(vm = vm, state = state)
                    }
                }
            }
        }
    }
}
