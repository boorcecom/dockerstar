package main

import (
	"encoding/json"
	"io/fs"
	"net/http"
	"sort"
	"strings"
	"time"
)

// Server : handlers HTTP en lecture seule.
type Server struct {
	app   *App
	lim   *limiter
	fails *failTracker
	now   func() time.Time
}

func newServer(app *App, web fs.FS) (*Server, http.Handler) {
	s := &Server{app: app, lim: newLimiter(app.cfg.RateRPS, app.cfg.RateBurst), fails: newFailTracker(10, 10*time.Minute), now: time.Now}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	mux.HandleFunc("GET /api/v1/prices", s.handlePrices)
	// Écritures : réservées aux appareils porteurs d'une clé d'API.
	mux.HandleFunc("PUT /api/v1/stations/{id}/prices", s.withKey(s.handlePutPrices))
	mux.HandleFunc("DELETE /api/v1/stations/{id}/prices/{fuel}", s.withKey(s.handleDeletePrice))
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "route inconnue")
	})
	mux.Handle("/", staticHandler(web))
	return s, s.middleware(mux)
}

func staticHandler(web fs.FS) http.Handler {
	fileServer := http.FileServerFS(web)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "méthode non autorisée", http.StatusMethodNotAllowed)
			return
		}
		w.Header().Set("Cache-Control", "no-cache") // le service worker gère le hors-ligne
		fileServer.ServeHTTP(w, r)
	})
}

func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		if strings.HasPrefix(r.URL.Path, "/api/") {
			if s.app.cfg.CORSOrigin != "" {
				h.Set("Access-Control-Allow-Origin", s.app.cfg.CORSOrigin)
				h.Set("Vary", "Origin")
			}
			if r.URL.Path != "/api/v1/health" && !s.lim.allow(clientIP(r, s.app.cfg.TrustProxy), s.now()) {
				h.Set("Retry-After", "1")
				writeError(w, http.StatusTooManyRequests, "trop de requêtes")
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func writeError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

// --- /api/v1/prices -------------------------------------------------------

type fuelView struct {
	Price       float64   `json:"price"`   // €/L
	Updated     time.Time `json:"updated"` // date du prix déclarée par la source (UTC)
	AgeHours    float64   `json:"age_hours"`
	Reliability int       `json:"reliability"` // 0–100
	Level       string    `json:"level"`       // good | medium | low
	Origin      string    `json:"origin"`      // source | manual (correction saisie à la main)
}

type stationView struct {
	ID         string              `json:"id"`
	Name       string              `json:"name"`
	Town       string              `json:"town"`
	Country    string              `json:"country"`
	Lat        float64             `json:"lat,omitempty"`
	Lon        float64             `json:"lon,omitempty"`
	DistanceKm *float64            `json:"distance_km,omitempty"` // absent si la position est inconnue
	Checked    *time.Time          `json:"checked,omitempty"`     // dernier passage réussi sur la source
	Prices     map[string]fuelView `json:"prices"`
}

type sourceView struct {
	Label       string     `json:"label"`
	OK          bool       `json:"ok"`
	LastSuccess *time.Time `json:"last_success,omitempty"`
	NextRun     *time.Time `json:"next_run,omitempty"`
}

type pricesResponse struct {
	GeneratedAt time.Time             `json:"generated_at"`
	Origin      Origin                `json:"origin"`
	Fuels       []FuelInfo            `json:"fuels"`
	Stations    []stationView         `json:"stations"`
	Best        map[string]string     `json:"best"` // carburant -> id de la station la moins chère (prix non « low »)
	Sources     map[string]sourceView `json:"sources"`
}

func (s *Server) buildPrices(now time.Time) pricesResponse {
	snap := s.app.store.Snapshot()
	resp := pricesResponse{
		GeneratedAt: now.UTC(),
		Origin:      s.app.sf.Origin,
		Fuels:       fuels,
		Stations:    []stationView{},
		Best:        map[string]string{},
		Sources:     map[string]sourceView{},
	}
	for _, st := range s.app.sf.Stations {
		v := stationView{ID: st.ID, Name: st.Name, Town: st.Town, Country: st.Country, Lat: st.Lat, Lon: st.Lon, Prices: map[string]fuelView{}}
		if d := snap.Stations[st.ID]; d != nil {
			if v.Lat == 0 && v.Lon == 0 {
				v.Lat, v.Lon = d.Lat, d.Lon
			}
			if !d.Checked.IsZero() {
				c := d.Checked.UTC()
				v.Checked = &c
			}
			for key, p := range d.Prices {
				if validFuel(key) {
					v.Prices[key] = makeFuelView(p, "source", now)
				}
			}
		}
		// Corrections manuelles : la plus récente des deux données gagne (à égalité, la source).
		for key, m := range snap.Manual[st.ID] {
			if !validFuel(key) {
				continue
			}
			if cur, ok := v.Prices[key]; ok && !m.Updated.After(cur.Updated) {
				continue
			}
			v.Prices[key] = makeFuelView(PricePoint{Value: m.Value, Updated: m.Updated}, "manual", now)
		}
		if v.Lat != 0 || v.Lon != 0 {
			d := round1(haversineKm(resp.Origin.Lat, resp.Origin.Lon, v.Lat, v.Lon))
			v.DistanceKm = &d
		}
		resp.Stations = append(resp.Stations, v)
	}
	sort.SliceStable(resp.Stations, func(i, j int) bool { // position inconnue : en dernier
		a, b := resp.Stations[i].DistanceKm, resp.Stations[j].DistanceKm
		if a == nil || b == nil {
			return a != nil && b == nil
		}
		return *a < *b
	})

	for _, f := range fuels {
		bestID, bestPrice := "", 0.0
		for _, st := range resp.Stations {
			p, ok := st.Prices[f.Key]
			if !ok || p.Level == "low" {
				continue
			}
			if bestID == "" || p.Price < bestPrice {
				bestID, bestPrice = st.ID, p.Price
			}
		}
		if bestID != "" {
			resp.Best[f.Key] = bestID
		}
	}

	labels := map[string]string{"fr": "France – prix-carburants.gouv.fr (open data)", "be": "Belgique – carbu.com"}
	for name, label := range labels {
		if name == "be" && !s.app.cfg.BEEnabled {
			continue
		}
		src, known := snap.Sources[name]
		sv := sourceView{Label: label}
		if known {
			sv.OK = !src.Failed && !src.LastSuccess.IsZero()
			if !src.LastSuccess.IsZero() {
				t := src.LastSuccess.UTC()
				sv.LastSuccess = &t
			}
			if !src.NextRun.IsZero() {
				t := src.NextRun.UTC()
				sv.NextRun = &t
			}
		}
		resp.Sources[name] = sv
	}
	return resp
}

func makeFuelView(p PricePoint, origin string, now time.Time) fuelView {
	age := now.Sub(p.Updated)
	if age < 0 {
		age = 0
	}
	score, level := reliability(age)
	return fuelView{Price: p.Value, Updated: p.Updated.UTC(), AgeHours: round1(age.Hours()), Reliability: score, Level: level, Origin: origin}
}

func round1(f float64) float64 { return float64(int(f*10+0.5)) / 10 }

func (s *Server) handlePrices(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=60")
	_ = json.NewEncoder(w).Encode(s.buildPrices(s.now()))
}
