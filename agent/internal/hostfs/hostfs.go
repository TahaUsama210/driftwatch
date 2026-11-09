// Package hostfs gives checks read-only access to the filesystem of the host
// being scanned.
//
// Names are absolute, slash-separated paths as they appear on the scanned host
// (for example "/etc/ssh/sshd_config"). An FS maps them onto wherever that
// filesystem is actually mounted, which lets the agent scan a host root that is
// bind-mounted into a container at, say, /host.
package hostfs

import (
	"errors"
	"io/fs"
	"path"
	"slices"
	"strings"
)

// FileInfo is the subset of file metadata the checks need.
type FileInfo struct {
	Mode fs.FileMode // file type and permission bits
	UID  uint32
	GID  uint32
}

// FS is read-only access to a host filesystem.
//
// Errors are *fs.PathError values that carry the host path, so they can be
// shown to users as-is, and that match fs.ErrNotExist / fs.ErrPermission with
// errors.Is.
type FS interface {
	// ReadFile returns the contents of the named file.
	ReadFile(name string) ([]byte, error)
	// Stat returns metadata for the named file, following symbolic links.
	Stat(name string) (FileInfo, error)
	// ReadDir returns the sorted names of the entries in the named directory.
	ReadDir(name string) ([]string, error)
}

// Glob returns the names of all files matching pattern, in lexical order, the
// way glob(3) expands an sshd_config Include directive. The pattern syntax is
// that of path.Match and the pattern must be absolute. Like glob(3), a pattern
// that matches nothing is not an error, and a name starting with "." matches
// only a pattern element that starts with "." too.
func Glob(fsys FS, pattern string) ([]string, error) {
	if !path.IsAbs(pattern) {
		return nil, &fs.PathError{Op: "glob", Path: pattern, Err: fs.ErrInvalid}
	}
	if _, err := path.Match(pattern, ""); err != nil {
		return nil, &fs.PathError{Op: "glob", Path: pattern, Err: err}
	}
	return glob(fsys, path.Clean(pattern))
}

func glob(fsys FS, pattern string) ([]string, error) {
	if !hasMeta(pattern) {
		if _, err := fsys.Stat(pattern); err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				return nil, nil
			}
			return nil, err
		}
		return []string{pattern}, nil
	}

	dir, file := path.Split(pattern)
	dir = path.Clean(dir)
	dirs := []string{dir}
	if hasMeta(dir) {
		var err error
		if dirs, err = glob(fsys, dir); err != nil {
			return nil, err
		}
	}

	var matches []string
	for _, d := range dirs {
		names, err := fsys.ReadDir(d)
		if err != nil {
			// glob(3) skips directories it cannot descend into only when they
			// are missing or not directories; anything else is a real failure.
			if errors.Is(err, fs.ErrNotExist) || isNotDir(fsys, d) {
				continue
			}
			return nil, err
		}
		for _, name := range names {
			if strings.HasPrefix(name, ".") && !strings.HasPrefix(file, ".") {
				continue
			}
			// The pattern was validated up front, so Match cannot fail here.
			if ok, _ := path.Match(file, name); ok {
				matches = append(matches, path.Join(d, name))
			}
		}
	}
	slices.Sort(matches)
	return matches, nil
}

func hasMeta(p string) bool {
	return strings.ContainsAny(p, `*?[\`)
}

func isNotDir(fsys FS, name string) bool {
	fi, err := fsys.Stat(name)
	return err == nil && !fi.Mode.IsDir()
}
