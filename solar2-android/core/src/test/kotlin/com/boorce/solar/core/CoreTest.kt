package com.boorce.solar.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// Extraits de vraies réponses de l'API solar2 (données de production).
private const val CURRENT_JSON =
    """{"t":1794252515,"age_seconds":64746,"watts":0,"energy_today_kwh":2.69,"energy_total_kwh":13772.6,"temperature_c":-21,"sun_elevation_deg":19.1}"""
private const val DAY_JSON =
    """{"date":"2026-11-09","tz":"Europe/Paris","day_start":1794178800,"day_end":1794265200,"points":[[1794178917,0],[1794179241,0],[1794200000,1250]],"summary":{"energy_kwh":2.69,"peak_w":1250,"peak_at":1794200000,"first_at":1794178917,"last_at":1794200000,"sun_max_elevation_deg":22.1},"champ_futur":42}"""
private const val DAYS_JSON =
    """{"tz":"Europe/Paris","from":"2026-10-10","to":"2026-10-12","days":[{"date":"2026-10-10","energy_kwh":3.91,"peak_w":1381},{"date":"2026-10-12","energy_kwh":3.56,"peak_w":1212}]}"""
private const val SUMMARY_JSON =
    """{"tz":"Europe/Paris","today_kwh":0,"last7_kwh":11,"last30_kwh":76.4,"last365_kwh":2108,"total_kwh":13773}"""

class ModelsTest {
    @Test
    fun `parse current`() {
        val c = apiJson.decodeFromString(Current.serializer(), CURRENT_JSON)
        assertEquals(1794252515L, c.at)
        assertEquals(0, c.watts)
        assertEquals(13772.6, c.energyTotalKwh, 1e-9)
        assertEquals(19.1, c.sunElevationDeg!!, 1e-9)
    }

    @Test
    fun `parse day ignore les champs inconnus et convertit les points`() {
        val d = apiJson.decodeFromString(DayResponse.serializer(), DAY_JSON)
        assertEquals("2026-11-09", d.date)
        assertEquals(3, d.toPoints().size)
        assertEquals(Point(1794200000, 1250), d.toPoints().last())
        assertEquals(1250, d.summary.peakW)
        assertEquals(22.1, d.summary.sunMaxElevationDeg!!, 1e-9)
    }

    @Test
    fun `jour vide`() {
        val d = apiJson.decodeFromString(
            DayResponse.serializer(),
            """{"date":"2026-11-10","tz":"Europe/Paris","day_start":1,"day_end":2,"points":[],"summary":{"energy_kwh":0,"peak_w":0}}""",
        )
        assertTrue(d.toPoints().isEmpty())
        assertNull(d.summary.peakAt)
    }
}

class FormatTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `puissance`() {
        assertEquals("0 W", Fmt.power(0))
        assertEquals("456 W", Fmt.power(456))
        assertEquals("1,23 kW", Fmt.power(1234))
        assertEquals("1,90 kW", Fmt.power(1900))
    }

    @Test
    fun `energie`() {
        assertEquals("12,81 kWh", Fmt.kwh(12.81))
        assertEquals("0,00 kWh", Fmt.kwh(0.0))
        assertEquals("76,4", Fmt.num(76.4, 1))
    }

    @Test
    fun `heure dans le fuseau du serveur et pas celui de l'appareil`() {
        // 2026-11-09 19:28:35 UTC = 20:28 à Paris (hiver)
        assertEquals("20:28", Fmt.time(1794252515, paris))
        assertEquals("19:28", Fmt.time(1794252515, ZoneId.of("UTC")))
    }

    @Test
    fun `dates`() {
        assertEquals("dimanche 21 juin 2026", Fmt.dateLong(LocalDate.of(2026, 6, 21)))
        assertEquals("21/06", Fmt.dateShort(LocalDate.of(2026, 6, 21)))
        assertEquals("janv", Fmt.monthShort(java.time.YearMonth.of(2026, 1)))
        assertEquals("juin 2026", Fmt.monthLong(java.time.YearMonth.of(2026, 6)))
    }

    @Test
    fun `anciennete`() {
        assertEquals("à l'instant", Fmt.ago(30))
        assertEquals("il y a 12 min", Fmt.ago(720))
        assertEquals("il y a 17 h 59", Fmt.ago(64746))
        assertEquals("il y a 18 h 00", Fmt.ago(17 * 3600L + 59 * 60 + 45)) // pas « 17 h 60 »
        assertEquals("il y a 1 h 00", Fmt.ago(3599))
        assertEquals("il y a 3 j", Fmt.ago(3 * 86400L + 100))
    }
}

