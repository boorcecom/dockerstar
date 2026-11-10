package com.boorce.solar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boorce.solar.core.DayResponse
import com.boorce.solar.core.History
import com.boorce.solar.core.HistoryRange
import com.boorce.solar.core.Kpis
import com.boorce.solar.core.SolarApi
import com.boorce.solar.core.SolarRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class Tab { Day, History }

data class UiState(
    val baseUrl: String? = null,
    val zone: ZoneId = ZoneId.of("Europe/Paris"),
    val kpis: Kpis? = null,
    val tab: Tab = Tab.Day,
    val date: LocalDate? = null, // null = aujourd'hui
    val day: DayResponse? = null,
    val dayLoading: Boolean = false,
    val range: HistoryRange = HistoryRange.D30,
    val history: History? = null,
    val historyLoading: Boolean = false,
    val error: String? = null,
)

class SolarViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private var repo: SolarRepository? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var kpisJob: Job? = null
    private var dayJob: Job? = null
    private var historyJob: Job? = null

    init {
        settings.baseUrl?.let { connect(it) }
    }

    private fun connect(url: String) {
        // Une adresse enregistrée mais devenue invalide ramène à l'écran de configuration.
        repo = runCatching { SolarRepository(SolarApi(url)) }.getOrNull()
        _state.value = if (repo != null) UiState(baseUrl = url) else UiState()
    }

    fun saveBaseUrl(url: String) {
        settings.baseUrl = url
        connect(url)
    }

    fun resetServer() {
        kpisJob?.cancel(); dayJob?.cancel(); historyJob?.cancel()
        settings.baseUrl = null
        repo = null
        _state.value = UiState()
    }

    /** Appelé toutes les 60 s tant que l'application est visible. */
    fun refreshLive() {
        loadKpis()
        val s = _state.value
        if (s.tab == Tab.Day && s.date == null) loadDay()
        if (s.tab == Tab.History && s.history == null) loadHistory()
    }

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.Day) loadDay() else loadHistory()
    }

    /** null ou une date d'aujourd'hui/future = aujourd'hui. */
    fun selectDate(date: LocalDate?) {
        val today = repo?.today()
        val d = if (date == null || today == null || !date.isBefore(today)) null else date
        _state.update { it.copy(date = d) }
        loadDay()
    }

    fun shiftDay(delta: Long) {
        val today = repo?.today() ?: return
        selectDate((_state.value.date ?: today).plusDays(delta))
    }

    fun selectRange(range: HistoryRange) {
        _state.update { it.copy(range = range) }
        loadHistory()
    }

    private fun loadKpis() {
        val r = repo ?: return
        kpisJob?.cancel()
        kpisJob = viewModelScope.launch {
            r.kpis().fold(
                onSuccess = { k -> _state.update { it.copy(kpis = k, zone = r.zone, error = null) } },
                onFailure = { e -> _state.update { it.copy(error = e.message) } },
            )
        }
    }

    private fun loadDay() {
        val r = repo ?: return
        dayJob?.cancel() // une requête plus récente remplace la précédente (et annule l'appel réseau)
        _state.update { it.copy(dayLoading = true) }
        val date = _state.value.date
        dayJob = viewModelScope.launch {
            r.day(date).fold(
                onSuccess = { d -> _state.update { it.copy(day = d, dayLoading = false, zone = r.zone, error = null) } },
                onFailure = { e -> _state.update { it.copy(dayLoading = false, error = e.message) } },
            )
        }
    }

    private fun loadHistory() {
        val r = repo ?: return
        historyJob?.cancel()
        _state.update { it.copy(historyLoading = true) }
        val range = _state.value.range
        historyJob = viewModelScope.launch {
            r.history(range).fold(
                onSuccess = { h -> _state.update { it.copy(history = h, historyLoading = false, zone = r.zone, error = null) } },
                onFailure = { e -> _state.update { it.copy(historyLoading = false, error = e.message) } },
            )
        }
    }
}
