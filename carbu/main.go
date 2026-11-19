package main

import (
	"context"
	"embed"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "time/tzdata" // fuseaux horaires embarqués : l'image finale n'a pas de /usr/share/zoneinfo
)

//go:embed web
var webEmbed embed.FS

//go:embed stations.json
var stationsEmbed []byte

func main() {
	healthcheck := flag.Bool("healthcheck", false, "interroge /api/v1/health et quitte (pour HEALTHCHECK Docker)")
	flag.Parse()

	if *healthcheck {
		addr := getenv("CARBU_LISTEN", ":8080")
		if addr[0] == ':' {
			addr = "127.0.0.1" + addr
		}
		client := http.Client{Timeout: 3 * time.Second}
		resp, err := client.Get("http://" + addr + "/api/v1/health")
		if err != nil || resp.StatusCode != http.StatusOK {
			os.Exit(1)
		}
		return
	}

	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, nil)))
	cfg, err := loadConfig()
	if err != nil {
		fatal(err)
	}
	sf, err := loadStations(cfg, stationsEmbed)
	if err != nil {
		fatal(err)
	}
	store := newStore(cfg.DataDir)
	if err := store.Load(); err != nil {
		slog.Warn("état précédent illisible, on repart de zéro", "err", err)
	}
	keys := newKeyStore(cfg.DataDir)
	if err := keys.Load(); err != nil {
		fatal(fmt.Errorf("clés illisibles (%w) : on refuse de démarrer plutôt que de perdre les appareils appairés", err))
	}
	app := newApp(cfg, sf, store, keys)

	web, err := fs.Sub(webEmbed, "web")
	if err != nil {
		fatal(err)
	}
	_, handler := newServer(app, web)
	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	app.start(ctx)
	go func() {
		slog.Info("démarrage", "listen", cfg.Listen, "stations", len(sf.Stations), "be", cfg.BEEnabled,
			"fr_interval", cfg.FRInterval.String(), "be_interval", cfg.BEInterval.String())
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			fatal(err)
		}
	}()
	var adminSrv *http.Server
	if cfg.AdminListen != "" && cfg.AdminListen != "off" {
		_, adminHandler := newAdminHandler(keys, cfg)
		adminSrv = &http.Server{
			Addr:              cfg.AdminListen,
			Handler:           adminHandler,
			ReadHeaderTimeout: 5 * time.Second,
			ReadTimeout:       10 * time.Second,
			WriteTimeout:      10 * time.Second,
			IdleTimeout:       30 * time.Second,
			MaxHeaderBytes:    8 << 10,
		}
		go func() {
			slog.Info("gestion des clés (LAN uniquement)", "listen", cfg.AdminListen)
			if err := adminSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				fatal(err)
			}
		}()
	}
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
	if adminSrv != nil {
		_ = adminSrv.Shutdown(shutdownCtx)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "erreur:", err)
	os.Exit(1)
}
