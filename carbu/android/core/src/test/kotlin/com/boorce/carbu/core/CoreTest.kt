package com.boorce.carbu.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TripTest {
    @Test
    fun `plein de 45 L au prix de la station`() {
        val c = costing(price = 2.000, straightKm = 0.0)
        assertEquals(90.0, c.fillCost, 1e-9)
        assertEquals(0.0, c.tripCost, 1e-9)
        assertEquals(90.0, c.total, 1e-9)
    }

    @Test
    fun `deplacement aller-retour, route plus longue que le vol d'oiseau, 7 L aux 100`() {
        // 10 km à vol d'oiseau x1,3 route x2 A/R = 26 km ; 26 x 7/100 = 1,82 L ; à 2,000 €/L = 3,64 €
        val c = costing(price = 2.000, straightKm = 10.0)
        assertEquals(26.0, c.drivenKm, 1e-9)
        assertEquals(1.82, c.tripLiters, 1e-9)
        assertEquals(3.64, c.tripCost, 1e-9)
        assertEquals(93.64, c.total, 1e-9)
    }

    @Test
    fun `aller simple et parametres personnalises`() {
        val p = TripParams(consumption = 5.0, tank = 50.0, roadFactor = 1.0, roundTrip = false)
        val c = costing(price = 1.5, straightKm = 20.0, p = p)
        assertEquals(20.0, c.drivenKm, 1e-9)
        assertEquals(1.0, c.tripLiters, 1e-9) // 20 km x 5/100
        assertEquals(75.0, c.fillCost, 1e-9)
        assertEquals(1.5, c.tripCost, 1e-9)
    }

    @Test
    fun `une station un peu moins chere mais loin peut couter plus cher au total`() {
        val pres = costing(price = 2.05, straightKm = 1.0)
        val loin = costing(price = 2.00, straightKm = 40.0) // 0,05 €/L x 45 L = 2,25 € gagnés, 8,7 L brûlés
        assertTrue(loin.fillCost < pres.fillCost)
        assertTrue(loin.total > pres.total)
        assertTrue(cheapestTotal(pres, loin) < 0)
    }

    @Test
    fun `valeurs aberrantes ramenees dans des bornes`() {
        val s = TripParams(consumption = -3.0, tank = 0.0, roadFactor = 9.0).sanitized()
        assertEquals(1.0, s.consumption, 0.0)
        assertEquals(1.0, s.tank, 0.0)
        assertEquals(2.0, s.roadFactor, 0.0)
    }

    @Test
    fun `haversine Le Doulieu - Gabriels Locre vaut environ 12,7 km`() {
        val d = haversineKm(50.679621, 2.703757, 50.785394, 2.771578)
        assertEquals(12.7, d, 0.2)
        assertEquals(0.0, haversineKm(50.0, 2.0, 50.0, 2.0), 1e-9)
    }

    @Test
    fun `station sans coordonnees - pas de distance`() {
        val s = StationView(id = "x", name = "X")
        assertNull(distanceTo(s, GeoPoint(50.0, 2.0)))
    }

    @Test
    fun `formats francais`() {
        assertEquals("93,64 €", formatEuro(93.64).replace(' ', ' '))
        assertEquals("2,035", formatPrice(2.035))
        assertEquals("12,7 km", formatKm(12.66).replace(' ', ' '))
        assertEquals("il y a 5 h", formatAge(5.2))
        assertEquals("il y a 3 j", formatAge(72.0))
        assertEquals("il y a moins d'1 h", formatAge(0.3))
    }

    @Test
    fun `saisie de prix - virgule, point, bornes`() {
        assertEquals(2.05, parsePriceInput("2,05")!!, 0.0)
        assertEquals(2.05, parsePriceInput(" 2.05 ")!!, 0.0)
        assertEquals(1.999, parsePriceInput("1,9994")!!, 0.0)
        assertNull(parsePriceInput("20,5")) // faute de frappe probable
        assertNull(parsePriceInput("0"))
        assertNull(parsePriceInput("abc"))
        assertNull(parsePriceInput(""))
    }
}

class UrlTest {
    @Test
    fun `normalisation des adresses`() {
        assertEquals("https://carbu.example.com", normalizeBaseUrl("carbu.example.com"))
        assertEquals("https://carbu.example.com", normalizeBaseUrl("  https://carbu.example.com/  "))
        assertEquals("https://carbu.example.com", normalizeBaseUrl("https://carbu.example.com/api/v1"))
        assertEquals("https://carbu.example.com:8443", normalizeBaseUrl("carbu.example.com:8443/"))
        assertEquals("http://10.0.2.2:18081", normalizeBaseUrl("http://10.0.2.2:18081?x=1#y"))
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("ftp://exemple.fr"))
    }
}

class ApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString()

    private val pricesJson = """
        {"generated_at":"2026-11-19T12:00:00Z","origin":{"name":"Le Doulieu","lat":50.68,"lon":2.70},
         "fuels":[{"key":"gazole","label":"Gazole / Diesel"},{"key":"e10","label":"SP95-E10"}],
         "stations":[
          {"id":"fr-1","name":"Bailleul","town":"Bailleul","country":"FR","lat":50.724,"lon":2.74,"distance_km":5.6,"checked":"2026-11-19T12:00:00Z",
           "prices":{"gazole":{"price":2.064,"updated":"2026-11-19T11:00:00Z","age_hours":1.0,"reliability":100,"level":"good","origin":"source","nouveau_champ":1}}},
          {"id":"be-3427","name":"Le Seau","town":"Le Seau","country":"BE","distance_km":9.9,"prices":{}}],
         "best":{"gazole":"fr-1"},"sources":{"fr":{"label":"France","ok":true,"last_success":"2026-11-19T11:59:00Z"}}}
    """.trimIndent()

    @Test
    fun `lecture des prix, champs inconnus ignores`() = runTest {
        server.enqueue(MockResponse().setBody(pricesJson))
        val r = CarbuApi(base(), { null }).prices()
        assertEquals(2, r.stations.size)
        assertEquals(2.064, r.stations[0].prices["gazole"]!!.price, 0.0)
        assertFalse(r.stations[0].prices["gazole"]!!.isManual)
        assertTrue(r.stations[0].hasPosition)
        assertFalse(r.stations[1].hasPosition) // pas de lat/lon : distance inconnue
        assertEquals("fr-1", r.best["gazole"])
        assertEquals("/api/v1/prices", server.takeRequest().path)
    }

    @Test
    fun `ecriture sans cle - refusee avant tout appel reseau`() = runTest {
        try {
            CarbuApi(base(), { null }).putPrices("fr-1", mapOf("gazole" to 2.05))
            fail()
        } catch (e: ApiException.NoKey) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `ecriture avec cle - en-tete Bearer et corps JSON`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"fr-1","name":"Bailleul","prices":{"gazole":{"price":2.05,"origin":"manual","level":"good","reliability":100}}}"""))
        val st = CarbuApi(base(), { "ck_secret123" }).putPrices("fr-1", mapOf("gazole" to 2.05))
        assertTrue(st.prices["gazole"]!!.isManual)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/stations/fr-1/prices", req.path)
        assertEquals("Bearer ck_secret123", req.getHeader("Authorization"))
        assertEquals("""{"prices":{"gazole":2.05}}""", req.body.readUtf8())
    }

    @Test
    fun `cle refusee 401 et rejet de saisie 400 avec le message du serveur`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"clé d'API invalide"}"""))
        try {
            CarbuApi(base(), { "ck_x" }).putPrices("fr-1", mapOf("gazole" to 2.05)); fail()
        } catch (e: ApiException.Unauthorized) { /* attendu */ }

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"prix hors limites pour gazole (0,30 à 5,00 €/L)"}"""))
        try {
            CarbuApi(base(), { "ck_x" }).putPrices("fr-1", mapOf("gazole" to 9.0)); fail()
        } catch (e: ApiException.Rejected) {
            assertTrue(e.message!!.contains("hors limites"))
        }
    }

    @Test
    fun `suppression d'une correction`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"fr-1","name":"Bailleul","prices":{}}"""))
        CarbuApi(base(), { "ck_x" }).deletePrice("fr-1", "gazole")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v1/stations/fr-1/prices/gazole", req.path)
    }

    @Test
    fun `synchro de cle - requete, reponse, rotation par device_id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"abc","name":"Pixel 7","created":"2026-11-19T12:00:00Z","key":"ck_nouvelle"}"""))
        val k = KeyApi(base()).issue("device-uuid-0001", "Pixel 7")
        assertEquals("ck_nouvelle", k.key)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/keys", req.path)
        assertEquals("""{"device_id":"device-uuid-0001","name":"Pixel 7"}""", req.body.readUtf8())
    }

    @Test
    fun `gestion des cles hors LAN - 403 explicite`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"accès réservé au réseau local"}"""))
        try {
            KeyApi(base()).issue("device-uuid-0001", "Pixel 7"); fail()
        } catch (e: ApiException.Forbidden) {
            assertTrue(e.message!!.contains("réseau local"))
        }
    }

    @Test
    fun `reponse non JSON - erreur propre`() = runTest {
        server.enqueue(MockResponse().setBody("<html>proxy</html>"))
        try {
            CarbuApi(base(), { null }).prices(); fail()
        } catch (e: ApiException.BadResponse) { assertNotNull(e) }
    }

    @Test
    fun `serveur en erreur 500 et limitation 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        try { CarbuApi(base(), { null }).prices(); fail() } catch (e: ApiException.Server) { assertEquals(500, e.code) }
        server.enqueue(MockResponse().setResponseCode(429))
        try { CarbuApi(base(), { null }).prices(); fail() } catch (e: ApiException.RateLimited) { /* attendu */ }
    }
}
