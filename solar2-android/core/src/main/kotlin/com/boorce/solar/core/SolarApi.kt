package com.boorce.solar.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Erreurs de l'API ; les messages sont directement affichables à l'utilisateur. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited : ApiException("Trop de requêtes, réessayez dans un instant.")
    class Server(val code: Int) : ApiException("Erreur du serveur ($code).")
    class Network(cause: Throwable) : ApiException("Serveur injoignable (${cause.message ?: cause.javaClass.simpleName}).", cause)
    class BadResponse(cause: Throwable? = null) : ApiException("Réponse inattendue du serveur.", cause)
}

val apiJson = Json {
    ignoreUnknownKeys = true // le serveur peut ajouter des champs sans casser l'application
    coerceInputValues = true
}

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()

/**
 * Normalise l'adresse saisie par l'utilisateur : ajoute https:// si absent, retire un éventuel
 * suffixe /api ou /api/v1, la requête et le fragment, ainsi que la barre oblique finale.
 * Renvoie null si l'adresse n'est pas exploitable.
 */
fun normalizeBaseUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    val url = s.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    val path = url.encodedPath.trimEnd('/').removeSuffix("/api/v1").removeSuffix("/api").ifEmpty { "/" }
    return url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString().trimEnd('/')
}

/** Client de l'API solar2 (lecture seule). Toutes les fonctions sont annulables. */
class SolarApi(baseUrl: String, private val client: OkHttpClient = defaultHttpClient()) {
    private val base: HttpUrl = (normalizeBaseUrl(baseUrl) ?: error("Adresse invalide : $baseUrl")).let {
        (it + "/").toHttpUrlOrNull() ?: error("Adresse invalide : $baseUrl")
    }

    suspend fun health(): Boolean = try {
        fetch("health") != null
    } catch (e: ApiException.BadResponse) {
        false
    }

    suspend fun current(): Current? = get("current")
    suspend fun summary(): SummaryResponse? = get("summary")
    suspend fun day(date: LocalDate? = null): DayResponse? =
        get("day", *listOfNotNull(date?.let { "date" to it.toString() }).toTypedArray())

    suspend fun days(from: LocalDate, to: LocalDate): DaysResponse? =
        get("days", "from" to from.toString(), "to" to to.toString())

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T? {
        val body = fetch(path, *query) ?: return null
        return try {
            apiJson.decodeFromString(serializer<T>(), body)
        } catch (e: Exception) {
            throw ApiException.BadResponse(e)
        }
    }

    /** Corps de la réponse, ou null pour un 404 (« aucune donnée »). */
    private suspend fun fetch(path: String, vararg query: Pair<String, String>): String? {
        val url = base.newBuilder().addPathSegments("api/v1/$path").apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                when {
                    it.code == 404 -> null
                    it.code == 429 -> throw ApiException.RateLimited()
                    !it.isSuccessful -> throw ApiException.Server(it.code)
                    else -> try {
                        it.body?.string() ?: throw ApiException.BadResponse()
                    } catch (e: IOException) {
                        throw ApiException.Network(e)
                    }
                }
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(ApiException.Network(e))
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
