// Package state persists the agent's identity and credentials between runs.
package state

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/secret"
)

// FileName is the name of the state file inside the state directory.
const FileName = "state.json"

// State is what the agent remembers across restarts.
type State struct {
	HostID     uuid.UUID
	AgentToken secret.Token
	// MachineID is the generated identity used when the host has no usable
	// /etc/machine-id; it is empty otherwise.
	MachineID string
}

// stateFile is the on-disk format. secret.Token redacts itself when encoded,
// so the token is written here explicitly and nowhere else.
type stateFile struct {
	HostID     uuid.UUID `json:"hostId,omitzero"`
	AgentToken string    `json:"agentToken,omitempty"`
	MachineID  string    `json:"machineId,omitempty"`
}

// Enrolled reports whether the state holds credentials for the API.
func (s State) Enrolled() bool {
	return s.HostID != uuid.Nil() && s.AgentToken != ""
}

// Store reads and writes the state file in a directory.
type Store struct {
	dir string
}

// NewStore returns a Store for the state directory dir.
func NewStore(dir string) *Store {
	return &Store{dir: dir}
}

// Path returns the location of the state file.
func (s *Store) Path() string {
	return filepath.Join(s.dir, FileName)
}

// Load returns the saved state, or the zero State if none has been saved yet.
func (s *Store) Load() (State, error) {
	data, err := os.ReadFile(s.Path())
	if errors.Is(err, fs.ErrNotExist) {
		return State{}, nil
	}
	if err != nil {
		return State{}, fmt.Errorf("read state: %w", err)
	}
	var f stateFile
	if err := json.Unmarshal(data, &f); err != nil {
		return State{}, fmt.Errorf("parse state %s: %w", s.Path(), err)
	}
	return State{HostID: f.HostID, AgentToken: secret.Token(f.AgentToken), MachineID: f.MachineID}, nil
}

// Save replaces the state file atomically: the new contents are written to a
// temporary file in the same directory, flushed to disk and renamed over the
// old file, so a crash leaves either the old or the new state, never a torn
// one. The file is created with mode 0600 because it holds the agent token.
func (s *Store) Save(st State) (err error) {
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return fmt.Errorf("create state directory: %w", err)
	}
	f := stateFile{HostID: st.HostID, AgentToken: st.AgentToken.Reveal(), MachineID: st.MachineID}
	data, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return fmt.Errorf("encode state: %w", err)
	}

	// CreateTemp creates the file with mode 0600.
	tmp, err := os.CreateTemp(s.dir, FileName+".*.tmp")
	if err != nil {
		return fmt.Errorf("create temporary state file: %w", err)
	}
	defer func() {
		if err != nil {
			err = errors.Join(err, removeIfExists(tmp.Name()))
		}
	}()

	if _, err := tmp.Write(append(data, '\n')); err != nil {
		return errors.Join(fmt.Errorf("write state: %w", err), tmp.Close())
	}
	if err := tmp.Sync(); err != nil {
		return errors.Join(fmt.Errorf("sync state: %w", err), tmp.Close())
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close state: %w", err)
	}
	if err := os.Rename(tmp.Name(), s.Path()); err != nil {
		return fmt.Errorf("replace state: %w", err)
	}
	// Persist the rename itself.
	if err := syncDir(s.dir); err != nil {
		return fmt.Errorf("sync state directory: %w", err)
	}
	return nil
}

func removeIfExists(name string) error {
	if err := os.Remove(name); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}
