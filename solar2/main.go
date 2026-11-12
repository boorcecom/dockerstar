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

func main() {
	healthcheck := flag.Bool("healthcheck", false, "interroge /api/v1/health et quitte (pour HEALTHCHECK Docker)")
	flag.Parse()

	if *healthcheck {
		addr := getenv("SOLAR_LISTEN", ":8080")
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
	store, err := openStore(cfg)
	if err != nil {
		fatal(err)
	}
	defer store.Close()

	web, err := fs.Sub(webEmbed, "web")
	if err != nil {
		fatal(err)
	}
	_, handler := newServer(store, cfg, web)

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
	go func() {
		slog.Info("démarrage", "listen", cfg.Listen, "tz", cfg.TZ.String())
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			fatal(err)
		}
	}()
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "erreur:", err)
	os.Exit(1)
}
