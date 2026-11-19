package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Source France : open data « Prix des carburants en France – flux instantané »
// (data.economie.gouv.fr, Licence Ouverte). Une seule requête pour toutes les stations.

var frFuelFields = map[string]string{ // clé interne -> préfixe des champs de l'API
	"gazole": "gazole",
	"sp95":   "sp95",
	"e10":    "e10",
	"e85":    "e85",
}

func fetchFR(ctx context.Context, client *http.Client, cfg Config, stations []Station) (map[string]*StationData, error) {
	byRef := map[string]Station{}
	var refs []string
	for _, s := range stations {
		if s.Source == "fr" {
			byRef[s.Ref] = s
			refs = append(refs, s.Ref)
		}
	}
	if len(refs) == 0 {
		return nil, nil
	}
	sel := []string{"id", "geom"}
	for _, p := range frFuelFields {
		sel = append(sel, p+"_prix", p+"_maj")
	}
	q := url.Values{}
	q.Set("where", "id in ("+strings.Join(refs, ",")+")")
	q.Set("select", strings.Join(sel, ","))
	q.Set("limit", "100")

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, cfg.FRURL+"?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", cfg.UserAgent)
	req.Header.Set("Accept", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("open data France: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	return parseFR(body, byRef, time.Now())
}

func parseFR(body []byte, byRef map[string]Station, now time.Time) (map[string]*StationData, error) {
	var doc struct {
		Results []map[string]any `json:"results"`
	}
	if err := json.Unmarshal(body, &doc); err != nil {
		return nil, fmt.Errorf("open data France: JSON invalide: %w", err)
	}
	out := map[string]*StationData{}
	for _, rec := range doc.Results {
		ref := fmt.Sprintf("%.0f", num(rec["id"]))
		st, ok := byRef[ref]
		if !ok {
			continue
		}
		d := &StationData{Prices: map[string]PricePoint{}, Checked: now}
		if g, ok := rec["geom"].(map[string]any); ok {
			d.Lat, d.Lon = num(g["lat"]), num(g["lon"])
		}
		for key, p := range frFuelFields {
			price := num(rec[p+"_prix"])
			maj, _ := rec[p+"_maj"].(string)
			if price <= 0 || maj == "" {
				continue
			}
			t, err := time.Parse(time.RFC3339, maj)
			if err != nil || price > 10 {
				continue // donnée aberrante : ignorée
			}
			d.Prices[key] = PricePoint{Value: price, Updated: t.UTC()}
		}
		out[st.ID] = d
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("open data France: aucune station reconnue")
	}
	return out, nil
}

func num(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case json.Number:
		f, _ := x.Float64()
		return f
	}
	return 0
}
