package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"testing/fstest"
	"time"
)

func TestSunElevation(t *testing.T) {
	lat, lon := 48.8566, 2.3522 // Paris
	cases := []struct {
		name string
		day  string
		want float64
	}{
		{"solstice d'été", "2024-06-21", 90 - lat + 23.44},
		{"solstice d'hiver", "2024-12-21", 90 - lat - 23.44},
		{"équinoxe", "2024-03-20", 90 - lat},
	}
	for _, c := range cases {
		d, _ := time.Parse("2006-01-02", c.day)
		got := sunMaxElevation(d.Unix(), d.Unix()+86400, lat, lon)
		if diff := got - c.want; diff < -0.6 || diff > 0.6 {
			t.Errorf("%s: max %.2f°, attendu ~%.2f°", c.name, got, c.want)
		}
	}
	// minuit : soleil sous l'horizon
	d, _ := time.Parse("2006-01-02 15:04", "2024-06-21 23:30")
	if v := sunElevation(d.Unix(), lat, lon); v > -10 {
		t.Errorf("minuit: %.1f°, attendu très négatif", v)
	}
}

func TestLimiter(t *testing.T) {
	l := newLimiter(1, 3)
	now := time.Now()
	for i := 0; i < 3; i++ {
		if !l.allow("a", now) {
			t.Fatalf("requête %d refusée dans la rafale", i)
		}
	}
	if l.allow("a", now) {
		t.Fatal("la 4e requête immédiate aurait dû être refusée")
	}
	if !l.allow("b", now) {
		t.Fatal("une autre IP ne doit pas être limitée")
	}
	if !l.allow("a", now.Add(1100*time.Millisecond)) {
		t.Fatal("un jeton doit être revenu après 1 s")
	}
}

func TestClientIP(t *testing.T) {
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "10.0.0.2:5555"
	r.Header.Set("X-Forwarded-For", "6.6.6.6, 203.0.113.9")
	if got := clientIP(r, false); got != "10.0.0.2" {
		t.Errorf("sans proxy de confiance: %s", got)
	}
	if got := clientIP(r, true); got != "203.0.113.9" {
		t.Errorf("avec proxy de confiance: %s (une IP forgée en tête ne doit pas compter)", got)
	}
}

func TestCacheSingleFlight(t *testing.T) {
	c := newTTLCache(10)
	var calls int32
	now := time.Now()
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = c.do("k", time.Minute, now, func() ([]byte, error) {
				atomic.AddInt32(&calls, 1)
				time.Sleep(20 * time.Millisecond)
				return []byte("x"), nil
			})
		}()
	}
	wg.Wait()
	if calls != 1 {
		t.Errorf("fn appelée %d fois, attendu 1", calls)
	}
	// expiré -> recalcul
	_, _ = c.do("k", time.Minute, now.Add(2*time.Minute), func() ([]byte, error) {
		atomic.AddInt32(&calls, 1)
		return []byte("y"), nil
	})
	if calls != 2 {
		t.Errorf("après expiration, attendu 2 appels, obtenu %d", calls)
	}
	// les erreurs ne sont pas mises en cache
	_, _ = c.do("e", time.Minute, now, func() ([]byte, error) { return nil, errors.New("boom") })
	_, err := c.do("e", time.Minute, now, func() ([]byte, error) { return []byte("ok"), nil })
	if err != nil {
		t.Error("une erreur ne doit pas être mise en cache")
	}
}

type fakeStore struct {
	dayCalls int32
	noData   bool
	pingErr  error
}

func (f *fakeStore) Ping(ctx context.Context) error { return f.pingErr }
func (f *fakeStore) Current(ctx context.Context, now time.Time) (*Current, error) {
	if f.noData {
		return nil, errNoData
	}
	return &Current{At: now.Unix() - 60, AgeSeconds: 60, Watts: 1234}, nil
}
func (f *fakeStore) Day(ctx context.Context, day time.Time) (*DayResponse, error) {
	atomic.AddInt32(&f.dayCalls, 1)
	return &DayResponse{Date: day.Format("2006-01-02"), Points: [][2]int64{}}, nil
}
func (f *fakeStore) Days(ctx context.Context, from, to time.Time) (*DaysResponse, error) {
	return &DaysResponse{From: from.Format("2006-01-02"), To: to.Format("2006-01-02"), Days: []DayTotal{}}, nil
}
func (f *fakeStore) Summary(ctx context.Context, now time.Time) (*SummaryResponse, error) {
	return &SummaryResponse{}, nil
}

func testServer(t *testing.T, st *fakeStore, burst int) (*Server, http.Handler) {
	t.Helper()
	loc, _ := time.LoadLocation("Europe/Paris")
	cfg := Config{TZ: loc, RateRPS: 1, RateBurst: burst}
	web := fstest.MapFS{"index.html": {Data: []byte("<html>ok</html>")}}
	s, h := newServer(st, cfg, web)
	s.now = func() time.Time { return time.Date(2025, 7, 1, 12, 0, 0, 0, loc) }
	return s, h
}

