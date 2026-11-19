package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
	"time"
)

const carbuPage = `
<h2 class="title-section">Brandstoffen <a href="x"></a></h2>
<div class="row">
 <div class="col-xs-12 col-sm-6"><div class="panel panel-default">
 <div class="panel-heading" style="text-align:center">
 <h2 class="title">Super 95 (E10)</h2>
 </div>
 <div class="panel-body" style="text-align:center">

 <h1 class="price">1,649 &euro;/L</h1>
 <p class="sources">19/11/26 Nick</p>
 </div></div></div>
 <div class="col-xs-12 col-sm-6"><div class="panel panel-default">
 <div class="panel-heading" style="text-align:center">
 <h2 class="title">Super 98 (E5)</h2>
 </div>
 <div class="panel-body" style="text-align:center">
 <h1 class="price">1,799 &euro;/L</h1>
 <p class="sources">19/11/26 Nick</p>
 </div></div></div>
 <div class="col-xs-12 col-sm-6"><div class="panel panel-default">
 <div class="panel-heading" style="text-align:center">
 <h2 class="title">Diesel (B7)</h2>
 </div>
 <div class="panel-body" style="text-align:center">
 <h1 class="price">2,129 &euro;/L</h1>
 <p class="sources">17/11/26 Nick</p>
 </div></div></div>
 <div class="col-xs-12 col-sm-6"><div class="panel panel-default">
 <div class="panel-heading" style="text-align:center">
 <h2 class="title">Super 95 (E5)</h2>
 </div>
 <div class="panel-body" style="text-align:center">
 <h1 class="price">Prijs niet beschikbaar</h1>
 </div></div></div>
</div>`

func paris(t *testing.T) *time.Location {
	t.Helper()
	l, err := time.LoadLocation("Europe/Paris")
	if err != nil {
		t.Fatal(err)
	}
	return l
}

func TestParseCarbu(t *testing.T) {
	got, err := parseCarbu([]byte(carbuPage), paris(t))
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 { // Super 98 ignoré, Super 95 (E5) sans prix ignoré
		t.Fatalf("attendu e10 + gazole, obtenu %v", got)
	}
	if p := got["e10"]; p.Value != 1.649 || p.Updated.Format("2006-01-02T15") != "2026-11-19T11" { // 12:00 Paris = 11:00 UTC
		t.Errorf("e10 = %+v", p)
	}
	if p := got["gazole"]; p.Value != 2.129 || p.Updated.Day() != 17 {
		t.Errorf("gazole = %+v", p)
	}
	if _, ok := got["sp95"]; ok {
		t.Error("« Prijs niet beschikbaar » ne doit pas produire de prix")
	}
}

func TestParseCarbuFormatInattendu(t *testing.T) {
	if _, err := parseCarbu([]byte("<html>autre chose</html>"), paris(t)); err == nil {
		t.Fatal("une page sans bloc carburant doit être une erreur (mise en page modifiée)")
	}
}

func TestParseCarbuRejetteLesAberrations(t *testing.T) {
	page := strings.ReplaceAll(carbuPage, "2,129", "92,129")
	got, _ := parseCarbu([]byte(page), paris(t))
	if _, ok := got["gazole"]; ok {
		t.Error("un prix > 10 €/L doit être ignoré")
	}
	page = strings.ReplaceAll(carbuPage, "17/11/26", "31/02/26")
	got, _ = parseCarbu([]byte(page), paris(t))
	if _, ok := got["gazole"]; ok {
		t.Error("une date impossible doit être ignorée")
	}
}

func TestReliability(t *testing.T) {
	cases := []struct {
		age   time.Duration
		level string
		min   int
		max   int
	}{
		{0, "good", 100, 100},
		{24 * time.Hour, "good", 100, 100},
		{48 * time.Hour, "good", 88, 92},
		{3 * 24 * time.Hour, "good", 75, 80},
		{5 * 24 * time.Hour, "medium", 55, 65},
		{8 * 24 * time.Hour, "low", 20, 35},
		{10 * 24 * time.Hour, "low", 0, 0},
		{40 * 24 * time.Hour, "low", 0, 0},
	}
	for _, c := range cases {
		s, l := reliability(c.age)
		if l != c.level || s < c.min || s > c.max {
			t.Errorf("age %v: score %d niveau %s (attendu %s %d–%d)", c.age, s, l, c.level, c.min, c.max)
		}
	}
}

