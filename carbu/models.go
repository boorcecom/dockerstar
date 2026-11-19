package main

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"time"
)

// Carburants suivis (clés stables de l'API).
type FuelInfo struct {
	Key   string `json:"key"`
	Label string `json:"label"`
}

var fuels = []FuelInfo{
	{"gazole", "Gazole / Diesel"},
	{"sp95", "SP95 (E5)"},
	{"e10", "SP95-E10"},
	{"e85", "E85"},
}

func validFuel(k string) bool {
	for _, f := range fuels {
		if f.Key == k {
			return true
		}
	}
	return false
}

// Origin : point de référence pour les distances.
type Origin struct {
	Name string  `json:"name"`
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
}

// Station : une station suivie (configuration).
type Station struct {
	ID      string  `json:"id"`
	Country string  `json:"country"` // FR | BE
	Source  string  `json:"source"`  // fr (open data) | carbu (carbu.com) | manual (saisie seule)
	Ref     string  `json:"ref"`     // identifiant chez la source
	Name    string  `json:"name"`
	Town    string  `json:"town"`
	Lat     float64 `json:"lat,omitempty"`
	Lon     float64 `json:"lon,omitempty"`
}

type StationsFile struct {
	Origin   Origin    `json:"origin"`
	Stations []Station `json:"stations"`
}

func parseStations(b []byte) (StationsFile, error) {
	var sf StationsFile
	if err := json.Unmarshal(b, &sf); err != nil {
		return sf, fmt.Errorf("stations: %w", err)
	}
	seen := map[string]bool{}
	for _, s := range sf.Stations {
		if s.ID == "" || s.Ref == "" || s.Name == "" {
			return sf, fmt.Errorf("stations: id, ref et name sont obligatoires (%+v)", s)
		}
		if s.Source != "fr" && s.Source != "carbu" && s.Source != "manual" {
			return sf, fmt.Errorf("stations: source inconnue %q pour %s", s.Source, s.ID)
		}
		if seen[s.ID] {
			return sf, fmt.Errorf("stations: id en double %q", s.ID)
		}
		seen[s.ID] = true
	}
	return sf, nil
}

func loadStations(cfg Config, embedded []byte) (StationsFile, error) {
	if cfg.StationsFile == "" {
		return parseStations(embedded)
	}
	b, err := os.ReadFile(cfg.StationsFile)
	if err != nil {
		return StationsFile{}, err
	}
	return parseStations(b)
}

// PricePoint : un prix et la date à laquelle la SOURCE l'a relevé/déclaré.
type PricePoint struct {
	Value   float64   `json:"value"`
	Updated time.Time `json:"updated"`
}

// ManualPoint : correction saisie à la main (un seul point par station et carburant).
type ManualPoint struct {
	Value   float64   `json:"value"`
	Updated time.Time `json:"updated"` // date d'observation du prix (par défaut : la saisie)
	By      string    `json:"by"`      // nom de la clé (appareil) qui l'a poussée
}

// StationData : dernier état connu d'une station (aucun historique).
type StationData struct {
	Prices  map[string]PricePoint `json:"prices"`
	Lat     float64               `json:"lat,omitempty"`
	Lon     float64               `json:"lon,omitempty"`
	Checked time.Time             `json:"checked"` // dernier passage réussi sur cette station
}

// SourceStatus : état d'une source (fr, carbu).
type SourceStatus struct {
	LastAttempt time.Time `json:"last_attempt"`
	LastSuccess time.Time `json:"last_success"`
	NextRun     time.Time `json:"next_run"`
	Failed      bool      `json:"failed"`
}

func haversineKm(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 6371.0
	rad := math.Pi / 180
	dLat, dLon := (lat2-lat1)*rad, (lon2-lon1)*rad
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Sqrt(a))
}

// Fiabilité d'un prix d'après son âge (date déclarée par la source).
//
//	≤ 24 h  : 100        puis décroissance linéaire jusqu'à 0 à 10 jours.
//	niveau  : good ≥ 75 (≈ ≤ 3,2 j) · medium ≥ 40 (≈ ≤ 6,6 j) · low en dessous.
const (
	fullTrustAge = 24 * time.Hour
	zeroTrustAge = 10 * 24 * time.Hour
)

func reliability(age time.Duration) (int, string) {
	var score float64
	switch {
	case age <= fullTrustAge:
		score = 100
	case age >= zeroTrustAge:
		score = 0
	default:
		score = 100 * (1 - float64(age-fullTrustAge)/float64(zeroTrustAge-fullTrustAge))
	}
	s := int(math.Round(score))
	switch {
	case s >= 75:
		return s, "good"
	case s >= 40:
		return s, "medium"
	default:
		return s, "low"
	}
}
