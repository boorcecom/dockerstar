package main

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config regroupe la configuration, lue uniquement depuis l'environnement.
type Config struct {
	Listen     string
	DBHost     string
	DBPort     string
	DBUser     string
	DBPassword string
	DBName     string
	// TZ est le fuseau dans lequel le collecteur enregistre les colonnes datetime.
	TZ         *time.Location
	InverterID int // 0 = détection automatique (dernier onduleur ayant écrit une mesure)
	Lat, Lon   *float64
	RateRPS    float64
	RateBurst  int
	TrustProxy bool
	CORSOrigin string
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func loadConfig() (Config, error) {
	c := Config{
		Listen:     getenv("SOLAR_LISTEN", ":8080"),
		DBHost:     getenv("SOLAR_DB_HOST", "mariadb"),
		DBPort:     getenv("SOLAR_DB_PORT", "3306"),
		DBUser:     getenv("SOLAR_DB_USER", "solarview"),
		DBPassword: os.Getenv("SOLAR_DB_PASSWORD"),
		DBName:     getenv("SOLAR_DB_NAME", "solar"),
		CORSOrigin: os.Getenv("SOLAR_CORS_ORIGIN"),
	}
	if c.DBPassword == "" {
		return c, fmt.Errorf("SOLAR_DB_PASSWORD non défini")
	}
	loc, err := time.LoadLocation(getenv("SOLAR_TZ", "Europe/Paris"))
	if err != nil {
		return c, fmt.Errorf("SOLAR_TZ invalide: %w", err)
	}
	c.TZ = loc

	if c.InverterID, err = strconv.Atoi(getenv("SOLAR_INVERTER_ID", "0")); err != nil {
		return c, fmt.Errorf("SOLAR_INVERTER_ID invalide: %w", err)
	}
	if c.RateRPS, err = strconv.ParseFloat(getenv("SOLAR_RATE_RPS", "5"), 64); err != nil {
		return c, fmt.Errorf("SOLAR_RATE_RPS invalide: %w", err)
	}
	if c.RateBurst, err = strconv.Atoi(getenv("SOLAR_RATE_BURST", "20")); err != nil {
		return c, fmt.Errorf("SOLAR_RATE_BURST invalide: %w", err)
	}
	c.TrustProxy = getenv("SOLAR_TRUST_PROXY", "false") == "true"

	if s := os.Getenv("SOLAR_LAT"); s != "" {
		v, err := strconv.ParseFloat(s, 64)
		if err != nil {
			return c, fmt.Errorf("SOLAR_LAT invalide: %w", err)
		}
		c.Lat = &v
	}
	if s := os.Getenv("SOLAR_LON"); s != "" {
		v, err := strconv.ParseFloat(s, 64)
		if err != nil {
			return c, fmt.Errorf("SOLAR_LON invalide: %w", err)
		}
		c.Lon = &v
	}
	return c, nil
}
