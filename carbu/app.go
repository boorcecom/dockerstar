package main

import (
	"context"
	"log/slog"
	"net/http"
	"time"
)

// App : orchestre les sources et l'état.
type App struct {
	cfg    Config
	sf     StationsFile
	store  *Store
	keys   *KeyStore
	client *http.Client
	now    func() time.Time
}

func newApp(cfg Config, sf StationsFile, store *Store, keys *KeyStore) *App {
	return &App{cfg: cfg, sf: sf, store: store, keys: keys, client: &http.Client{Timeout: 25 * time.Second}, now: time.Now}
}

// merge fusionne les stations rafraîchies ; celles absentes du résultat gardent leurs valeurs.
func (a *App) merge(data map[string]*StationData) {
	_ = a.store.Update(func(st *State) {
		for id, d := range data {
			st.Stations[id] = d
			// Une donnée de source plus récente qu'une correction manuelle la remplace : on purge.
			for fuel, m := range st.Manual[id] {
				if sp, ok := d.Prices[fuel]; ok && sp.Updated.After(m.Updated) {
					delete(st.Manual[id], fuel)
				}
			}
		}
	})
}

func (a *App) refreshFR(ctx context.Context) error {
	data, err := fetchFR(ctx, a.client, a.cfg, a.sf.Stations)
	if err != nil {
		return err
	}
	a.merge(data)
	slog.Info("prix France rafraîchis", "stations", len(data))
	return nil
}

func (a *App) refreshBE(ctx context.Context) error {
	data, failed, err := fetchCarbu(ctx, a.client, a.cfg, a.sf.Stations)
	a.merge(data)
	if err != nil {
		return err
	}
	slog.Info("prix Belgique rafraîchis", "stations", len(data), "en_echec", failed)
	return nil
}

// loop exécute fn à intervalle régulier. Le premier passage tient compte de l'état
// persisté : un redémarrage ne déclenche pas de nouveau passage avant l'échéance.
func (a *App) loop(ctx context.Context, name string, interval, retry time.Duration, fn func(context.Context) error) {
	src := a.store.source(name)
	next := a.now()
	if !src.LastAttempt.IsZero() {
		wait := interval
		if src.Failed {
			wait = retry
		}
		if t := src.LastAttempt.Add(wait); t.After(next) && t.Before(next.Add(wait+time.Minute)) {
			next = t
		}
	}
	for {
		a.store.setNext(name, next) // publie l'échéance prévue
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Until(next)):
		}
		start := a.now()
		err := fn(ctx)
		wait := interval
		if err != nil {
			slog.Error("rafraîchissement en échec", "source", name, "err", err)
			wait = retry
		}
		next = a.now().Add(wait)
		a.store.markAttempt(name, start, err == nil, next)
		if ctx.Err() != nil {
			return
		}
	}
}

func (a *App) start(ctx context.Context) {
	go a.loop(ctx, "fr", a.cfg.FRInterval, 10*time.Minute, a.refreshFR)
	if a.cfg.BEEnabled {
		go a.loop(ctx, "be", a.cfg.BEInterval, 6*time.Hour, a.refreshBE)
	}
}