func TestParseStationsValidation(t *testing.T) {
	if _, err := parseStations([]byte(`{"stations":[{"id":"a","source":"x","ref":"1","name":"n"}]}`)); err == nil {
		t.Error("source inconnue acceptée")
	}
	if _, err := parseStations([]byte(`{"stations":[{"id":"a","source":"fr","ref":"1","name":"n"},{"id":"a","source":"fr","ref":"2","name":"m"}]}`)); err == nil {
		t.Error("id en double accepté")
	}
	if _, err := parseStations(stationsEmbed); err != nil {
		t.Errorf("stations.json embarqué invalide : %v", err)
	}
}

func TestFetchFR(t *testing.T) {
	var gotWhere string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotWhere = r.URL.Query().Get("where")
		fmt.Fprint(w, `{"total_count":2,"results":[
		 {"id":59940001,"geom":{"lon":2.70632,"lat":50.64413},"gazole_prix":2.079,"gazole_maj":"2026-11-13T16:37:06+00:00","e10_prix":1.925,"e10_maj":"2026-11-13T16:37:07+00:00","sp95_prix":null,"sp95_maj":null,"e85_prix":0.869,"e85_maj":"2026-11-13T16:37:07+00:00"},
		 {"id":99999999,"gazole_prix":1.0,"gazole_maj":"2026-11-13T16:37:06+00:00"}]}`)
	}))
	defer ts.Close()
	cfg := Config{FRURL: ts.URL, UserAgent: "test"}
	sts := []Station{{ID: "fr-59940001", Source: "fr", Ref: "59940001"}, {ID: "fr-1", Source: "fr", Ref: "1"}, {ID: "be-1", Source: "carbu", Ref: "9"}}
	got, err := fetchFR(context.Background(), ts.Client(), cfg, sts)
	if err != nil {
		t.Fatal(err)
	}
	if gotWhere != "id in (59940001,1)" {
		t.Errorf("where = %q", gotWhere)
	}
	d := got["fr-59940001"]
	if d == nil || len(got) != 1 || d.Prices["gazole"].Value != 2.079 || d.Prices["e85"].Value != 0.869 || d.Lat == 0 {
		t.Fatalf("résultat inattendu : %+v", got)
	}
	if _, ok := d.Prices["sp95"]; ok {
		t.Error("sp95 null ne doit pas produire de prix")
	}
}

func TestFetchCarbuToleranceAuxEchecs(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/2") {
			http.Error(w, "boom", 500)
			return
		}
		fmt.Fprint(w, carbuPage)
	}))
	defer ts.Close()
	cfg := Config{CarbuBase: ts.URL + "/", UserAgent: "test", TZ: paris(t)}
	sts := []Station{{ID: "be-1", Source: "carbu", Ref: "1"}, {ID: "be-2", Source: "carbu", Ref: "2"}, {ID: "fr-1", Source: "fr", Ref: "1"}}
	got, failed, err := fetchCarbu(context.Background(), ts.Client(), cfg, sts)
	if err != nil || failed != 1 || len(got) != 1 || got["be-1"] == nil {
		t.Fatalf("got=%v failed=%d err=%v", got, failed, err)
	}
	// Tout en échec -> erreur (la source sera marquée en échec, retry plus tard).
	cfg.CarbuBase = ts.URL + "/x/"
	sts = []Station{{ID: "be-2", Source: "carbu", Ref: "2"}}
	if _, _, err := fetchCarbu(context.Background(), ts.Client(), cfg, sts); err == nil {
		t.Error("toutes les stations en échec doivent donner une erreur")
	}
}

func TestDigitsOnly(t *testing.T) {
	if digitsOnly("1508") != "1508" || digitsOnly("../x") != "0" {
		t.Error("digitsOnly")
	}
}

func testApp(t *testing.T) *App {
	t.Helper()
	sf := StationsFile{Origin: Origin{Name: "Origine", Lat: 50.68, Lon: 2.70}, Stations: []Station{
		{ID: "a", Country: "FR", Source: "fr", Ref: "1", Name: "A", Town: "TA"},
		{ID: "b", Country: "BE", Source: "carbu", Ref: "2", Name: "B", Town: "TB", Lat: 50.78, Lon: 2.77},
		{ID: "c", Country: "FR", Source: "fr", Ref: "3", Name: "C", Town: "TC"},
	}}
	cfg := Config{RateRPS: 100, RateBurst: 100, BEEnabled: true}
	return newApp(cfg, sf, newStore(""), newKeyStore(""))
}

