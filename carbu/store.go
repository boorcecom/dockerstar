package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// State : tout ce qui est conservé. Un seul instantané, réécrit à chaque mise à jour :
// pas d'historique des prix.
type State struct {
	Stations map[string]*StationData  `json:"stations"`
	Sources  map[string]*SourceStatus `json:"sources"`
	// Corrections manuelles : station -> carburant -> prix. Une donnée de source plus récente la remplace.
	Manual map[string]map[string]ManualPoint `json:"manual,omitempty"`
}

// Store : état en mémoire + persistance atomique dans un fichier unique.
type Store struct {
	mu   sync.RWMutex
	st   State
	path string // vide : pas de persistance (tests)
}

func newStore(dir string) *Store {
	s := &Store{st: State{Stations: map[string]*StationData{}, Sources: map[string]*SourceStatus{}, Manual: map[string]map[string]ManualPoint{}}}
	if dir != "" {
		s.path = filepath.Join(dir, "state.json")
	}
	return s
}

// Load lit l'instantané précédent ; un fichier absent ou illisible n'est pas bloquant.
func (s *Store) Load() error {
	if s.path == "" {
		return nil
	}
	b, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var st State
	if err := json.Unmarshal(b, &st); err != nil {
		return err
	}
	if st.Stations == nil {
		st.Stations = map[string]*StationData{}
	}
	if st.Sources == nil {
		st.Sources = map[string]*SourceStatus{}
	}
	if st.Manual == nil {
		st.Manual = map[string]map[string]ManualPoint{}
	}
	s.mu.Lock()
	s.st = st
	s.mu.Unlock()
	return nil
}

func (s *Store) saveLocked() error {
	if s.path == "" {
		return nil
	}
	b, err := json.Marshal(s.st)
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// Update applique fn sous verrou puis persiste.
func (s *Store) Update(fn func(st *State)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	fn(&s.st)
	return s.saveLocked()
}

// Snapshot renvoie une copie profonde pour la lecture (API).
func (s *Store) Snapshot() State {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := State{Stations: map[string]*StationData{}, Sources: map[string]*SourceStatus{}, Manual: map[string]map[string]ManualPoint{}}
	for id, m := range s.st.Manual {
		cp := make(map[string]ManualPoint, len(m))
		for k, v := range m {
			cp[k] = v
		}
		out.Manual[id] = cp
	}
	for k, v := range s.st.Stations {
		c := *v
		c.Prices = make(map[string]PricePoint, len(v.Prices))
		for fk, p := range v.Prices {
			c.Prices[fk] = p
		}
		out.Stations[k] = &c
	}
	for k, v := range s.st.Sources {
		c := *v
		out.Sources[k] = &c
	}
	return out
}

func (s *Store) source(name string) SourceStatus {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if v, ok := s.st.Sources[name]; ok {
		return *v
	}
	return SourceStatus{}
}

// markAttempt note le résultat d'un passage et la date du suivant.
func (s *Store) markAttempt(name string, now time.Time, ok bool, next time.Time) {
	_ = s.Update(func(st *State) {
		src := st.Sources[name]
		if src == nil {
			src = &SourceStatus{}
			st.Sources[name] = src
		}
		src.LastAttempt = now
		if ok {
			src.LastSuccess = now
		}
		src.Failed = !ok
		src.NextRun = next
	})
}

// setNext publie la prochaine échéance prévue sans toucher au résultat du dernier passage.
func (s *Store) setNext(name string, next time.Time) {
	_ = s.Update(func(st *State) {
		src := st.Sources[name]
		if src == nil {
			src = &SourceStatus{}
			st.Sources[name] = src
		}
		src.NextRun = next
	})
}
