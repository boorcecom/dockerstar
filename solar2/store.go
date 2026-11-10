package main

import (
	"context"
	"database/sql"
	"fmt"
	"math"
	"net"
	"sync"
	"time"

	"github.com/go-sql-driver/mysql"
)

// Store lit la base solar (en lecture seule).
type Store struct {
	db  *sql.DB
	cfg Config

	mu     sync.Mutex
	invID  int
	coords *[2]float64 // lat, lon
}

func openStore(cfg Config) (*Store, error) {
	my := mysql.Config{
		User:                 cfg.DBUser,
		Passwd:               cfg.DBPassword,
		Net:                  "tcp",
		Addr:                 net.JoinHostPort(cfg.DBHost, cfg.DBPort),
		DBName:               cfg.DBName,
		ParseTime:            true,
		Loc:                  cfg.TZ, // les datetime sont stockés en heure locale
		Timeout:              5 * time.Second,
		ReadTimeout:          10 * time.Second,
		AllowNativePasswords: true,
	}
	db, err := sql.Open("mysql", my.FormatDSN())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(5 * time.Minute)
	return &Store{db: db, cfg: cfg}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) Ping(ctx context.Context) error { return s.db.PingContext(ctx) }

// resolve détermine l'onduleur et ses coordonnées (une fois, puis mis en cache).
func (s *Store) resolve(ctx context.Context) (int, *[2]float64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.invID == 0 {
		id := s.cfg.InverterID
		if id == 0 {
			err := s.db.QueryRowContext(ctx, "SELECT inverter_id FROM mesure ORDER BY mesure_id DESC LIMIT 1").Scan(&id)
			if err == sql.ErrNoRows {
				return 0, nil, errNoData
			} else if err != nil {
				return 0, nil, err
			}
		}
		s.invID = id
	}
	if s.coords == nil {
		if s.cfg.Lat != nil && s.cfg.Lon != nil {
			s.coords = &[2]float64{*s.cfg.Lat, *s.cfg.Lon}
		} else {
			var lat, lon float64
			err := s.db.QueryRowContext(ctx, "SELECT latitude, longitude FROM coordonee WHERE inverter_id = ?", s.invID).Scan(&lat, &lon)
			if err == nil {
				s.coords = &[2]float64{lat, lon}
			} else if err != sql.ErrNoRows {
				return 0, nil, err
			}
		}
	}
	return s.invID, s.coords, nil
}

func round(v float64, decimals int) float64 {
	p := math.Pow(10, float64(decimals))
	return math.Round(v*p) / p
}

func (s *Store) Current(ctx context.Context, now time.Time) (*Current, error) {
	inv, coords, err := s.resolve(ctx)
	if err != nil {
		return nil, err
	}
	var (
		at                        time.Time
		watts                     int
		today, total, temperature float64
	)
	err = s.db.QueryRowContext(ctx, `
		SELECT m.datetime, a.watts, m.earned_today, m.earned_total, m.temperature
		FROM mesure m JOIN ac a ON a.mesure_id = m.mesure_id AND a.acnum = 1
		WHERE m.inverter_id = ?
		ORDER BY m.mesure_id DESC LIMIT 1`, inv).Scan(&at, &watts, &today, &total, &temperature)
	if err == sql.ErrNoRows {
		return nil, errNoData
	} else if err != nil {
		return nil, err
	}
	c := &Current{
		At:          at.Unix(),
		AgeSeconds:  max(0, now.Unix()-at.Unix()),
		Watts:       watts,
		EnergyToday: round(today, 2),
		EnergyTotal: round(total, 1),
		Temperature: round(temperature, 1),
	}
	if coords != nil {
		v := round(sunElevation(now.Unix(), coords[0], coords[1]), 1)
		c.SunElevation = &v
	}
	return c, nil
}

func dayBounds(day time.Time, loc *time.Location) (time.Time, time.Time) {
	y, m, d := day.Date()
	return time.Date(y, m, d, 0, 0, 0, 0, loc), time.Date(y, m, d+1, 0, 0, 0, 0, loc)
}

