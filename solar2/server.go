package main

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	maxRangeDays = 400
	minDate      = "2000-01-01"
)

// Server : handlers HTTP, indépendants de la base grâce à dataStore.
type Server struct {
	store dataStore
	cfg   Config
	cache *ttlCache
	lim   *limiter
	now   func() time.Time
}

func newServer(store dataStore, cfg Config, web fs.FS) (*Server, http.Handler) {
	s := &Server{
		store: store,
		cfg:   cfg,
		cache: newTTLCache(512),
		lim:   newLimiter(cfg.RateRPS, cfg.RateBurst),
		now:   time.Now,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", s.handleHealth)
	mux.HandleFunc("GET /api/v1/current", s.handleCurrent)
	mux.HandleFunc("GET /api/v1/day", s.handleDay)
	mux.HandleFunc("GET /api/v1/days", s.handleDays)
	mux.HandleFunc("GET /api/v1/summary", s.handleSummary)
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

// middleware : en-têtes de sécurité, CORS optionnel, limitation de débit sur /api/.
func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")

		if strings.HasPrefix(r.URL.Path, "/api/") {
			if s.cfg.CORSOrigin != "" {
				h.Set("Access-Control-Allow-Origin", s.cfg.CORSOrigin)
				h.Set("Vary", "Origin")
			}
			if r.URL.Path != "/api/v1/health" && !s.lim.allow(clientIP(r, s.cfg.TrustProxy), s.now()) {
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

// serve renvoie la réponse JSON mise en cache pendant ttl (ou la calcule).
func (s *Server) serve(w http.ResponseWriter, r *http.Request, key string, ttl time.Duration, fn func(ctx context.Context) (any, error)) {
	body, err := s.cache.do(key, ttl, s.now(), func() ([]byte, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		v, err := fn(ctx)
		if err != nil {
			return nil, err
		}
		return json.Marshal(v)
	})
	if err != nil {
		if errors.Is(err, errNoData) {
			writeError(w, http.StatusNotFound, "aucune donnée")
			return
		}
		slog.Error("requête échouée", "path", r.URL.Path, "err", err) // détail dans les logs, jamais au client
		writeError(w, http.StatusInternalServerError, "erreur interne")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age="+strconv.Itoa(int(ttl.Seconds())))
	_, _ = w.Write(body)
}

func (s *Server) parseDay(v string, def time.Time) (time.Time, error) {
	if v == "" {
		return def, nil
	}
	t, err := time.ParseInLocation("2006-01-02", v, s.cfg.TZ)
	if err != nil {
		return t, errors.New("date invalide (attendu AAAA-MM-JJ)")
	}
	min, _ := time.ParseInLocation("2006-01-02", minDate, s.cfg.TZ)
	if t.Before(min) || t.After(s.today().AddDate(0, 0, 2)) {
		return t, errors.New("date hors limites")
	}
	return t, nil
}

func (s *Server) today() time.Time {
	t, _ := dayBounds(s.now().In(s.cfg.TZ), s.cfg.TZ)
	return t
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.store.Ping(ctx); err != nil {
		slog.Error("ping base", "err", err)
		writeError(w, http.StatusServiceUnavailable, "base indisponible")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func (s *Server) handleCurrent(w http.ResponseWriter, r *http.Request) {
	s.serve(w, r, "current", 30*time.Second, func(ctx context.Context) (any, error) {
		return s.store.Current(ctx, s.now())
	})
}

func (s *Server) handleDay(w http.ResponseWriter, r *http.Request) {
	today := s.today()
	day, err := s.parseDay(r.URL.Query().Get("date"), today)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	ttl := time.Hour // un jour passé ne change plus
	if !day.Before(today) {
		ttl = 60 * time.Second
	}
	s.serve(w, r, "day:"+day.Format("2006-01-02"), ttl, func(ctx context.Context) (any, error) {
		return s.store.Day(ctx, day)
	})
}

func (s *Server) handleDays(w http.ResponseWriter, r *http.Request) {
	today := s.today()
	q := r.URL.Query()
	to, err := s.parseDay(q.Get("to"), today)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	from, err := s.parseDay(q.Get("from"), to.AddDate(0, 0, -29))
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if from.After(to) || to.Sub(from) > maxRangeDays*24*time.Hour {
		writeError(w, http.StatusBadRequest, "intervalle invalide (max "+strconv.Itoa(maxRangeDays)+" jours)")
		return
	}
	key := "days:" + from.Format("2006-01-02") + ":" + to.Format("2006-01-02")
	s.serve(w, r, key, 5*time.Minute, func(ctx context.Context) (any, error) {
		return s.store.Days(ctx, from, to)
	})
}

func (s *Server) handleSummary(w http.ResponseWriter, r *http.Request) {
	s.serve(w, r, "summary", 5*time.Minute, func(ctx context.Context) (any, error) {
		return s.store.Summary(ctx, s.now())
	})
}