class ChartsTest {
    @Test
    fun `echelle ronde`() {
        assertEquals(Scale(2000.0, 500.0), niceScale(1900.0))
        assertEquals(Scale(400.0, 100.0), niceScale(389.8))
        assertEquals(Scale(6.0, 2.0), niceScale(4.6))
        assertEquals(1.0, niceScale(0.0).max, 0.0)
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0, 2000.0), niceScale(1900.0).ticks())
    }

    @Test
    fun `point le plus proche`() {
        val pts = listOf(Point(100, 1), Point(200, 2), Point(400, 3))
        assertEquals(0, nearestIndex(pts, 10.0))
        assertEquals(1, nearestIndex(pts, 240.0))
        assertEquals(2, nearestIndex(pts, 330.0))
        assertEquals(2, nearestIndex(pts, 9999.0))
        assertEquals(-1, nearestIndex(emptyList(), 5.0))
    }

    @Test
    fun `un trou coupe la courbe`() {
        val pts = listOf(Point(0, 1), Point(300, 1), Point(600, 1), Point(4000, 1), Point(4300, 1))
        val s = segments(pts)
        assertEquals(2, s.size)
        assertEquals(3, s[0].size)
        assertEquals(2, s[1].size)
        assertTrue(segments(emptyList()).isEmpty())
    }

    @Test
    fun `colonnes quotidiennes - les jours sans mesure valent zero`() {
        val days = listOf(DayTotal("2026-10-10", 3.91, 1381), DayTotal("2026-10-12", 3.56, 1212))
        val bars = dailyBars(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), days)
        assertEquals(listOf(3.91, 0.0, 3.56), bars.map { it.value })
        assertEquals("pas de mesure", bars[1].extra)
        assertEquals("10/10", bars[0].label)
    }

    @Test
    fun `colonnes mensuelles`() {
        val days = listOf(DayTotal("2026-01-02", 1.0), DayTotal("2026-01-30", 2.5), DayTotal("2026-02-01", 4.0))
        val bars = monthlyBars(days)
        assertEquals(listOf(3.5, 4.0), bars.map { it.value })
        assertEquals(listOf("janv", "févr"), bars.map { it.label })
    }

    @Test
    fun `avertissement de mesure ancienne seulement quand le soleil est leve`() {
        val base = Current(at = 0, ageSeconds = 4000, watts = 0, sunElevationDeg = 30.0)
        assertNotNull(staleWarning(base))
        assertNull(staleWarning(base.copy(sunElevationDeg = -20.0))) // nuit : l'onduleur dort
        assertNull(staleWarning(base.copy(ageSeconds = 120)))
        assertNull(staleWarning(base.copy(sunElevationDeg = null)))
    }
}

class UrlTest {
    @Test
    fun `normalisation de l'adresse du serveur`() {
        assertEquals("https://solar.exemple.fr", normalizeBaseUrl("solar.exemple.fr"))
        assertEquals("https://solar.exemple.fr", normalizeBaseUrl("  https://solar.exemple.fr/  "))
        assertEquals("https://solar.exemple.fr", normalizeBaseUrl("https://solar.exemple.fr/api/v1/"))
        assertEquals("https://h.fr/solar", normalizeBaseUrl("https://h.fr/solar/?x=1#y"))
        assertEquals("http://192.168.0.10:8080", normalizeBaseUrl("http://192.168.0.10:8080"))
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("ftp://serveur"))
        assertNull(normalizeBaseUrl("pas une url"))
    }
}

