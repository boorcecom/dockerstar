package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"testing/fstest"
	"time"
)

var t0 = time.Date(2026, 11, 19, 12, 0, 0, 0, time.UTC)

func TestKeyStoreCycleDeVie(t *testing.T) {
	dir := t.TempDir()
	ks := newKeyStore(dir)
	_, key, err := ks.Issue("device-0001", "Pixel 7", t0)
	if err != nil || !strings.HasPrefix(key, "ck_") || len(key) < 40 {
		t.Fatalf("issue: %v %q", err, key)
	}
	if _, ok := ks.Verify(key, t0); !ok {
		t.Fatal("clé valide refusée")
	}
	if _, ok := ks.Verify(key+"x", t0); ok {
		t.Fatal("clé altérée acceptée")
	}
	if _, ok := ks.Verify("", t0); ok {
		t.Fatal("clé vide acceptée")
	}
	// Rotation : même appareil => l'ancienne clé ne marche plus, pas de doublon.
	_, key2, _ := ks.Issue("device-0001", "Pixel 7", t0.Add(time.Hour))
	if _, ok := ks.Verify(key, t0); ok {
		t.Error("l'ancienne clé doit être invalidée par la rotation")
	}
	if _, ok := ks.Verify(key2, t0); !ok || len(ks.List()) != 1 {
		t.Errorf("rotation: %v", ks.List())
	}
	// Persistance : seule l'empreinte est sur disque, jamais la clé.
	b := readFile(t, dir+"/keys.json")
	if strings.Contains(b, key2) || !strings.Contains(b, hashKey(key2)) {
		t.Error("le fichier doit contenir l'empreinte et jamais la clé en clair")
	}
	ks2 := newKeyStore(dir)
	if err := ks2.Load(); err != nil {
		t.Fatal(err)
	}
	if _, ok := ks2.Verify(key2, t0); !ok {
		t.Error("clé non rechargée")
	}
	if !ks2.Revoke(ks2.List()[0].ID) {
		t.Error("révocation")
	}
	if _, ok := ks2.Verify(key2, t0); ok {
		t.Error("clé révoquée encore valide")
	}
}

