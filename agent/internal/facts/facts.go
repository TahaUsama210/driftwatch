// Package facts collects the host facts reported with every enrollment and
// scan: hostname, OS release, kernel release, architecture and machine ID.
package facts

import (
	"errors"
	"fmt"
	"io/fs"
	"runtime"
	"strings"
	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
)

// OS identifies the distribution, from os-release(5).
type OS struct {
	ID         string `json:"id"`
	Version    string `json:"version,omitempty"`
	PrettyName string `json:"prettyName"`
}

// Host is the HostFacts schema of the API contract.
type Host struct {
	Hostname string `json:"hostname"`
	OS       OS     `json:"os"`
	Kernel   string `json:"kernel"`
	Arch     string `json:"arch"`
}

// Collector gathers facts from a host filesystem and procfs.
type Collector struct {
	Root hostfs.FS
	Proc hostfs.FS
	// Hostname, when set, provides the hostname instead of /etc/hostname. The
	// agent uses the kernel's hostname when it scans the system it runs on and
	// /etc/hostname when it scans a mounted root.
	Hostname func() (string, error)
}

// Collect returns the host's current facts.
func (c Collector) Collect() (Host, error) {
	hostname, err := c.hostname()
	if err != nil {
		return Host{}, fmt.Errorf("hostname: %w", err)
	}
	release, err := c.osRelease()
	if err != nil {
		return Host{}, fmt.Errorf("os-release: %w", err)
	}
	kernel, err := c.Proc.ReadFile("/sys/kernel/osrelease")
	if err != nil {
		return Host{}, fmt.Errorf("kernel release: %w", err)
	}
	return Host{
		Hostname: hostname,
		OS:       release,
		Kernel:   strings.TrimSpace(string(kernel)),
		Arch:     runtime.GOARCH,
	}, nil
}

func (c Collector) hostname() (string, error) {
	if c.Hostname != nil {
		name, err := c.Hostname()
		if err != nil {
			return "", err
		}
		if name == "" {
			return "", errors.New("kernel hostname is empty")
		}
		return name, nil
	}

	data, err := c.Root.ReadFile("/etc/hostname")
	if err != nil {
		return "", err
	}
	for line := range strings.Lines(string(data)) {
		if name := strings.TrimSpace(line); name != "" && !strings.HasPrefix(name, "#") {
			return name, nil
		}
	}
	return "", errors.New("/etc/hostname contains no hostname")
}

// osRelease reads /etc/os-release, falling back to /usr/lib/os-release, and
// applies the defaults os-release(5) specifies for a missing file or field.
func (c Collector) osRelease() (OS, error) {
	data, err := c.Root.ReadFile("/etc/os-release")
	if errors.Is(err, fs.ErrNotExist) {
		data, err = c.Root.ReadFile("/usr/lib/os-release")
	}
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return OS{}, err
	}

	fields := parseOSRelease(data)
	release := OS{ID: fields["ID"], Version: fields["VERSION_ID"], PrettyName: fields["PRETTY_NAME"]}
	if release.ID == "" {
		release.ID = "linux"
	}
	if release.PrettyName == "" {
		release.PrettyName = "Linux"
	}
	return release, nil
}

// parseOSRelease parses the shell-compatible KEY=value assignments of
// os-release(5), unquoting single- and double-quoted values.
func parseOSRelease(data []byte) map[string]string {
	fields := make(map[string]string)
	for line := range strings.Lines(string(data)) {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		fields[key] = unquote(value)
	}
	return fields
}

func unquote(v string) string {
	if len(v) < 2 {
		return v
	}
	switch q := v[0]; {
	case q == '\'' && v[len(v)-1] == '\'':
		return v[1 : len(v)-1]
	case q == '"' && v[len(v)-1] == '"':
		var b strings.Builder
		inner := v[1 : len(v)-1]
		for i := 0; i < len(inner); i++ {
			if inner[i] == '\\' && i+1 < len(inner) && strings.IndexByte("\"\\$`", inner[i+1]) >= 0 {
				i++
			}
			b.WriteByte(inner[i])
		}
		return b.String()
	default:
		return v
	}
}

// MachineID returns the identity the agent enrolls with: the host's
// /etc/machine-id when it holds a valid ID, otherwise fallback. When fallback
// is empty too, a new random UUID is returned with fresh set; the caller must
// persist it so the identity survives restarts.
//
// A missing, empty or malformed machine-id (images prepared for first boot
// contain "uninitialized") selects the fallback. Any other read error is
// returned, because silently switching identity would enroll a duplicate host.
func MachineID(root hostfs.FS, fallback string) (id string, fresh bool, err error) {
	data, err := root.ReadFile("/etc/machine-id")
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return "", false, err
	}
	if id := strings.TrimSpace(string(data)); isMachineID(id) {
		return id, false, nil
	}
	if fallback != "" {
		return fallback, false, nil
	}
	return uuid.NewV4().String(), true, nil
}

// isMachineID reports whether s has the machine-id(5) format: 32 lowercase
// hexadecimal characters.
func isMachineID(s string) bool {
	if len(s) != 32 {
		return false
	}
	for _, c := range []byte(s) {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}