class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SolarApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = SolarApi(server.url("/").toString())
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `current ok`() = runBlocking {
        server.enqueue(json(CURRENT_JSON))
        val c = api.current()
        assertEquals(0, c!!.watts)
        val req = server.takeRequest()
        assertEquals("/api/v1/current", req.path)
        assertEquals("GET", req.method)
    }

    @Test
    fun `day avec et sans date`() = runBlocking {
        server.enqueue(json(DAY_JSON))
        server.enqueue(json(DAY_JSON))
        api.day()
        api.day(LocalDate.of(2026, 6, 21))
        assertEquals("/api/v1/day", server.takeRequest().path)
        assertEquals("/api/v1/day?date=2026-06-21", server.takeRequest().path)
    }

    @Test
    fun `days passe from et to`() = runBlocking {
        server.enqueue(json(DAYS_JSON))
        val r = api.days(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12))
        assertEquals(2, r!!.days.size)
        assertEquals("/api/v1/days?from=2026-10-10&to=2026-10-12", server.takeRequest().path)
    }

    @Test
    fun `404 signifie aucune donnee`() = runBlocking {
        server.enqueue(json("""{"error":"aucune donnée"}""", 404))
        assertNull(api.current())
    }

    @Test
    fun `429 limitation de debit`() = runBlocking {
        server.enqueue(json("""{"error":"trop de requêtes"}""", 429))
        try {
            api.current()
            fail("429 attendu")
        } catch (e: ApiException.RateLimited) {
            assertTrue(e.message!!.contains("Trop de requêtes"))
        }
    }

    @Test
    fun `500 erreur serveur`() = runBlocking {
        server.enqueue(json("""{"error":"erreur interne"}""", 500))
        try {
            api.summary()
            fail("500 attendu")
        } catch (e: ApiException.Server) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `json invalide`() = runBlocking {
        server.enqueue(json("<html>pas du json</html>"))
        try {
            api.current()
            fail("BadResponse attendu")
        } catch (e: ApiException.BadResponse) {
            // attendu
        }
    }

    @Test
    fun `health`() = runBlocking {
        server.enqueue(json("""{"status":"ok"}"""))
        assertTrue(api.health())
        server.enqueue(json("""{"error":"base indisponible"}""", 503))
        try {
            api.health()
            fail("503 attendu")
        } catch (e: ApiException.Server) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun `reseau coupe`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            api.current()
            fail("erreur réseau attendue")
        } catch (e: ApiException.Network) {
            assertTrue(e.message!!.startsWith("Serveur injoignable"))
        }
    }

    @Test
    fun `adresse avec chemin`() = runBlocking {
        val withPath = SolarApi(server.url("/solar/").toString())
        server.enqueue(json(CURRENT_JSON))
        withPath.current()
        assertEquals("/solar/api/v1/current", server.takeRequest().path)
    }
}

class RepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: SolarRepository

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        repo = SolarRepository(SolarApi(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun dispatcher(routes: Map<String, Pair<Int, String>>) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val key = routes.keys.firstOrNull { request.path!!.startsWith(it) }
                val (code, body) = routes[key] ?: (404 to "{}")
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
    }

    @Test
    fun `kpis complets`() = runBlocking {
        dispatcher(mapOf(
            "/api/v1/current" to (200 to CURRENT_JSON),
            "/api/v1/day" to (200 to DAY_JSON),
            "/api/v1/summary" to (200 to SUMMARY_JSON),
        ))
        val k = repo.kpis().getOrThrow()
        assertEquals(0, k.current!!.watts)
        assertEquals(1250, k.today!!.summary.peakW)
        assertEquals(76.4, k.summary!!.last30Kwh, 1e-9)
    }

    @Test
    fun `kpis partiels - une panne n'empeche pas le reste`() = runBlocking {
        dispatcher(mapOf(
            "/api/v1/current" to (500 to "{}"),
            "/api/v1/day" to (200 to DAY_JSON),
            "/api/v1/summary" to (429 to "{}"),
        ))
        val k = repo.kpis().getOrThrow()
        assertNull(k.current)
        assertNotNull(k.today)
        assertNull(k.summary)
    }

    @Test
    fun `kpis - echec total`() = runBlocking {
        dispatcher(mapOf(
            "/api/v1/current" to (500 to "{}"),
            "/api/v1/day" to (500 to "{}"),
            "/api/v1/summary" to (500 to "{}"),
        ))
        assertTrue(repo.kpis().isFailure)
    }

    @Test
    fun `historique 30 jours - une colonne par jour`() = runBlocking {
        val d1 = repo.today().minusDays(3).toString()
        val d2 = repo.today().minusDays(1).toString()
        val json = """{"tz":"Europe/Paris","from":"$d1","to":"$d2","days":[{"date":"$d1","energy_kwh":3.91,"peak_w":1381},{"date":"$d2","energy_kwh":3.56,"peak_w":1212}]}"""
        dispatcher(mapOf("/api/v1/days" to (200 to json)))
        val h = repo.history(HistoryRange.D30).getOrThrow()
        assertEquals(30, h.bars.size)
        assertFalse(h.monthly)
        assertEquals(3.91 + 3.56, h.totalKwh, 1e-9)
        assertEquals(d2, h.rows.first().date) // tableau : le plus récent d'abord
    }

    @Test
    fun `historique 12 mois - regroupe par mois`() = runBlocking {
        dispatcher(mapOf("/api/v1/days" to (200 to DAYS_JSON)))
        val h = repo.history(HistoryRange.Y1).getOrThrow()
        assertTrue(h.monthly)
        assertEquals(1, h.bars.size)
        assertEquals(7.47, h.bars[0].value, 1e-9)
    }

    @Test
    fun `le fuseau du serveur est appris`() = runBlocking {
        dispatcher(mapOf("/api/v1/day" to (200 to DAY_JSON.replace("Europe/Paris", "America/Martinique"))))
        repo.day(null).getOrThrow()
        assertEquals(ZoneId.of("America/Martinique"), repo.zone)
    }
}