func TestKeyStoreLimitesEtValidation(t *testing.T) {
	ks := newKeyStore("")
	for _, bad := range []string{"", "court", "avec espace 123456", strings.Repeat("a", 65), "../../etc/passwd"} {
		if _, _, err := ks.Issue(bad, "ok", t0); err == nil {
			t.Errorf("device_id %q accepté", bad)
		}
	}
	for _, bad := range []string{"", strings.Repeat("n", 41), "<script>", "a\nb"} {
		if _, _, err := ks.Issue("device-0001", bad, t0); err == nil {
			t.Errorf("name %q accepté", bad)
		}
	}
	for i := 0; i < maxKeys; i++ {
		if _, _, err := ks.Issue("device-"+strings.Repeat("x", 4)+string(rune('a'+i)), "n", t0); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := ks.Issue("device-overflow", "n", t0); err != errTooManyKeys {
		t.Errorf("dépassement accepté: %v", err)
	}
}

func readFile(t *testing.T, p string) string {
	t.Helper()
	b, err := osReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestIsLAN(t *testing.T) {
	for ip, want := range map[string]bool{
		"192.168.0.12": true, "10.1.2.3": true, "172.16.5.5": true, "127.0.0.1": true, "::1": true,
		"fe80::1": true, "fd00::1": true, "::ffff:192.168.1.1": true, "169.254.1.1": true,
		"8.8.8.8": false, "91.121.1.1": false, "172.32.0.1": false, "2a01:e0a::1": false, "::ffff:8.8.8.8": false,
	} {
		if got := isLAN(netip.MustParseAddr(ip)); got != want {
			t.Errorf("isLAN(%s) = %v", ip, got)
		}
	}
}

func adminReq(h http.Handler, method, path, body, remote, xff string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.RemoteAddr = remote
	if xff != "" {
		r.Header.Set("X-Forwarded-For", xff)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, r)
	return rec
}

func TestAdminRestreintAuLAN(t *testing.T) {
	_, h := newAdminHandler(newKeyStore(""), Config{})
	body := `{"device_id":"device-0001","name":"Pixel"}`
	if rec := adminReq(h, "POST", "/api/v1/keys", body, "8.8.8.8:5555", ""); rec.Code != 403 {
		t.Errorf("IP publique: %d", rec.Code)
	}
	if rec := adminReq(h, "POST", "/api/v1/keys", body, "192.168.0.5:5555", ""); rec.Code != 201 {
		t.Errorf("IP LAN: %d %s", rec.Code, rec.Body)
	}
	// Derrière un proxy : on juge l'IP du client, pas celle du proxy ; XFF absent = refus.
	_, hp := newAdminHandler(newKeyStore(""), Config{AdminTrustProxy: true})
	if rec := adminReq(hp, "POST", "/api/v1/keys", body, "172.18.0.2:1", "8.8.8.8"); rec.Code != 403 {
		t.Errorf("client public derrière proxy: %d", rec.Code)
	}
	if rec := adminReq(hp, "POST", "/api/v1/keys", body, "172.18.0.2:1", "8.8.8.8, 192.168.0.9"); rec.Code != 201 {
		t.Errorf("dernière entrée XFF = LAN: %d", rec.Code)
	}
	if rec := adminReq(hp, "POST", "/api/v1/keys", body, "172.18.0.2:1", "192.168.0.9, 8.8.8.8"); rec.Code != 403 {
		t.Errorf("XFF forgé en tête ne doit pas passer: %d", rec.Code)
	}
	if rec := adminReq(hp, "POST", "/api/v1/keys", body, "172.18.0.2:1", ""); rec.Code != 403 {
		t.Errorf("XFF absent derrière proxy: %d", rec.Code)
	}
}

func TestAdminEmissionListeRevocation(t *testing.T) {
	_, h := newAdminHandler(newKeyStore(""), Config{})
	rec := adminReq(h, "POST", "/api/v1/keys", `{"device_id":"device-0001","name":"Pixel 7"}`, "192.168.0.5:1", "")
	var out issueResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	if rec.Code != 201 || out.Key == "" || out.ID == "" || rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("émission: %d %s", rec.Code, rec.Body)
	}
	rec = adminReq(h, "GET", "/api/v1/keys", "", "192.168.0.5:1", "")
	if strings.Contains(rec.Body.String(), out.Key) || strings.Contains(rec.Body.String(), "hash") || !strings.Contains(rec.Body.String(), "Pixel 7") {
		t.Errorf("la liste ne doit montrer ni clé ni empreinte: %s", rec.Body)
	}
	if rec = adminReq(h, "POST", "/api/v1/keys", `{"device_id":"device-0002","name":"x","extra":1}`, "192.168.0.5:1", ""); rec.Code != 400 {
		t.Errorf("champ inconnu: %d", rec.Code)
	}
	if rec = adminReq(h, "DELETE", "/api/v1/keys/"+out.ID, "", "192.168.0.5:1", ""); rec.Code != 204 {
		t.Errorf("révocation: %d", rec.Code)
	}
	if rec = adminReq(h, "DELETE", "/api/v1/keys/"+out.ID, "", "192.168.0.5:1", ""); rec.Code != 404 {
		t.Errorf("révocation répétée: %d", rec.Code)
	}
}

// --- API d'écriture ---------------------------------------------------------

func writeSetup(t *testing.T) (http.Handler, *App, string) {
	t.Helper()
	app := testApp(t)
	_, key, err := app.keys.Issue("device-0001", "Pixel 7", t0)
	if err != nil {
		t.Fatal(err)
	}
	srv, h := newServer(app, fstest.MapFS{"index.html": {Data: []byte("x")}})
	srv.now = func() time.Time { return t0 }
	return h, app, key
}

func doReq(h http.Handler, method, path, body, key string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.RemoteAddr = "203.0.113.7:4444"
	if key != "" {
		r.Header.Set("Authorization", "Bearer "+key)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, r)
	return rec
}

func TestEcritureExigeUneCle(t *testing.T) {
	h, app, key := writeSetup(t)
	body := `{"prices":{"gazole":2.05}}`
	if rec := doReq(h, "PUT", "/api/v1/stations/a/prices", body, ""); rec.Code != 401 {
		t.Errorf("sans clé: %d", rec.Code)
	}
	if rec := doReq(h, "PUT", "/api/v1/stations/a/prices", body, "ck_pasunevraiecle0000000000"); rec.Code != 401 {
		t.Errorf("fausse clé: %d", rec.Code)
	}
	if rec := doReq(h, "DELETE", "/api/v1/stations/a/prices/gazole", "", ""); rec.Code != 401 {
		t.Errorf("DELETE sans clé: %d", rec.Code)
	}
	if len(app.store.Snapshot().Manual) != 0 {
		t.Fatal("rien ne doit être écrit sans authentification")
	}
	if rec := doReq(h, "PUT", "/api/v1/stations/a/prices", body, key); rec.Code != 200 {
		t.Fatalf("avec clé: %d %s", rec.Code, rec.Body)
	}
}

func TestEcritureBloqueApresEchecs(t *testing.T) {
	h, _, key := writeSetup(t)
	for i := 0; i < 10; i++ {
		doReq(h, "PUT", "/api/v1/stations/a/prices", `{}`, "ck_mauvaisemauvaisemauvaise")
	}
	// Même la bonne clé est refusée depuis l'IP bloquée (anti force brute).
	if rec := doReq(h, "PUT", "/api/v1/stations/a/prices", `{"prices":{"gazole":2}}`, key); rec.Code != 429 {
		t.Errorf("IP bloquée: %d", rec.Code)
	}
}

func TestEcritureValidations(t *testing.T) {
	h, _, key := writeSetup(t)
	cases := map[string]struct {
		path, body string
		want       int
	}{
		"station inconnue":  {"/api/v1/stations/zzz/prices", `{"prices":{"gazole":2}}`, 404},
		"carburant inconnu": {"/api/v1/stations/a/prices", `{"prices":{"kerosene":2}}`, 400},
		"faute de frappe":   {"/api/v1/stations/a/prices", `{"prices":{"gazole":20.5}}`, 400},
		"prix nul":          {"/api/v1/stations/a/prices", `{"prices":{"gazole":0}}`, 400},
		"vide":              {"/api/v1/stations/a/prices", `{"prices":{}}`, 400},
		"json cassé":        {"/api/v1/stations/a/prices", `{`, 400},
		"champ inconnu":     {"/api/v1/stations/a/prices", `{"prices":{"gazole":2},"x":1}`, 400},
		"futur":             {"/api/v1/stations/a/prices", `{"prices":{"gazole":2},"observed_at":"2026-11-25T00:00:00Z"}`, 400},
		"trop ancien":       {"/api/v1/stations/a/prices", `{"prices":{"gazole":2},"observed_at":"2026-01-01T00:00:00Z"}`, 400},
		"ok avec date":      {"/api/v1/stations/a/prices", `{"prices":{"gazole":2.05},"observed_at":"2026-11-18T08:00:00Z"}`, 200},
	}
	for name, c := range cases {
		if rec := doReq(h, "PUT", c.path, c.body, key); rec.Code != c.want {
			t.Errorf("%s: %d (attendu %d) %s", name, rec.Code, c.want, rec.Body)
		}
	}
	big := `{"prices":{"gazole":2},"observed_at":"` + strings.Repeat("x", 5000) + `"}`
	if rec := doReq(h, "PUT", "/api/v1/stations/a/prices", big, key); rec.Code != 400 {
		t.Errorf("corps trop gros: %d", rec.Code)
	}
}

func getStation(t *testing.T, h http.Handler, id string) stationView {
	t.Helper()
	rec := doReq(h, "GET", "/api/v1/prices", "", "")
	var resp pricesResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	for _, s := range resp.Stations {
		if s.ID == id {
			return s
		}
	}
	t.Fatalf("station %s absente", id)
	return stationView{}
}

func TestCorrectionManuelleVsSource(t *testing.T) {
	h, app, key := writeSetup(t)
	// Source : gazole 2,10 relevé il y a 30 h.
	app.merge(map[string]*StationData{"a": {Checked: t0, Prices: map[string]PricePoint{"gazole": {2.10, t0.Add(-30 * time.Hour)}}}})

	// La correction (maintenant) est plus récente : elle gagne et est marquée « manual ».
	if rec := doReq(h, "PUT", "/api/v1/stations/a/prices", `{"prices":{"gazole":2.019}}`, key); rec.Code != 200 {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	p := getStation(t, h, "a").Prices["gazole"]
	if p.Price != 2.019 || p.Origin != "manual" || p.Level != "good" {
		t.Errorf("correction non prise en compte: %+v", p)
	}
	// Une donnée de source plus récente la remplace et purge la correction.
	app.merge(map[string]*StationData{"a": {Checked: t0, Prices: map[string]PricePoint{"gazole": {2.07, t0.Add(time.Hour)}}}})
	p = getStation(t, h, "a").Prices["gazole"]
	if p.Price != 2.07 || p.Origin != "source" {
		t.Errorf("la source plus récente doit gagner: %+v", p)
	}
	if len(app.store.Snapshot().Manual["a"]) != 0 {
		t.Error("correction périmée non purgée")
	}
	// Une station sans prix de source (ex. Le Seau) peut recevoir une saisie.
	doReq(h, "PUT", "/api/v1/stations/c/prices", `{"prices":{"e10":1.899}}`, key)
	if p := getStation(t, h, "c").Prices["e10"]; p.Price != 1.899 || p.Origin != "manual" {
		t.Errorf("saisie sur station sans source: %+v", p)
	}
	// Suppression de la correction.
	if rec := doReq(h, "DELETE", "/api/v1/stations/c/prices/e10", "", key); rec.Code != 200 {
		t.Errorf("delete: %d", rec.Code)
	}
	if _, ok := getStation(t, h, "c").Prices["e10"]; ok {
		t.Error("correction non supprimée")
	}
}

func TestPersistanceDesCorrections(t *testing.T) {
	dir := t.TempDir()
	s := newStore(dir)
	_ = s.Update(func(st *State) {
		st.Manual["a"] = map[string]ManualPoint{"gazole": {Value: 2.0, Updated: t0, By: "Pixel"}}
	})
	s2 := newStore(dir)
	if err := s2.Load(); err != nil {
		t.Fatal(err)
	}
	if s2.Snapshot().Manual["a"]["gazole"].Value != 2.0 {
		t.Error("correction non rechargée")
	}
}

func TestFailTracker(t *testing.T) {
	f := newFailTracker(3, time.Minute)
	for i := 0; i < 3; i++ {
		f.fail("1.1.1.1", t0)
	}
	if !f.blocked("1.1.1.1", t0) || f.blocked("2.2.2.2", t0) {
		t.Error("blocage par IP")
	}
	if f.blocked("1.1.1.1", t0.Add(2*time.Minute)) {
		t.Error("le blocage doit expirer")
	}
}
