package hostfs

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strings"
	"syscall"
)

const (
	// maxSymlinks bounds symlink expansion during resolution, like Linux's
	// MAXSYMLINKS, so a link cycle fails instead of looping forever.
	maxSymlinks = 40

	// MaxFileSize caps how much of a single file ReadFile will load. Every file
	// the agent reads is a small text file; the cap keeps a corrupted or
	// hostile one from exhausting memory.
	MaxFileSize = 8 << 20
)

var (
	errTooManyLinks = errors.New("too many levels of symbolic links")
	errTooLarge     = fmt.Errorf("file exceeds the %d MiB read limit", MaxFileSize>>20)
	errNotRegular   = errors.New("not a regular file")
)

// Dir returns an FS that serves host paths from the local directory root, as
// if root were "/" on the scanned host.
//
// Symbolic links are resolved the way the scanned host would resolve them:
// absolute link targets are interpreted relative to root, and ".." never climbs
// above it. Scanning a mounted image therefore reads the image's files, never
// the scanner's own.
func Dir(root string) FS {
	return dirFS{root: root}
}

type dirFS struct {
	root string
}

func (d dirFS) ReadFile(name string) ([]byte, error) {
	local, err := d.resolve("open", name)
	if err != nil {
		return nil, err
	}
	// O_NONBLOCK keeps the open from waiting for a writer if the file is a
	// FIFO; it does not change how regular files are read.
	f, err := os.OpenFile(local, os.O_RDONLY|syscall.O_NONBLOCK, 0) //nolint:gosec // reading host files chosen by the operator is the agent's purpose
	if err != nil {
		return nil, hostPathError("open", name, err)
	}
	defer f.Close() //nolint:errcheck // read-only file; Close cannot lose data

	// Only regular files are read. A FIFO or device in place of a
	// configuration file could block the scan forever or never end.
	fi, err := f.Stat()
	if err != nil {
		return nil, hostPathError("stat", name, err)
	}
	if !fi.Mode().IsRegular() {
		return nil, &fs.PathError{Op: "open", Path: name, Err: errNotRegular}
	}

	// Stat sizes are unreliable for procfs, so read up to the cap and check.
	data, err := io.ReadAll(io.LimitReader(f, MaxFileSize+1))
	if err != nil {
		return nil, hostPathError("read", name, err)
	}
	if len(data) > MaxFileSize {
		return nil, &fs.PathError{Op: "read", Path: name, Err: errTooLarge}
	}
	return data, nil
}

func (d dirFS) Stat(name string) (FileInfo, error) {
	local, err := d.resolve("stat", name)
	if err != nil {
		return FileInfo{}, err
	}
	fi, err := os.Stat(local)
	if err != nil {
		return FileInfo{}, hostPathError("stat", name, err)
	}
	uid, gid, err := owner(fi)
	if err != nil {
		return FileInfo{}, &fs.PathError{Op: "stat", Path: name, Err: err}
	}
	return FileInfo{Mode: fi.Mode(), UID: uid, GID: gid}, nil
}

func (d dirFS) ReadDir(name string) ([]string, error) {
	local, err := d.resolve("readdir", name)
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(local)
	if err != nil {
		return nil, hostPathError("readdir", name, err)
	}
	names := make([]string, len(entries))
	for i, e := range entries {
		names[i] = e.Name()
	}
	return names, nil
}

// resolve maps a host path to a local path with every symlink expanded
// relative to the root.
func (d dirFS) resolve(op, name string) (string, error) {
	if !path.IsAbs(name) {
		return "", &fs.PathError{Op: op, Path: name, Err: fs.ErrInvalid}
	}

	resolved := "/"
	pending := strings.Split(name, "/")
	for links := 0; len(pending) > 0; {
		part := pending[0]
		pending = pending[1:]
		switch part {
		case "", ".":
			continue
		case "..":
			resolved = path.Dir(resolved)
			continue
		}

		next := path.Join(resolved, part)
		fi, err := os.Lstat(d.local(next))
		if err != nil {
			return "", hostPathError(op, name, err)
		}
		if fi.Mode()&fs.ModeSymlink == 0 {
			resolved = next
			continue
		}

		links++
		if links > maxSymlinks {
			return "", &fs.PathError{Op: op, Path: name, Err: errTooManyLinks}
		}
		target, err := os.Readlink(d.local(next))
		if err != nil {
			return "", hostPathError(op, name, err)
		}
		target = filepath.ToSlash(target)
		if path.IsAbs(target) {
			resolved = "/"
		}
		pending = append(strings.Split(target, "/"), pending...)
	}
	return d.local(resolved), nil
}

func (d dirFS) local(hostPath string) string {
	return filepath.Join(d.root, filepath.FromSlash(hostPath))
}

// hostPathError rewrites an os error so that it names the host path rather
// than the local mount path, keeping the underlying cause for errors.Is.
func hostPathError(op, name string, err error) error {
	if pe, ok := errors.AsType[*fs.PathError](err); ok {
		err = pe.Err
	}
	return &fs.PathError{Op: op, Path: name, Err: err}
}
