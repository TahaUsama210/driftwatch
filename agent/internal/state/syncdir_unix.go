//go:build unix

package state

import "os"

func syncDir(dir string) error {
	d, err := os.Open(dir) //nolint:gosec // the operator-configured state directory
	if err != nil {
		return err
	}
	if err := d.Sync(); err != nil {
		_ = d.Close() // the Sync error is the one worth reporting
		return err
	}
	return d.Close()
}
