package com.boorce.carbu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.boorce.carbu.core.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object Locator {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Position actuelle, ou null (permission refusée, localisation coupée, délai dépassé).
     * Le réseau d'abord (rapide, y compris en intérieur), puis le GPS, puis la dernière position connue.
     * La position n'est utilisée que pour calculer des distances sur le téléphone.
     */
    suspend fun current(context: Context): GeoPoint? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        for (p in providers) {
            val loc = withTimeoutOrNull(12_000) { fix(context, lm, p) }
            if (loc != null) return GeoPoint(loc.latitude, loc.longitude)
        }
        for (p in providers) {
            @Suppress("MissingPermission")
            val last = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (last != null) return GeoPoint(last.latitude, last.longitude)
        }
        return null
    }

    @Suppress("MissingPermission")
    private suspend fun fix(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(context)) {
                    if (cont.isActive) cont.resume(it)
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
        }
}
