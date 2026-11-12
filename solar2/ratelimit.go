package main

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

// limiter : seau à jetons par adresse IP.
type limiter struct {
	mu    sync.Mutex
	m     map[string]*bucket
	rate  float64 // jetons par seconde
	burst float64
}

func newLimiter(rps float64, burst int) *limiter {
	return &limiter{m: make(map[string]*bucket), rate: rps, burst: float64(burst)}
}

func (l *limiter) allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.m) > 10000 { // purge : évite une croissance sans fin
		for k, b := range l.m {
			if now.Sub(b.last) > 10*time.Minute {
				delete(l.m, k)
			}
		}
	}
	b, ok := l.m[key]
	if !ok {
		l.m[key] = &bucket{tokens: l.burst - 1, last: now}
		return true
	}
	b.tokens += now.Sub(b.last).Seconds() * l.rate
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// clientIP : derrière notre reverse proxy (trustProxy), on prend la dernière
// entrée de X-Forwarded-For, celle ajoutée par ce proxy ; sinon l'adresse TCP.
func clientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			parts := strings.Split(xff, ",")
			if ip := strings.TrimSpace(parts[len(parts)-1]); ip != "" {
				return ip
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
