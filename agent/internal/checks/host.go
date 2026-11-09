package checks

import (
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
	"github.com/TahaUsama210/driftwatch/agent/internal/sshd"
)

// Host is the system under scan.
//
// It memoizes parsed configuration files, so each file is read once per scan
// and every check sees the same snapshot even if the file changes mid-scan.
// Create a new Host for each scan. A Host is not safe for concurrent use.
type Host struct {
	root hostfs.FS // the host's filesystem (--root)
	proc hostfs.FS // the host's procfs (--proc)

	sshd      memo[*sshd.Config]
	passwd    memo[[]record]
	shadow    memo[[]record]
	loginDefs memo[loginDefs]
}

// NewHost returns a Host whose files are read from root and whose kernel
// parameters are read from proc.
func NewHost(root, proc hostfs.FS) *Host {
	return &Host{root: root, proc: proc}
}

func (h *Host) sshdConfig() (*sshd.Config, error) {
	return h.sshd.get(func() (*sshd.Config, error) {
		return sshd.Load(h.root, sshd.DefaultPath)
	})
}

type memo[T any] struct {
	done bool
	val  T
	err  error
}

func (m *memo[T]) get(load func() (T, error)) (T, error) {
	if !m.done {
		m.val, m.err = load()
		m.done = true
	}
	return m.val, m.err
}