func (s *Store) Day(ctx context.Context, day time.Time) (*DayResponse, error) {
	inv, coords, err := s.resolve(ctx)
	if err != nil && err != errNoData {
		return nil, err
	}
	start, end := dayBounds(day, s.cfg.TZ)
	resp := &DayResponse{
		Date:     start.Format("2006-01-02"),
		TZ:       s.cfg.TZ.String(),
		DayStart: start.Unix(),
		DayEnd:   end.Unix(),
		Points:   [][2]int64{},
	}
	if err == errNoData {
		return resp, nil
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT m.datetime, a.watts, m.earned_today
		FROM mesure m JOIN ac a ON a.mesure_id = m.mesure_id AND a.acnum = 1
		WHERE m.inverter_id = ? AND m.datetime >= ? AND m.datetime < ?
		ORDER BY m.datetime`, inv, start, end)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var energy float64
	for rows.Next() {
		var (
			at    time.Time
			watts int
			e     float64
		)
		if err := rows.Scan(&at, &watts, &e); err != nil {
			return nil, err
		}
		t := at.Unix()
		resp.Points = append(resp.Points, [2]int64{t, int64(watts)})
		if e > energy {
			energy = e
		}
		sum := &resp.Summary
		if sum.PeakAt == nil || watts > sum.PeakW {
			sum.PeakW = watts
			sum.PeakAt = &t
		}
		if sum.FirstAt == nil {
			sum.FirstAt = &t
		}
		sum.LastAt = &t
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	resp.Summary.EnergyKWh = round(energy, 2)
	if coords != nil {
		v := round(sunMaxElevation(resp.DayStart, resp.DayEnd, coords[0], coords[1]), 1)
		resp.Summary.SunMaxElevation = &v
	}
	return resp, nil
}

func (s *Store) Days(ctx context.Context, from, to time.Time) (*DaysResponse, error) {
	inv, _, err := s.resolve(ctx)
	fromStart, _ := dayBounds(from, s.cfg.TZ)
	_, toEnd := dayBounds(to, s.cfg.TZ)
	resp := &DaysResponse{
		TZ:   s.cfg.TZ.String(),
		From: fromStart.Format("2006-01-02"),
		To:   to.In(s.cfg.TZ).Format("2006-01-02"),
		Days: []DayTotal{},
	}
	if err == errNoData {
		return resp, nil
	} else if err != nil {
		return nil, err
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT DATE_FORMAT(m.datetime, '%Y-%m-%d') AS d, MAX(m.earned_today), MAX(a.watts)
		FROM mesure m JOIN ac a ON a.mesure_id = m.mesure_id AND a.acnum = 1
		WHERE m.inverter_id = ? AND m.datetime >= ? AND m.datetime < ?
		GROUP BY d ORDER BY d`, inv, fromStart, toEnd)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var (
			d string
			e float64
			w int
		)
		if err := rows.Scan(&d, &e, &w); err != nil {
			return nil, err
		}
		resp.Days = append(resp.Days, DayTotal{Date: d, EnergyKWh: round(e, 2), PeakW: w})
	}
	return resp, rows.Err()
}

func (s *Store) Summary(ctx context.Context, now time.Time) (*SummaryResponse, error) {
	inv, _, err := s.resolve(ctx)
	if err != nil {
		return nil, err
	}
	loc := s.cfg.TZ
	today, _ := dayBounds(now.In(loc), loc)
	_, end := dayBounds(today, loc)
	from := today.AddDate(0, 0, -364)

	rows, err := s.db.QueryContext(ctx, `
		SELECT DATE_FORMAT(datetime, '%Y-%m-%d') AS d, MAX(earned_today)
		FROM mesure
		WHERE inverter_id = ? AND datetime >= ? AND datetime < ?
		GROUP BY d`, inv, from, end)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	resp := &SummaryResponse{TZ: loc.String()}
	d7 := today.AddDate(0, 0, -6).Format("2006-01-02")
	d30 := today.AddDate(0, 0, -29).Format("2006-01-02")
	todayStr := today.Format("2006-01-02")
	for rows.Next() {
		var (
			d string
			e float64
		)
		if err := rows.Scan(&d, &e); err != nil {
			return nil, err
		}
		resp.Last365KWh += e
		if d >= d30 {
			resp.Last30KWh += e
		}
		if d >= d7 {
			resp.Last7KWh += e
		}
		if d == todayStr {
			resp.TodayKWh = e
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var total float64
	err = s.db.QueryRowContext(ctx, "SELECT earned_total FROM mesure WHERE inverter_id = ? ORDER BY mesure_id DESC LIMIT 1", inv).Scan(&total)
	if err != nil && err != sql.ErrNoRows {
		return nil, fmt.Errorf("total: %w", err)
	}
	resp.TodayKWh = round(resp.TodayKWh, 2)
	resp.Last7KWh = round(resp.Last7KWh, 1)
	resp.Last30KWh = round(resp.Last30KWh, 1)
	resp.Last365KWh = round(resp.Last365KWh, 0)
	resp.TotalKWh = round(total, 0)
	return resp, nil
}
