//go:build unix

package hostfs

import (
	"fmt"
	"io/fs"
	"syscall"
)

func owner(fi fs.FileInfo) (uid, gid uint32, err error) {
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return 0, 0, fmt.Errorf("unexpected stat type %T", fi.Sys())
	}
	return st.Uid, st.Gid, nil
}