func TestPricesAPI(t *testing.T) {
	app := testApp(t)
	now := time.Date(2026, 11, 19, 12, 0, 0, 0, time.UTC)
	app.merge(map[string]*StationData{
		"a": {Lat: 50.64, Lon: 2.70, Checked: now, Prices: map[string]PricePoint{
			"gazole": {2.05, now.Add(-2 * time.Hour)}, "e10": {1.90, now.Add(-12 * 24 * time.Hour)}}},
		"b": {Checked: now, Prices: map[string]PricePoint{
			"gazole": {2.10, now.Add(-30 * time.Hour)}, "e10": {1.65, now.Add(-30 * time.Hour)}}},
	})
	app.store.markAttempt("fr", now, true, now.Add(time.Hour))
	app.store.markAttempt("be", now, false, now.Add(6*time.Hour))

	web := fstest.MapFS{"index.html": {Data: []byte("<h1>ok</h1>")}}
	srv, h := newServer(app, web)
	srv.now = func() time.Time { return now }

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/api/v1/prices", nil))
	if rec.Code != 200 {
		t.Fatalf("code %d", rec.Code)
	}
	var resp pricesResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if len(resp.Stations) != 3 || resp.Stations[0].ID != "a" || resp.Stations[2].ID != "c" { // par distance, inconnue en dernier
		t.Fatalf("stations: %+v", resp.Stations)
	}
	a := resp.Stations[0].Prices
	if a["gazole"].Level != "good" || a["e10"].Level != "low" {
		t.Errorf("fiabilité a: %+v", a)
	}
	if resp.Best["gazole"] != "a" {
		t.Errorf("meilleur gazole = %q", resp.Best["gazole"])
	}
	if resp.Best["e10"] != "b" { // le e10 de « a » est ancien (low) : écarté
		t.Errorf("meilleur e10 = %q (le prix ancien ne doit pas gagner)", resp.Best["e10"])
	}
	if _, ok := resp.Best["e85"]; ok {
		t.Error("pas de meilleur e85 sans donnée")
	}
	if !resp.Sources["fr"].OK || resp.Sources["be"].OK {
		t.Errorf("sources: %+v", resp.Sources)
	}
	// distance calculée depuis l'origine, y compris pour une station sans prix
	for _, s := range resp.Stations {
		if s.ID == "b" && (s.DistanceKm == nil || *s.DistanceKm < 10 || *s.DistanceKm > 15) {
			t.Errorf("distance b = %v", s.DistanceKm)
		}
	}
}

func TestMiddlewareEtStatique(t *testing.T) {
	app := testApp(t)
	web := fstest.MapFS{"index.html": {Data: []byte("<h1>ok</h1>")}}
	_, h := newServer(app, web)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/", nil))
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "ok") || rec.Header().Get("X-Frame-Options") != "DENY" {
		t.Errorf("statique: %d %v", rec.Code, rec.Header())
	}
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("POST", "/", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("POST / = %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/api/v1/inconnu", nil))
	if rec.Code != 404 {
		t.Errorf("route inconnue = %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/api/v1/health", nil))
	if rec.Code != 200 {
		t.Errorf("health = %d", rec.Code)
	}
}

func TestStorePersisteSansHistorique(t *testing.T) {
	dir := t.TempDir()
	s := newStore(dir)
	t0 := time.Date(2026, 11, 19, 0, 0, 0, 0, time.UTC)
	_ = s.Update(func(st *State) {
		st.Stations["a"] = &StationData{Prices: map[string]PricePoint{"gazole": {2.0, t0}}}
	})
	_ = s.Update(func(st *State) { // un nouveau relevé REMPLACE l'ancien
		st.Stations["a"] = &StationData{Prices: map[string]PricePoint{"gazole": {1.9, t0.Add(time.Hour)}}}
	})
	s2 := newStore(dir)
	if err := s2.Load(); err != nil {
		t.Fatal(err)
	}
	got := s2.Snapshot().Stations["a"].Prices["gazole"]
	if got.Value != 1.9 {
		t.Errorf("dernier prix = %v", got)
	}
}

func TestConfigPlancherCarbu(t *testing.T) {
	t.Setenv("CARBU_BE_INTERVAL", "1h")
	if _, err := loadConfig(); err == nil {
		t.Error("un intervalle carbu.com < 12h doit être refusé")
	}
	t.Setenv("CARBU_BE_INTERVAL", "48h")
	c, err := loadConfig()
	if err != nil || c.BEInterval != 48*time.Hour {
		t.Errorf("config par défaut: %v %v", c.BEInterval, err)
	}
}
