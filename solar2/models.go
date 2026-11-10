package main

import (
	"context"
	"errors"
	"time"
)

var errNoData = errors.New("aucune donnée")

// Current : dernière mesure connue.
type Current struct {
	At           int64    `json:"t"`
	AgeSeconds   int64    `json:"age_seconds"`
	Watts        int      `json:"watts"`
	EnergyToday  float64  `json:"energy_today_kwh"`
	EnergyTotal  float64  `json:"energy_total_kwh"`
	Temperature  float64  `json:"temperature_c"`
	SunElevation *float64 `json:"sun_elevation_deg,omitempty"`
}

type DaySummary struct {
	EnergyKWh       float64  `json:"energy_kwh"`
	PeakW           int      `json:"peak_w"`
	PeakAt          *int64   `json:"peak_at,omitempty"`
	FirstAt         *int64   `json:"first_at,omitempty"`
	LastAt          *int64   `json:"last_at,omitempty"`
	SunMaxElevation *float64 `json:"sun_max_elevation_deg,omitempty"`
}

// DayResponse : points [timestamp unix, watts] d'une journée locale.
type DayResponse struct {
	Date     string     `json:"date"`
	TZ       string     `json:"tz"`
	DayStart int64      `json:"day_start"`
	DayEnd   int64      `json:"day_end"`
	Points   [][2]int64 `json:"points"`
	Summary  DaySummary `json:"summary"`
}

type DayTotal struct {
	Date      string  `json:"date"`
	EnergyKWh float64 `json:"energy_kwh"`
	PeakW     int     `json:"peak_w"`
}

type DaysResponse struct {
	TZ   string     `json:"tz"`
	From string     `json:"from"`
	To   string     `json:"to"`
	Days []DayTotal `json:"days"`
}

type SummaryResponse struct {
	TZ         string  `json:"tz"`
	TodayKWh   float64 `json:"today_kwh"`
	Last7KWh   float64 `json:"last7_kwh"`
	Last30KWh  float64 `json:"last30_kwh"`
	Last365KWh float64 `json:"last365_kwh"`
	TotalKWh   float64 `json:"total_kwh"`
}

// dataStore est ce dont l'API a besoin ; Store (MariaDB) l'implémente,
// les tests utilisent un faux.
type dataStore interface {
	Ping(ctx context.Context) error
	Current(ctx context.Context, now time.Time) (*Current, error)
	Day(ctx context.Context, day time.Time) (*DayResponse, error)
	Days(ctx context.Context, from, to time.Time) (*DaysResponse, error)
	Summary(ctx context.Context, now time.Time) (*SummaryResponse, error)
}
