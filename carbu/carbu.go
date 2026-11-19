package main

import (
	"context"
	"errors"
	"fmt"
	"html"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Source Belgique : fiches de stations de carbu.com.
//
// Usage strictement personnel, très faible volume : une fiche par station, une fois tous
// les 2 jours (CARBU_BE_INTERVAL, plancher 12 h), avec une pause entre les fiches et un
// User-Agent qui dit ce qu'est ce client. carbu.com réserve ses droits de fouille de
// données (tdmrep) : l'activer est un choix assumé de l'utilisateur, désactivable avec
// CARBU_BE_ENABLED=false.

var (
	// <h2 class="title">Diesel (B7)</h2> ... <h1 class="price">2,129 &euro;/L</h1> <p class="sources">19/11/26 Nick</p>
	carbuPanel = regexp.MustCompile(`(?s)<h2 class="title">\s*([^<]+?)\s*</h2>\s*</div>\s*<div class="panel-body"[^>]*>\s*<h1 class="price">\s*([^<]*?)\s*</h1>(?:\s*<p class="sources">\s*([^<]*?)\s*</p>)?`)
	carbuNum   = regexp.MustCompile(`(\d+)[,.](\d{2,4})`)
	carbuDate  = regexp.MustCompile(`(\d{2})/(\d{2})/(\d{2,4})`)
)

// carbuFuel : libellé néerlandais de carbu.com -> clé interne.
var carbuFuel = map[string]string{
	"Diesel (B7)":    "gazole",
	"Super 95 (E5)":  "sp95",
	"Super 95 (E10)": "e10",
}

var errCarbuFormat = errors.New("carbu.com: format de page inattendu")

// parseCarbu extrait les prix d'une fiche. Un carburant « Prijs niet beschikbaar » est ignoré.
// Erreur si la page ne contient aucun bloc de carburant reconnaissable (mise en page modifiée).
func parseCarbu(page []byte, loc *time.Location) (map[string]PricePoint, error) {
	panels := carbuPanel.FindAllSubmatch(page, -1)
	if len(panels) == 0 {
		return nil, errCarbuFormat
	}
	out := map[string]PricePoint{}
	for _, m := range panels {
		key, ok := carbuFuel[html.UnescapeString(string(m[1]))]
		if !ok {
			continue
		}
		n := carbuNum.FindStringSubmatch(html.UnescapeString(string(m[2])))
		if n == nil {
			continue // prix non disponible
		}
		price, err := strconv.ParseFloat(n[1]+"."+n[2], 64)
		if err != nil || price <= 0 || price > 10 {
			continue
		}
		d := carbuDate.FindStringSubmatch(string(m[3]))
		if d == nil {
			continue // pas de date : on ne peut pas juger la fiabilité, on ignore
		}
		y, _ := strconv.Atoi(d[3])
		if y < 100 {
			y += 2000
		}
		mo, _ := strconv.Atoi(d[2])
		day, _ := strconv.Atoi(d[1])
		// carbu.com ne donne que le jour : on le place à midi (âge estimé à ±12 h).
		t := time.Date(y, time.Month(mo), day, 12, 0, 0, 0, loc)
		if t.Month() != time.Month(mo) { // date impossible (31/02…)
			continue
		}
		out[key] = PricePoint{Value: price, Updated: t.UTC()}
	}
	return out, nil
}

// fetchCarbu passe sur chaque fiche, une par une. Une station en échec garde ses anciennes
// valeurs (leur fiabilité baisse d'elle-même avec l'âge).
func fetchCarbu(ctx context.Context, client *http.Client, cfg Config, stations []Station) (map[string]*StationData, int, error) {
	out := map[string]*StationData{}
	failed, total := 0, 0
	for _, s := range stations {
		if s.Source != "carbu" {
			continue
		}
		if total > 0 {
			select {
			case <-ctx.Done():
				return out, failed, ctx.Err()
			case <-time.After(cfg.BEPause):
			}
		}
		total++
		prices, err := fetchCarbuStation(ctx, client, cfg, s)
		if err != nil {
			slog.Warn("carbu.com: station en échec", "station", s.ID, "err", err)
			failed++
			continue
		}
		out[s.ID] = &StationData{Prices: prices, Lat: s.Lat, Lon: s.Lon, Checked: time.Now()}
	}
	if total > 0 && failed == total {
		return out, failed, fmt.Errorf("carbu.com: toutes les stations en échec")
	}
	return out, failed, nil
}

func fetchCarbuStation(ctx context.Context, client *http.Client, cfg Config, s Station) (map[string]PricePoint, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, cfg.CarbuBase+digitsOnly(s.Ref), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", cfg.UserAgent)
	req.Header.Set("Accept", "text/html")
	req.Header.Set("Accept-Language", "nl-BE,nl;q=0.8")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 3<<20))
	if err != nil {
		return nil, err
	}
	return parseCarbu(body, cfg.TZ)
}

// digitsOnly : la référence ne doit contenir que des chiffres (elle est collée à l'URL).
func digitsOnly(ref string) string {
	if strings.Trim(ref, "0123456789") != "" {
		return "0"
	}
	return ref
}
