package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"
)

// Clés d'API : 32 octets aléatoires (« ck_ » + base64url). Seule l'empreinte SHA-256 est
// conservée sur disque ; la clé en clair n'est renvoyée qu'une fois, à sa création.
// Une clé de 256 bits aléatoires n'a pas besoin d'un hachage lent (pas de dictionnaire possible).

const (
	maxKeys      = 20
	keyPrefix    = "ck_"
	lastUsedSave = time.Hour // on ne réécrit le fichier pour « dernière utilisation » qu'une fois par heure
)

var (
	errTooManyKeys = errors.New("nombre maximal de clés atteint")
	errBadDevice   = errors.New("device_id invalide (8 à 64 caractères : lettres, chiffres, tiret)")
	errBadName     = errors.New("name invalide (1 à 40 caractères imprimables)")
	deviceIDRe     = regexp.MustCompile(`^[A-Za-z0-9-]{8,64}$`)
	nameRe         = regexp.MustCompile(`^[\p{L}\p{N} ._'()-]{1,40}$`)
)

type KeyRecord struct {
	ID       string    `json:"id"`
	DeviceID string    `json:"device_id"`
	Name     string    `json:"name"`
	Hash     string    `json:"hash"`
	Created  time.Time `json:"created"`
	LastUsed time.Time `json:"last_used"`
	saved    time.Time // dernière écriture disque de last_used (mémoire seulement)
}

// KeyInfo : ce que l'API de gestion montre d'une clé (jamais l'empreinte).
type KeyInfo struct {
	ID       string     `json:"id"`
	Name     string     `json:"name"`
	Created  time.Time  `json:"created"`
	LastUsed *time.Time `json:"last_used,omitempty"`
}

type KeyStore struct {
	mu   sync.Mutex
	keys []KeyRecord
	path string // vide : pas de persistance (tests)
}

func newKeyStore(dir string) *KeyStore {
	k := &KeyStore{}
	if dir != "" {
		k.path = filepath.Join(dir, "keys.json")
	}
	return k
}

func (k *KeyStore) Load() error {
	if k.path == "" {
		return nil
	}
	b, err := os.ReadFile(k.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var keys []KeyRecord
	if err := json.Unmarshal(b, &keys); err != nil {
		return err
	}
	k.mu.Lock()
	k.keys = keys
	k.mu.Unlock()
	return nil
}

func (k *KeyStore) saveLocked() error {
	if k.path == "" {
		return nil
	}
	b, err := json.Marshal(k.keys)
	if err != nil {
		return err
	}
	tmp := k.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, k.path)
}

func hashKey(key string) string {
	h := sha256.Sum256([]byte(key))
	return hex.EncodeToString(h[:])
}

func randomString(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err) // pas de source d'aléa : on ne génère surtout pas de clé faible
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// Issue génère une clé pour un appareil. Une nouvelle synchronisation du même appareil
// (même device_id) REMPLACE sa clé précédente : la rotation ne laisse pas de clé orpheline.
func (k *KeyStore) Issue(deviceID, name string, now time.Time) (KeyInfo, string, error) {
	if !deviceIDRe.MatchString(deviceID) {
		return KeyInfo{}, "", errBadDevice
	}
	if !nameRe.MatchString(name) {
		return KeyInfo{}, "", errBadName
	}
	k.mu.Lock()
	defer k.mu.Unlock()
	replaced := -1
	for i, r := range k.keys {
		if r.DeviceID == deviceID {
			replaced = i
		}
	}
	if replaced < 0 && len(k.keys) >= maxKeys {
		return KeyInfo{}, "", errTooManyKeys
	}
	key := keyPrefix + randomString(32)
	rec := KeyRecord{ID: randomString(9), DeviceID: deviceID, Name: name, Hash: hashKey(key), Created: now.UTC(), saved: now}
	if replaced >= 0 {
		k.keys[replaced] = rec
	} else {
		k.keys = append(k.keys, rec)
	}
	if err := k.saveLocked(); err != nil {
		return KeyInfo{}, "", err
	}
	return KeyInfo{ID: rec.ID, Name: rec.Name, Created: rec.Created}, key, nil
}

// Verify cherche la clé en temps constant (on parcourt toutes les empreintes).
func (k *KeyStore) Verify(key string, now time.Time) (KeyInfo, bool) {
	if len(key) < 20 || len(key) > 128 {
		return KeyInfo{}, false
	}
	h := []byte(hashKey(key))
	k.mu.Lock()
	defer k.mu.Unlock()
	found := -1
	for i, r := range k.keys {
		if subtle.ConstantTimeCompare(h, []byte(r.Hash)) == 1 {
			found = i
		}
	}
	if found < 0 {
		return KeyInfo{}, false
	}
	r := &k.keys[found]
	r.LastUsed = now.UTC()
	if now.Sub(r.saved) >= lastUsedSave {
		r.saved = now
		_ = k.saveLocked()
	}
	return KeyInfo{ID: r.ID, Name: r.Name, Created: r.Created}, true
}

func (k *KeyStore) List() []KeyInfo {
	k.mu.Lock()
	defer k.mu.Unlock()
	out := make([]KeyInfo, 0, len(k.keys))
	for _, r := range k.keys {
		ki := KeyInfo{ID: r.ID, Name: r.Name, Created: r.Created}
		if !r.LastUsed.IsZero() {
			t := r.LastUsed
			ki.LastUsed = &t
		}
		out = append(out, ki)
	}
	return out
}

func (k *KeyStore) Revoke(id string) bool {
	k.mu.Lock()
	defer k.mu.Unlock()
	for i, r := range k.keys {
		if r.ID == id {
			k.keys = append(k.keys[:i], k.keys[i+1:]...)
			_ = k.saveLocked()
			return true
		}
	}
	return false
}

// failTracker : bloque une IP après trop d'échecs d'authentification (anti force brute).
type failTracker struct {
	mu     sync.Mutex
	m      map[string]*failEntry
	max    int
	window time.Duration
}

type failEntry struct {
	n     int
	since time.Time
}

func newFailTracker(max int, window time.Duration) *failTracker {
	return &failTracker{m: map[string]*failEntry{}, max: max, window: window}
}

func (f *failTracker) blocked(ip string, now time.Time) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	e := f.m[ip]
	if e == nil {
		return false
	}
	if now.Sub(e.since) > f.window {
		delete(f.m, ip)
		return false
	}
	return e.n >= f.max
}

func (f *failTracker) fail(ip string, now time.Time) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.m) > 10000 {
		for k, e := range f.m {
			if now.Sub(e.since) > f.window {
				delete(f.m, k)
			}
		}
	}
	e := f.m[ip]
	if e == nil || now.Sub(e.since) > f.window {
		f.m[ip] = &failEntry{n: 1, since: now}
		return
	}
	e.n++
}
