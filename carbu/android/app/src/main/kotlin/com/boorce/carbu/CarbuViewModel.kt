package com.boorce.carbu

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boorce.carbu.core.ApiException
import com.boorce.carbu.core.CarbuApi
import com.boorce.carbu.core.GeoPoint
import com.boorce.carbu.core.KeyApi
import com.boorce.carbu.core.PricesResponse
import com.boorce.carbu.core.StationView
import com.boorce.carbu.core.TripParams
import com.boorce.carbu.core.normalizeBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { MAIN, SETTINGS }
enum class SortMode(val label: String) { PRICE("Prix au litre"), TOTAL("Plein + trajet"), DISTANCE("Distance") }
enum class PositionState { UNKNOWN, LOCATING, OK, DENIED, UNAVAILABLE }

data class SyncState(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

data class UiState(
    val screen: Screen = Screen.MAIN,
    val data: PricesResponse? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fuel: String = "gazole",
    val sort: SortMode = SortMode.PRICE,
    val position: GeoPoint? = null,
    val positionState: PositionState = PositionState.UNKNOWN,
    val dataUrl: String? = null,
    val keyUrl: String? = null,
    val hasKey: Boolean = false,
    val keyDate: String? = null,
    val trip: TripParams = TripParams(),
    val sync: SyncState = SyncState(),
    val editing: StationView? = null,
    val saving: Boolean = false,
    val editError: String? = null,
    val message: String? = null,
)

class CarbuViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val _state = MutableStateFlow(
        UiState(
            screen = if (settings.dataUrl == null) Screen.SETTINGS else Screen.MAIN,
            fuel = settings.fuel,
            dataUrl = settings.dataUrl,
            keyUrl = settings.keyUrl,
            hasKey = settings.apiKey != null,
            keyDate = settings.apiKeyDate,
            trip = settings.trip,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        if (settings.dataUrl != null) refresh()
        if (Locator.hasPermission(app)) refreshPosition()
    }

    private fun api(): CarbuApi? = settings.dataUrl?.let { CarbuApi(it, { settings.apiKey }) }

    private fun errorText(e: Throwable) = (e as? ApiException)?.message ?: "Erreur : ${e.message ?: e.javaClass.simpleName}"

    // --- Données ---------------------------------------------------------------------

    fun refresh() {
        val api = api() ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val data = api.prices()
                _state.update { it.copy(data = data, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = errorText(e)) }
            }
        }
    }

    fun selectFuel(key: String) {
        settings.fuel = key
        _state.update { it.copy(fuel = key) }
    }

    fun setSort(mode: SortMode) = _state.update { it.copy(sort = mode) }

    // --- Position ----------------------------------------------------------------------

    fun refreshPosition() {
        viewModelScope.launch {
            if (!Locator.hasPermission(getApplication())) {
                _state.update { it.copy(positionState = PositionState.DENIED) }
                return@launch
            }
            _state.update { it.copy(positionState = PositionState.LOCATING) }
            val p = Locator.current(getApplication())
            _state.update {
                if (p != null) it.copy(position = p, positionState = PositionState.OK)
                else it.copy(positionState = PositionState.UNAVAILABLE)
            }
        }
    }

    fun onLocationPermission(granted: Boolean) {
        if (granted) refreshPosition() else _state.update { it.copy(positionState = PositionState.DENIED) }
    }

    // --- Paramètres et clé -------------------------------------------------------------

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS, sync = SyncState()) }
    fun closeSettings() = _state.update { it.copy(screen = Screen.MAIN) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Enregistre les URL et les hypothèses de coût. Renvoie un message d'erreur, ou null si tout est valide. */
    fun saveSettings(dataUrlInput: String, keyUrlInput: String, trip: TripParams): String? {
        val data = normalizeBaseUrl(dataUrlInput) ?: return "URL des données invalide."
        val key = keyUrlInput.takeIf { it.isNotBlank() }?.let { normalizeBaseUrl(it) ?: return "URL de gestion des clés invalide." }
        settings.dataUrl = data
        settings.keyUrl = key
        settings.trip = trip
        _state.update { it.copy(dataUrl = data, keyUrl = key, trip = settings.trip, screen = Screen.MAIN, message = "Paramètres enregistrés") }
        refresh()
        return null
    }

    /**
     * « Synchro clé » : demande une clé à l'API de gestion (LAN) pour cet appareil, la stocke chiffrée.
     * Rejouer l'opération remplace la clé précédente de l'appareil (rotation).
     */
    fun syncKey(keyUrlInput: String) {
        val url = normalizeBaseUrl(keyUrlInput)
        if (url == null) {
            _state.update { it.copy(sync = SyncState(message = "Renseignez d'abord l'URL de gestion des clés.", error = true)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sync = SyncState(running = true)) }
            try {
                val issued = KeyApi(url).issue(settings.deviceId, deviceName())
                settings.keyUrl = url
                settings.apiKey = issued.key
                _state.update {
                    it.copy(
                        keyUrl = url, hasKey = true, keyDate = settings.apiKeyDate,
                        sync = SyncState(message = "Clé synchronisée : les corrections sont autorisées."),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sync = SyncState(message = errorText(e), error = true)) }
            }
        }
    }

    fun forgetKey() {
        settings.apiKey = null
        _state.update { it.copy(hasKey = false, keyDate = null, sync = SyncState(message = "Clé oubliée sur ce téléphone (pensez à la révoquer côté serveur).")) }
    }

    private fun deviceName(): String =
        ("Carbu " + Build.MODEL).replace(Regex("[^\\p{L}\\p{N} ._'()-]"), "-").take(40)

    // --- Corrections manuelles -----------------------------------------------------------

    fun startEdit(station: StationView) = _state.update { it.copy(editing = station, editError = null) }
    fun reportEditError(msg: String) = _state.update { it.copy(editError = msg) }
    fun cancelEdit() = _state.update { it.copy(editing = null, editError = null, saving = false) }

    fun savePrices(stationId: String, prices: Map<String, Double>) {
        val api = api() ?: return
        if (prices.isEmpty()) {
            cancelEdit()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, editError = null) }
            try {
                api.putPrices(stationId, prices)
                _state.update { it.copy(saving = false, editing = null, message = "Correction enregistrée") }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, editError = errorText(e)) }
            }
        }
    }

    fun removeManual(stationId: String, fuel: String) {
        val api = api() ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, editError = null) }
            try {
                api.deletePrice(stationId, fuel)
                _state.update { it.copy(saving = false, editing = null, message = "Correction retirée") }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, editError = errorText(e)) }
            }
        }
    }
}
