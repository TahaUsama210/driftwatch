package hostfstest_test

import (
	"errors"
	"io/fs"
	"slices"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

func TestMapFS(t *testing.T) {
	fsys := hostfstest.MapFS{
		"/etc/shadow":       {Data: []byte("root:*:1::::::\n"), Mode: 0o640, GID: 42},
		"/etc/ssh/a.conf":   {},
		"/etc/unreadable":   {Err: fs.ErrPermission},
		"/etc/ssh/d/x.conf": {},
	}

	t.Run("reads files", func(t *testing.T) {
		got, err := fsys.ReadFile("/etc/shadow")
		if err != nil || string(got) != "root:*:1::::::\n" {
			t.Errorf("ReadFile = %q, %v", got, err)
		}
	})
	t.Run("stats files", func(t *testing.T) {
		fi, err := fsys.Stat("/etc/shadow")
		if err != nil || fi.Mode != 0o640 || fi.UID != 0 || fi.GID != 42 {
			t.Errorf("Stat = %+v, %v", fi, err)
		}
	})
	t.Run("directories are implicit", func(t *testing.T) {
		fi, err := fsys.Stat("/etc/ssh")
		if err != nil || !fi.Mode.IsDir() {
			t.Errorf("Stat(/etc/ssh) = %+v, %v", fi, err)
		}
		names, err := fsys.ReadDir("/etc/ssh")
		if err != nil || !slices.Equal(names, []string{"a.conf", "d"}) {
			t.Errorf("ReadDir(/etc/ssh) = %q, %v", names, err)
		}
		if _, err := fsys.ReadFile("/etc/ssh"); err == nil {
			t.Error("ReadFile on a directory succeeded")
		}
	})
	t.Run("injected errors", func(t *testing.T) {
		if _, err := fsys.ReadFile("/etc/unreadable"); !errors.Is(err, fs.ErrPermission) {
			t.Errorf("ReadFile error = %v", err)
		}
		if _, err := fsys.Stat("/etc/unreadable"); !errors.Is(err, fs.ErrPermission) {
			t.Errorf("Stat error = %v", err)
		}
	})
	t.Run("missing paths", func(t *testing.T) {
		if _, err := fsys.ReadFile("/nope"); !errors.Is(err, fs.ErrNotExist) {
			t.Errorf("ReadFile error = %v", err)
		}
		if _, err := fsys.Stat("/nope"); !errors.Is(err, fs.ErrNotExist) {
			t.Errorf("Stat error = %v", err)
		}
		if _, err := fsys.ReadDir("/nope"); !errors.Is(err, fs.ErrNotExist) {
			t.Errorf("ReadDir error = %v", err)
		}
	})
}
