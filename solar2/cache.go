package main

import (
	"sync"
	"time"
)

type cacheEntry struct {
	body    []byte
	expires time.Time
}

// ttlCache : cache mémoire borné + « single flight » par clé (une seule requête
// SQL à la fois pour une même clé, même sous charge).
type ttlCache struct {
	mu       sync.Mutex
	m        map[string]cacheEntry
	inflight map[string]*call
	max      int
}

type call struct {
	wg   sync.WaitGroup
	body []byte
	err  error
}

func newTTLCache(max int) *ttlCache {
	return &ttlCache{m: make(map[string]cacheEntry), inflight: make(map[string]*call), max: max}
}

// do renvoie le corps en cache ou appelle fn (une seule fois pour les appels concurrents).
func (c *ttlCache) do(key string, ttl time.Duration, now time.Time, fn func() ([]byte, error)) ([]byte, error) {
	c.mu.Lock()
	if e, ok := c.m[key]; ok && now.Before(e.expires) {
		c.mu.Unlock()
		return e.body, nil
	}
	if cl, ok := c.inflight[key]; ok {
		c.mu.Unlock()
		cl.wg.Wait()
		return cl.body, cl.err
	}
	cl := &call{}
	cl.wg.Add(1)
	c.inflight[key] = cl
	c.mu.Unlock()

	cl.body, cl.err = fn()

	c.mu.Lock()
	delete(c.inflight, key)
	if cl.err == nil {
		if len(c.m) >= c.max {
			for k, e := range c.m {
				if now.After(e.expires) {
					delete(c.m, k)
				}
			}
			if len(c.m) >= c.max { // toujours plein : on repart de zéro
				c.m = make(map[string]cacheEntry)
			}
		}
		c.m[key] = cacheEntry{body: cl.body, expires: now.Add(ttl)}
	}
	c.mu.Unlock()
	cl.wg.Done()
	return cl.body, cl.err
}
