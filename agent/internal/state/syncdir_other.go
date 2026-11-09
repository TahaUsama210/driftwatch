//go:build !unix

package state

// syncDir is a no-op where directories cannot be opened for fsync (Windows).
// The agent runs on Linux; this keeps the package usable in development.
func syncDir(string) error { return nil }
