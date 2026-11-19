package com.boorce.carbu

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.boorce.carbu.core.TripParams
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffre un secret avec une clé AES-GCM qui ne quitte jamais l'Android Keystore : la clé d'API
 * stockée dans les préférences est illisible hors de cette application (et sans sauvegarde cloud).
 */
class SecretStore(private val prefs: SharedPreferences) {
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, b64(cipher.iv) + ":" + b64(ct)).apply()
    }

    /** Null si absent ou illisible (clé du Keystore perdue : l'utilisateur refait « Synchro clé »). */
    fun get(name: String): String? = runCatching {
        val (iv, ct) = prefs.getString(name, null)?.split(":", limit = 2) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "carbu_api_key"
    }
}

/** Préférences : les deux URL, la clé d'API (chiffrée) et les hypothèses de coût. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("carbu", Context.MODE_PRIVATE)
    private val secrets = SecretStore(prefs)

    /** URL « classique » des données (accessible d'internet). */
    var dataUrl: String?
        get() = prefs.getString("data_url", null)
        set(v) = putString("data_url", v)

    /** URL de l'API de gestion des clés (réseau local uniquement). */
    var keyUrl: String?
        get() = prefs.getString("key_url", null)
        set(v) = putString("key_url", v)

    var apiKey: String?
        get() = secrets.get("api_key")
        set(v) {
            secrets.put("api_key", v)
            putString("api_key_date", if (v == null) null else java.time.Instant.now().toString())
        }

    val apiKeyDate: String? get() = prefs.getString("api_key_date", null)

    /** Identifiant stable de l'appareil : une nouvelle synchro REMPLACE la clé précédente côté serveur. */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { putString("device_id", it) }

    var trip: TripParams
        get() = TripParams(
            consumption = prefs.getFloat("consumption", 7f).toDouble(),
            tank = prefs.getFloat("tank", 45f).toDouble(),
            roadFactor = prefs.getFloat("road_factor", 1.3f).toDouble(),
            roundTrip = prefs.getBoolean("round_trip", true),
        ).sanitized()
        set(v) {
            val s = v.sanitized()
            prefs.edit()
                .putFloat("consumption", s.consumption.toFloat())
                .putFloat("tank", s.tank.toFloat())
                .putFloat("road_factor", s.roadFactor.toFloat())
                .putBoolean("round_trip", s.roundTrip)
                .apply()
        }

    var fuel: String
        get() = prefs.getString("fuel", "gazole") ?: "gazole"
        set(v) = putString("fuel", v)

    private fun putString(k: String, v: String?) {
        prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
    }
}
