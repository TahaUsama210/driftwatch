package state_test

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/secret"
	"github.com/TahaUsama210/driftwatch/agent/internal/state"
)

func TestLoadWithoutStateFileReturnsZeroState(t *testing.T) {
	st, err := state.NewStore(t.TempDir()).Load()
	if err != nil {
		t.Fatal(err)
	}
	if st != (state.State{}) || st.Enrolled() {
		t.Errorf("Load = %+v, want the zero State", st)
	}
}

func TestSaveThenLoadRoundTrips(t *testing.T) {
	store := state.NewStore(filepath.Join(t.TempDir(), "nested", "state"))
	want := state.State{HostID: uuid.NewV4(), AgentToken: "dwa_token", MachineID: "machine"}

	if err := store.Save(want); err != nil {
		t.Fatal(err)
	}
	got, err := store.Load()
	if err != nil {
		t.Fatal(err)
	}
	if got != want || !got.Enrolled() {
		t.Errorf("Load = %+v, want %+v", got, want)
	}
}

func TestSaveReplacesPreviousStateAndLeavesNoTemporaryFiles(t *testing.T) {
	dir := t.TempDir()
	store := state.NewStore(dir)
	if err := store.Save(state.State{MachineID: "first"}); err != nil {
		t.Fatal(err)
	}
	if err := store.Save(state.State{MachineID: "second"}); err != nil {
		t.Fatal(err)
	}

	got, err := store.Load()
	if err != nil {
		t.Fatal(err)
	}
	if got.MachineID != "second" {
		t.Errorf("MachineID = %q, want second", got.MachineID)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != state.FileName {
		t.Errorf("state directory contains %v, want only %s", entries, state.FileName)
	}
}

func TestSaveCreatesAPrivateFile(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permission bits are not meaningful on Windows")
	}
	store := state.NewStore(filepath.Join(t.TempDir(), "driftwatch"))
	if err := store.Save(state.State{AgentToken: secret.Token("dwa_x")}); err != nil {
		t.Fatal(err)
	}

	fi, err := os.Stat(store.Path())
	if err != nil {
		t.Fatal(err)
	}
	if perm := fi.Mode().Perm(); perm != 0o600 {
		t.Errorf("state file mode = %04o, want 0600", perm)
	}
	dir, err := os.Stat(filepath.Dir(store.Path()))
	if err != nil {
		t.Fatal(err)
	}
	if perm := dir.Mode().Perm(); perm != 0o700 {
		t.Errorf("state directory mode = %04o, want 0700", perm)
	}
}

func TestStateFileOmitsUnsetFields(t *testing.T) {
	store := state.NewStore(t.TempDir())
	if err := store.Save(state.State{MachineID: "m"}); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(store.Path())
	if err != nil {
		t.Fatal(err)
	}
	if got, want := string(data), "{\n  \"machineId\": \"m\"\n}\n"; got != want {
		t.Errorf("state file = %q, want %q", got, want)
	}
}

func TestLoadRejectsCorruptState(t *testing.T) {
	store := state.NewStore(t.TempDir())
	if err := os.WriteFile(store.Path(), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Load(); err == nil {
		t.Error("Load succeeded on a corrupt state file")
	}
}

func TestSaveFailsWhenTheDirectoryCannotBeCreated(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(blocker, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := state.NewStore(filepath.Join(blocker, "state")).Save(state.State{}); err == nil {
		t.Error("Save succeeded below a regular file")
	}
}

func TestStateFilePersistsTheTokenValue(t *testing.T) {
	store := state.NewStore(t.TempDir())
	if err := store.Save(state.State{AgentToken: "dwa_real"}); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(store.Path())
	if err != nil {
		t.Fatal(err)
	}
	if got, want := string(data), "{\n  \"agentToken\": \"dwa_real\"\n}\n"; got != want {
		t.Errorf("state file = %q, want %q", got, want)
	}
}

func TestFailedSaveKeepsTheDirectoryClean(t *testing.T) {
	dir := t.TempDir()
	store := state.NewStore(dir)
	// A directory where the state file should be makes Load and the final
	// rename fail.
	if err := os.Mkdir(store.Path(), 0o700); err != nil {
		t.Fatal(err)
	}

	if _, err := store.Load(); err == nil {
		t.Error("Load succeeded although the state file is a directory")
	}
	if err := store.Save(state.State{MachineID: "m"}); err == nil {
		t.Fatal("Save succeeded although the state file is a directory")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Errorf("temporary file left behind: %v", entries)
	}
}
