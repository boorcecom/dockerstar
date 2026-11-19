package main

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

// API de gestion des clés : écoute sur un port distinct, réservé au LAN.
//
// Défense en profondeur : le port n'est pas publié par Docker, Apache ne l'expose que sur
// une adresse/port LAN, le pare-feu bloque ce port côté internet, ET le code refuse lui-même
// toute requête dont l'adresse source n'est pas privée (RFC 1918 / ULA / lien local / boucle).

type AdminServer struct {
	keys *KeyStore
	cfg  Config
	lim  *limiter
	now  func() time.Time
}

func newAdminHandler(keys *KeyStore, cfg Config) (*AdminServer, http.Handler) {
	a := &AdminServer{keys: keys, cfg: cfg, lim: newLimiter(2, 10), now: time.Now}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write([]byte(`{"status":"ok","scope":"lan"}`))
	})
	mux.HandleFunc("POST /api/v1/keys", a.handleIssue)
	mux.HandleFunc("GET /api/v1/keys", a.handleList)
	mux.HandleFunc("DELETE /api/v1/keys/{id}", a.handleRevoke)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "route inconnue")
	})
	return a, a.middleware(mux)
}

// isLAN : l'adresse est-elle une adresse de réseau local ?
func isLAN(ip netip.Addr) bool {
	ip = ip.Unmap()
	return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast()
}

// adminClient renvoie l'adresse source à juger. Derrière le reverse proxy, c'est la dernière
// entrée de X-Forwarded-For (celle posée par le proxy) ; son absence est refusée.
func adminClient(r *http.Request, trustProxy bool) (netip.Addr, bool) {
	var s string
	if trustProxy {
		xff := r.Header.Get("X-Forwarded-For")
		if xff == "" {
			return netip.Addr{}, false
		}
		parts := strings.Split(xff, ",")
		s = strings.TrimSpace(parts[len(parts)-1])
	} else {
		s = clientIP(r, false)
	}
	ip, err := netip.ParseAddr(s)
	return ip, err == nil
}

func (a *AdminServer) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Content-Security-Policy", "default-src 'none'")
		ip, ok := adminClient(r, a.cfg.AdminTrustProxy)
		if !ok || !isLAN(ip) {
			slog.Warn("gestion des clés : accès hors LAN refusé", "src", r.RemoteAddr, "xff", r.Header.Get("X-Forwarded-For"))
			writeError(w, http.StatusForbidden, "accès réservé au réseau local")
			return
		}
		if r.URL.Path != "/api/v1/health" && !a.lim.allow(ip.String(), a.now()) {
			h.Set("Retry-After", "1")
			writeError(w, http.StatusTooManyRequests, "trop de requêtes")
			return
		}
		next.ServeHTTP(w, r)
	})
}

type issueRequest struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
}

type issueResponse struct {
	KeyInfo
	Key string `json:"key"` // affichée une seule fois
}

func (a *AdminServer) handleIssue(w http.ResponseWriter, r *http.Request) {
	var req issueRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "corps JSON invalide")
		return
	}
	info, key, err := a.keys.Issue(req.DeviceID, req.Name, a.now())
	switch {
	case errors.Is(err, errBadDevice), errors.Is(err, errBadName):
		writeError(w, http.StatusBadRequest, err.Error())
		return
	case errors.Is(err, errTooManyKeys):
		writeError(w, http.StatusConflict, err.Error())
		return
	case err != nil:
		slog.Error("génération de clé", "err", err)
		writeError(w, http.StatusInternalServerError, "erreur interne")
		return
	}
	slog.Info("clé émise", "id", info.ID, "name", info.Name)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(issueResponse{KeyInfo: info, Key: key})
}

func (a *AdminServer) handleList(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"keys": a.keys.List()})
}

func (a *AdminServer) handleRevoke(w http.ResponseWriter, r *http.Request) {
	if !a.keys.Revoke(r.PathValue("id")) {
		writeError(w, http.StatusNotFound, "clé inconnue")
		return
	}
	slog.Info("clé révoquée", "id", r.PathValue("id"))
	w.WriteHeader(http.StatusNoContent)
}

// decodeJSON : corps limité à 4 Ko, champs inconnus refusés.
func decodeJSON(w http.ResponseWriter, r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return err
	}
	if dec.More() {
		return errors.New("données après le JSON")
	}
	return nil
}
