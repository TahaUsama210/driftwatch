//go:build !unix

package hostfs

import (
	"errors"
	"fmt"
	"io/fs"
	"runtime"
)

// owner fails on platforms without Unix file ownership. The agent only scans
// Linux hosts; this keeps the module building and testable everywhere.
func owner(fs.FileInfo) (uid, gid uint32, err error) {
	return 0, 0, fmt.Errorf("file ownership on %s: %w", runtime.GOOS, errors.ErrUnsupported)
}
