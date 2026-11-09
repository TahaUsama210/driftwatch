// Package hostfstest provides an in-memory hostfs.FS for tests.
package hostfstest

import (
	"errors"
	"io/fs"
	"path"
	"slices"
	"strings"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
)

// File is one regular file in a MapFS.
type File struct {
	Data []byte
	Mode fs.FileMode // permission bits
	UID  uint32
	GID  uint32
	// Err, when set, is returned by every operation on this file, which lets
	// tests simulate unreadable files (for example fs.ErrPermission).
	Err error
}

// MapFS is an in-memory hostfs.FS keyed by absolute host path. Directories are
// implicit: every ancestor of a file exists and is owned by root with mode 0755.
type MapFS map[string]File

var _ hostfs.FS = MapFS(nil)

// ReadFile implements hostfs.FS.
func (m MapFS) ReadFile(name string) ([]byte, error) {
	f, ok := m[name]
	switch {
	case ok && f.Err != nil:
		return nil, &fs.PathError{Op: "open", Path: name, Err: f.Err}
	case ok:
		return slices.Clone(f.Data), nil
	case m.isDir(name):
		return nil, &fs.PathError{Op: "read", Path: name, Err: errIsDir}
	default:
		return nil, &fs.PathError{Op: "open", Path: name, Err: fs.ErrNotExist}
	}
}

// Stat implements hostfs.FS.
func (m MapFS) Stat(name string) (hostfs.FileInfo, error) {
	f, ok := m[name]
	switch {
	case ok && f.Err != nil:
		return hostfs.FileInfo{}, &fs.PathError{Op: "stat", Path: name, Err: f.Err}
	case ok:
		return hostfs.FileInfo{Mode: f.Mode.Perm(), UID: f.UID, GID: f.GID}, nil
	case m.isDir(name):
		return hostfs.FileInfo{Mode: fs.ModeDir | 0o755}, nil
	default:
		return hostfs.FileInfo{}, &fs.PathError{Op: "stat", Path: name, Err: fs.ErrNotExist}
	}
}

// ReadDir implements hostfs.FS.
func (m MapFS) ReadDir(name string) ([]string, error) {
	if !m.isDir(name) {
		return nil, &fs.PathError{Op: "readdir", Path: name, Err: fs.ErrNotExist}
	}
	prefix := strings.TrimSuffix(name, "/") + "/"
	var names []string
	for p := range m {
		rest, ok := strings.CutPrefix(p, prefix)
		if !ok {
			continue
		}
		child, _, _ := strings.Cut(rest, "/")
		if !slices.Contains(names, child) {
			names = append(names, child)
		}
	}
	slices.Sort(names)
	return names, nil
}

func (m MapFS) isDir(name string) bool {
	if name == "/" {
		return true
	}
	for p := range m {
		for d := path.Dir(p); d != "/"; d = path.Dir(d) {
			if d == name {
				return true
			}
		}
	}
	return false
}

var errIsDir = errors.New("is a directory")
