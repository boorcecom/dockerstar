package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"math"
	"net/http"
	"strings"
	"time"
)

// --- Écritures authentifiées (port de données, protégées par clé d'API) -------------

type ctxKey struct{}

// withKey exige « Authorization: Bearer ck_… ». Trop d'échecs depuis une IP : 429 pendant 10 min.
func (s *Server) withKey(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		now := s.now()
		ip := clientIP(r, s.app.cfg.TrustProxy)
		if s.fails.blocked(ip, now) {
			w.Header().Set("Retry-After", "600")
			writeError(w, http.StatusTooManyRequests, "trop d'échecs d'authentification")
			return
		}
		tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			s.fails.fail(ip, now)
			w.Header().Set("WWW-Authenticate", `Bearer realm="carbu"`)
			writeError(w, http.StatusUnauthorized, "clé d'API requise")
			return
		}
		info, ok := s.app.keys.Verify(strings.TrimSpace(tok), now)
		if !ok {
			s.fails.fail(ip, now)
			slog.Warn("clé d'API refusée", "ip", ip)
			w.Header().Set("WWW-Authenticate", `Bearer realm="carbu"`)
			writeError(w, http.StatusUnauthorized, "clé d'API invalide")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, info)))
	}
}

const (
	minPrice       = 0.3 // €/L : bornes larges, pour attraper les fautes de frappe (20,5 au lieu de 2,05)
	maxPrice       = 5.0
	maxObservedAge = 30 * 24 * time.Hour
)

type putPricesRequest struct {
	Prices     map[string]float64 `json:"prices"`
	ObservedAt *time.Time         `json:"observed_at"` // optionnel : quand le prix a été vu (défaut : maintenant)
}

func (s *Server) stationExists(id string) bool {
	for _, st := range s.app.sf.Stations {
		if st.ID == id {
			return true
		}
	}
	return false
}

func (s *Server) handlePutPrices(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !s.stationExists(id) {
		writeError(w, http.StatusNotFound, "station inconnue")
		return
	}
	var req putPricesRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "corps JSON invalide")
		return
	}
	if len(req.Prices) == 0 {
		writeError(w, http.StatusBadRequest, "aucun prix")
		return
	}
	now := s.now()
	observed := now
	if req.ObservedAt != nil {
		observed = *req.ObservedAt
		if observed.After(now.Add(5*time.Minute)) || now.Sub(observed) > maxObservedAge {
			writeError(w, http.StatusBadRequest, "observed_at hors limites (30 jours maximum, pas dans le futur)")
			return
		}
		if observed.After(now) {
			observed = now
		}
	}
	for fuel, v := range req.Prices {
		if !validFuel(fuel) {
			writeError(w, http.StatusBadRequest, "carburant inconnu : "+fuel)
			return
		}
		if math.IsNaN(v) || v < minPrice || v > maxPrice {
			writeError(w, http.StatusBadRequest, "prix hors limites pour "+fuel+" (0,30 à 5,00 €/L)")
			return
		}
	}
	info, _ := r.Context().Value(ctxKey{}).(KeyInfo)
	_ = s.app.store.Update(func(st *State) {
		if st.Manual == nil {
			st.Manual = map[string]map[string]ManualPoint{}
		}
		if st.Manual[id] == nil {
			st.Manual[id] = map[string]ManualPoint{}
		}
		for fuel, v := range req.Prices {
			st.Manual[id][fuel] = ManualPoint{Value: math.Round(v*1000) / 1000, Updated: observed.UTC(), By: info.Name}
		}
	})
	slog.Info("correction manuelle", "station", id, "carburants", len(req.Prices), "par", info.Name)
	s.writeStation(w, id)
}

func (s *Server) handleDeletePrice(w http.ResponseWriter, r *http.Request) {
	id, fuel := r.PathValue("id"), r.PathValue("fuel")
	if !s.stationExists(id) || !validFuel(fuel) {
		writeError(w, http.StatusNotFound, "station ou carburant inconnu")
		return
	}
	_ = s.app.store.Update(func(st *State) { delete(st.Manual[id], fuel) })
	info, _ := r.Context().Value(ctxKey{}).(KeyInfo)
	slog.Info("correction manuelle supprimée", "station", id, "carburant", fuel, "par", info.Name)
	s.writeStation(w, id)
}

// writeStation renvoie la station telle qu'elle apparaît ensuite dans /prices.
func (s *Server) writeStation(w http.ResponseWriter, id string) {
	for _, st := range s.buildPrices(s.now()).Stations {
		if st.ID == id {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			_ = json.NewEncoder(w).Encode(st)
			return
		}
	}
	writeError(w, http.StatusNotFound, "station inconnue")
}
