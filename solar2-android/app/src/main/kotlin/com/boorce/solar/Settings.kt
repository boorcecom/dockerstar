package com.boorce.solar

import android.content.Context

/** Préférences : adresse du serveur solar2 (la seule donnée à retenir). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("solar", Context.MODE_PRIVATE)

    var baseUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_URL) else putString(KEY_URL, value)
            }.apply()
        }

    private companion object {
        const val KEY_URL = "base_url"
    }
}