func get(h http.Handler, url string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("GET", url, nil)
	r.RemoteAddr = "192.0.2.1:1234"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestHandlers(t *testing.T) {
	st := &fakeStore{}
	_, h := testServer(t, st, 100)

	w := get(h, "/api/v1/current")
	if w.Code != 200 {
		t.Fatalf("current: %d", w.Code)
	}
	var c Current
	if err := json.Unmarshal(w.Body.Bytes(), &c); err != nil || c.Watts != 1234 {
		t.Errorf("current: %v %+v", err, c)
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/json; charset=utf-8" {
		t.Errorf("Content-Type = %q", ct)
	}

	for _, u := range []string{
		"/api/v1/day?date=pas-une-date",
		"/api/v1/day?date=1999-01-01",
		"/api/v1/day?date=2030-01-01",
		"/api/v1/days?from=2025-01-01&to=2025-12-31&x=1", // > 400 j ? non : 364 j, valide
	} {
		w := get(h, u)
		wantBad := u != "/api/v1/days?from=2025-01-01&to=2025-12-31&x=1"
		if wantBad && w.Code != 400 {
			t.Errorf("%s: code %d, attendu 400", u, w.Code)
		}
	}
	if w := get(h, "/api/v1/days?from=2023-01-01&to=2025-06-01"); w.Code != 400 {
		t.Errorf("intervalle trop long accepté: %d", w.Code)
	}
	if w := get(h, "/api/v1/days?from=2025-06-10&to=2025-06-01"); w.Code != 400 {
		t.Errorf("from > to accepté: %d", w.Code)
	}
	// injection : ne doit jamais atteindre le store
	if w := get(h, "/api/v1/day?date=2025-01-01'%20OR%201=1"); w.Code != 400 {
		t.Errorf("date malformée acceptée: %d", w.Code)
	}
}

func TestDayCacheTTL(t *testing.T) {
	st := &fakeStore{}
	_, h := testServer(t, st, 100)
	for i := 0; i < 5; i++ {
		get(h, "/api/v1/day?date=2025-06-30")
	}
	if st.dayCalls != 1 {
		t.Errorf("5 requêtes identiques -> %d appels base, attendu 1", st.dayCalls)
	}
	w := get(h, "/api/v1/day?date=2025-06-30")
	if cc := w.Header().Get("Cache-Control"); cc != "public, max-age=3600" {
		t.Errorf("jour passé: %q", cc)
	}
	w = get(h, "/api/v1/day")
	if cc := w.Header().Get("Cache-Control"); cc != "public, max-age=60" {
		t.Errorf("jour courant: %q", cc)
	}
}

func TestErrorsDoNotLeak(t *testing.T) {
	st := &fakeStore{noData: true}
	_, h := testServer(t, st, 100)
	if w := get(h, "/api/v1/current"); w.Code != 404 {
		t.Errorf("sans donnée: %d", w.Code)
	}
	st.pingErr = errors.New("dial tcp mariadb:3306: password=secret")
	w := get(h, "/api/v1/health")
	if w.Code != 503 || len(w.Body.String()) > 60 || contains(w.Body.String(), "secret") {
		t.Errorf("health: %d %q", w.Code, w.Body.String())
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func TestRateLimitAndHeaders(t *testing.T) {
	_, h := testServer(t, &fakeStore{}, 3)
	var last *httptest.ResponseRecorder
	for i := 0; i < 5; i++ {
		last = get(h, "/api/v1/current")
	}
	if last.Code != 429 || last.Header().Get("Retry-After") == "" {
		t.Errorf("limitation: code %d", last.Code)
	}
	// health n'est pas limité ; les fichiers statiques non plus
	if w := get(h, "/api/v1/health"); w.Code != 200 {
		t.Errorf("health: %d", w.Code)
	}
	w := get(h, "/")
	if w.Code != 200 || w.Header().Get("X-Content-Type-Options") != "nosniff" || w.Header().Get("Content-Security-Policy") == "" {
		t.Errorf("statique: %d %v", w.Code, w.Header())
	}
	// autre IP (la première a épuisé sa rafale)
	r := httptest.NewRequest("GET", "/api/v1/inconnu", nil)
	r.RemoteAddr = "192.0.2.77:1"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, r)
	if rec.Code != 404 {
		t.Errorf("route inconnue: %d", rec.Code)
	}
	r = httptest.NewRequest("POST", "/", nil)
	r.RemoteAddr = "192.0.2.78:1"
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, r)
	if rec.Code != 405 {
		t.Errorf("POST sur le statique: %d", rec.Code)
	}
}
