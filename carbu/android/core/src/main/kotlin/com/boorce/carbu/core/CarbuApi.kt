package com.boorce.carbu.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Erreurs de l'API ; les messages sont directement affichables à l'utilisateur. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoKey : ApiException("Aucune clé d'API : faites « Synchro clé » dans les paramètres (depuis le réseau local).")
    class Unauthorized : ApiException("Clé d'API refusée (révoquée ou invalide) : refaites « Synchro clé » depuis le réseau local.")
    class Forbidden : ApiException("Accès refusé : la gestion des clés n'est joignable que depuis le réseau local.")
    class Rejected(msg: String) : ApiException(msg)
    class RateLimited : ApiException("Trop de requêtes ou trop d'échecs : réessayez plus tard.")
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
 * Normalise une adresse saisie : https:// ajouté si absent, suffixe /api ou /api/v1, requête et
 * fragment retirés, barre oblique finale retirée. Le port est conservé (ex. :8443).
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

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private fun baseOf(input: String): HttpUrl =
    ((normalizeBaseUrl(input) ?: error("Adresse invalide : $input")) + "/").toHttpUrlOrNull()
        ?: error("Adresse invalide : $input")

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

/** Message d'erreur du serveur (`{"error":"…"}`), s'il y en a un. */
private fun serverMessage(body: String): String? = runCatching {
    apiJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
}.getOrNull()

private suspend fun execute(client: OkHttpClient, request: Request): Pair<Int, String> {
    val response = client.newCall(request).await()
    return withContext(Dispatchers.IO) {
        response.use {
            try {
                it.code to (it.body?.string() ?: "")
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
        }
    }
}

private fun check(code: Int, body: String, unauthorized: ApiException = ApiException.Unauthorized()) {
    when {
        code in 200..299 -> Unit
        code == 401 -> throw unauthorized
        code == 403 -> throw ApiException.Forbidden()
        code == 429 -> throw ApiException.RateLimited()
        code in 400..499 -> throw ApiException.Rejected(serverMessage(body) ?: "Requête refusée ($code).")
        else -> throw ApiException.Server(code)
    }
}

private inline fun <reified T> decode(body: String): T = try {
    apiJson.decodeFromString(body)
} catch (e: Exception) {
    throw ApiException.BadResponse(e)
}

/**
 * Client de l'API de données (URL « classique », accessible d'internet) :
 * lecture libre ; écriture avec la clé d'API fournie par [keyProvider].
 */
class CarbuApi(
    baseUrl: String,
    private val keyProvider: () -> String?,
    private val client: OkHttpClient = defaultHttpClient(),
) {
    private val base = baseOf(baseUrl)

    private fun url(vararg segments: String) = base.newBuilder().apply {
        addPathSegments("api/v1")
        segments.forEach { addPathSegment(it) }
    }.build()

    suspend fun prices(): PricesResponse {
        val (code, body) = execute(client, Request.Builder().url(url("prices")).header("Accept", "application/json").build())
        check(code, body)
        return decode(body)
    }

    /** Pousse des corrections manuelles ; renvoie la station telle qu'elle apparaît ensuite. */
    suspend fun putPrices(stationId: String, prices: Map<String, Double>): StationView {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw ApiException.NoKey()
        val json = apiJson.encodeToString(PutPricesRequest.serializer(), PutPricesRequest(prices))
        val req = Request.Builder()
            .url(url("stations", stationId, "prices"))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .put(json.toRequestBody(JSON_MEDIA))
            .build()
        val (code, body) = execute(client, req)
        check(code, body)
        return decode(body)
    }

    /** Retire la correction manuelle d'un carburant. */
    suspend fun deletePrice(stationId: String, fuel: String): StationView {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw ApiException.NoKey()
        val req = Request.Builder()
            .url(url("stations", stationId, "prices", fuel))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .delete()
            .build()
        val (code, body) = execute(client, req)
        check(code, body)
        return decode(body)
    }
}

/** Client de l'API de gestion des clés (URL réservée au LAN, port distinct). */
class KeyApi(baseUrl: String, private val client: OkHttpClient = defaultHttpClient()) {
    private val base = baseOf(baseUrl)

    /**
     * Demande une clé pour cet appareil. Même [deviceId] => la clé précédente est remplacée
     * (rotation). La clé n'est renvoyée qu'ici : l'appelant doit la stocker aussitôt.
     */
    suspend fun issue(deviceId: String, deviceName: String): IssuedKey {
        val json = apiJson.encodeToString(IssueRequest.serializer(), IssueRequest(deviceId, deviceName))
        val req = Request.Builder()
            .url(base.newBuilder().addPathSegments("api/v1/keys").build())
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        val (code, body) = execute(client, req)
        check(code, body)
        return decode(body)
    }
}
