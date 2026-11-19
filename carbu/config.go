package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config : paramètres d'exécution (variables d'environnement CARBU_*).
type Config struct {
	Listen       string
	TrustProxy   bool
	CORSOrigin   string
	RateRPS      float64
	RateBurst    int
	DataDir      string        // état courant (un seul fichier, écrasé : aucun historique)
	StationsFile string        // optionnel : remplace la liste de stations embarquée
	FRInterval   time.Duration // rafraîchissement open data France
	BEInterval   time.Duration // passage sur carbu.com (Belgique)
	BEEnabled    bool
	BEPause      time.Duration // pause entre deux fiches carbu.com
	FRURL        string
	CarbuBase    string
	UserAgent    string
	TZ           *time.Location
	// Port de gestion des clés (LAN uniquement). "off" ou vide : désactivé.
	AdminListen     string
	AdminTrustProxy bool
}

const minBEInterval = 12 * time.Hour // garde-fou : on ne scrute jamais carbu.com plus souvent

func loadConfig() (Config, error) {
	c := Config{
		Listen:          getenv("CARBU_LISTEN", ":8080"),
		TrustProxy:      getenv("CARBU_TRUST_PROXY", "false") == "true",
		CORSOrigin:      os.Getenv("CARBU_CORS_ORIGIN"),
		DataDir:         getenv("CARBU_DATA_DIR", "/data"),
		StationsFile:    os.Getenv("CARBU_STATIONS_FILE"),
		BEEnabled:       getenv("CARBU_BE_ENABLED", "true") != "false",
		FRURL:           getenv("CARBU_FR_URL", "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records"),
		CarbuBase:       getenv("CARBU_CARBU_BASE", "https://carbu.com/belgie/index.php/station/1/2/3/"),
		AdminListen:     getenv("CARBU_ADMIN_LISTEN", ":8081"),
		AdminTrustProxy: getenv("CARBU_ADMIN_TRUST_PROXY", "false") == "true",
		UserAgent:       getenv("CARBU_USER_AGENT", "carbu-perso/1.0 (usage personnel ; 1 passage tous les 2 jours)"),
	}
	var err error
	if c.RateRPS, err = envFloat("CARBU_RATE_RPS", 5); err != nil {
		return c, err
	}
	burst, err := envFloat("CARBU_RATE_BURST", 20)
	if err != nil {
		return c, err
	}
	c.RateBurst = int(burst)
	if c.FRInterval, err = envDuration("CARBU_FR_INTERVAL", time.Hour); err != nil {
		return c, err
	}
	if c.BEInterval, err = envDuration("CARBU_BE_INTERVAL", 48*time.Hour); err != nil {
		return c, err
	}
	if c.BEInterval < minBEInterval {
		return c, fmt.Errorf("CARBU_BE_INTERVAL trop court (minimum %s)", minBEInterval)
	}
	if c.FRInterval < 5*time.Minute {
		return c, fmt.Errorf("CARBU_FR_INTERVAL trop court (minimum 5m)")
	}
	if c.BEPause, err = envDuration("CARBU_BE_PAUSE", 8*time.Second); err != nil {
		return c, err
	}
	if !strings.HasPrefix(c.CarbuBase, "https://") || !strings.HasPrefix(c.FRURL, "https://") {
		if os.Getenv("CARBU_ALLOW_HTTP") != "true" { // http:// réservé aux tests
			return c, fmt.Errorf("les URL de sources doivent être en https")
		}
	}
	c.TZ, err = time.LoadLocation(getenv("CARBU_TZ", "Europe/Paris"))
	if err != nil {
		return c, fmt.Errorf("CARBU_TZ: %w", err)
	}
	return c, nil
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envFloat(k string, def float64) (float64, error) {
	v := os.Getenv(k)
	if v == "" {
		return def, nil
	}
	f, err := strconv.ParseFloat(v, 64)
	if err != nil || f <= 0 {
		return 0, fmt.Errorf("%s invalide: %q", k, v)
	}
	return f, nil
}

func envDuration(k string, def time.Duration) (time.Duration, error) {
	v := os.Getenv(k)
	if v == "" {
		return def, nil
	}
	d, err := time.ParseDuration(v)
	if err != nil || d < 0 {
		return 0, fmt.Errorf("%s invalide: %q", k, v)
	}
	return d, nil
}
