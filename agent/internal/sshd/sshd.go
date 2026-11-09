// Package sshd evaluates an OpenSSH server configuration the way sshd itself
// resolves global (non connection-specific) settings.
//
// Semantics, following sshd_config(5) and OpenSSH's servconf.c:
//
//   - Keywords are case-insensitive. A keyword is separated from its arguments
//     by whitespace and/or a single "="; arguments may be single- or
//     double-quoted; an unquoted "#" at the start of a token begins a comment.
//   - The first value obtained for a keyword wins; later lines never override it.
//   - Include directives are expanded in place. Each argument is a glob, and a
//     relative pattern is resolved against /etc/ssh. Matched files are read in
//     lexical order and nesting is limited to 16 levels, as in OpenSSH.
//   - Match blocks are not evaluated, because their effect depends on the
//     connecting user and address. A Match block runs from its Match line to
//     the next Match line or the end of the file containing it. "Match all"
//     matches every connection, so it ends the block and the directives after
//     it are global again. Directives inside a block are recorded separately
//     (see Config.Conditional) so callers can point out that a global value may
//     be overridden for some connections.
//
// OpenSSH bounds only the nesting depth, so files that Include each other can
// expand exponentially. The evaluator also caps the number of files and bytes
// it reads, so a hostile configuration fails the scan's SSH checks instead of
// hanging the agent.
package sshd

import (
	"errors"
	"fmt"
	"math"
	"path"
	"strconv"
	"strings"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
)

const (
	// DefaultPath is where sshd reads its configuration from.
	DefaultPath = "/etc/ssh/sshd_config"

	// includeDir is what relative Include patterns are resolved against
	// (SSHDIR in OpenSSH).
	includeDir = "/etc/ssh"

	// maxIncludeDepth is OpenSSH's SERVCONF_MAX_DEPTH.
	maxIncludeDepth = 16

	// maxFiles and maxBytes bound the whole configuration, counting a file
	// again each time it is included. Real configurations are a few files and
	// kilobytes.
	maxFiles = 256
	maxBytes = 8 << 20
)

// Directive is one keyword line of the configuration.
type Directive struct {
	Keyword string // as written in the file
	Args    []string
	File    string
	Line    int
}

// Value returns the arguments joined by single spaces.
func (d Directive) Value() string {
	return strings.Join(d.Args, " ")
}

// String formats the directive as file:line: Keyword args, which is how it is
// shown as evidence.
func (d Directive) String() string {
	return fmt.Sprintf("%s:%d: %s %s", d.File, d.Line, d.Keyword, d.Value())
}

// Config is an evaluated server configuration.
type Config struct {
	global      map[string]Directive
	conditional map[string][]Directive
}

// Lookup returns the directive that decides keyword (case-insensitive) for
// all connections, if the configuration sets it outside a Match block.
func (c *Config) Lookup(keyword string) (Directive, bool) {
	d, ok := c.global[strings.ToLower(keyword)]
	return d, ok
}

// Conditional returns the directives that set keyword inside Match blocks, in
// file order. They are not part of the evaluated configuration.
func (c *Config) Conditional(keyword string) []Directive {
	return c.conditional[strings.ToLower(keyword)]
}

// Load reads and evaluates the configuration file name and everything it
// includes. If name itself cannot be read the error from fsys is returned
// unwrapped, so callers can test for fs.ErrNotExist.
func Load(fsys hostfs.FS, name string) (*Config, error) {
	p := parser{
		fsys: fsys,
		cfg: &Config{
			global:      make(map[string]Directive),
			conditional: make(map[string][]Directive),
		},
	}
	data, err := p.read(name)
	if err != nil {
		return nil, err
	}
	if err := p.parse(name, data, 0); err != nil {
		return nil, err
	}
	return p.cfg, nil
}

type parser struct {
	fsys  hostfs.FS
	cfg   *Config
	files int // files read so far, including repeats
	bytes int // bytes read so far
}

// read returns the contents of a configuration file, enforcing maxFiles and
// maxBytes across the whole configuration.
func (p *parser) read(name string) ([]byte, error) {
	p.files++
	if p.files > maxFiles {
		return nil, fmt.Errorf("configuration includes more than %d files", maxFiles)
	}
	data, err := p.fsys.ReadFile(name)
	if err != nil {
		return nil, err
	}
	p.bytes += len(data)
	if p.bytes > maxBytes {
		return nil, fmt.Errorf("configuration exceeds %d MiB", maxBytes>>20)
	}
	return data, nil
}

func (p *parser) parse(name string, data []byte, depth int) error {
	inMatch := false
	for i, line := range strings.Split(string(data), "\n") {
		keyword, args, err := splitLine(line)
		if err != nil {
			return fmt.Errorf("%s:%d: %w", name, i+1, err)
		}
		if keyword == "" {
			continue
		}
		d := Directive{Keyword: keyword, Args: args, File: name, Line: i + 1}
		key := strings.ToLower(keyword)

		switch {
		case key == "match":
			inMatch = !matchesAll(args)
		case inMatch:
			p.cfg.conditional[key] = append(p.cfg.conditional[key], d)
		case key == "include":
			if err := p.include(d, depth); err != nil {
				return err
			}
		default:
			if _, seen := p.cfg.global[key]; !seen {
				p.cfg.global[key] = d
			}
		}
	}
	return nil
}

func (p *parser) include(d Directive, depth int) error {
	if depth+1 > maxIncludeDepth {
		return fmt.Errorf("%s:%d: Include nested more than %d levels deep", d.File, d.Line, maxIncludeDepth)
	}
	for _, pattern := range d.Args {
		if !path.IsAbs(pattern) {
			pattern = path.Join(includeDir, pattern)
		}
		files, err := hostfs.Glob(p.fsys, pattern)
		if err != nil {
			return fmt.Errorf("%s:%d: Include %s: %w", d.File, d.Line, pattern, err)
		}
		for _, f := range files {
			data, err := p.read(f)
			if err != nil {
				return fmt.Errorf("%s:%d: Include %s: %w", d.File, d.Line, pattern, err)
			}
			if err := p.parse(f, data, depth+1); err != nil {
				return err
			}
		}
	}
	return nil
}

// matchesAll reports whether Match arguments are the "all" criterion, which
// sshd accepts only on its own.
func matchesAll(args []string) bool {
	return len(args) == 1 && strings.EqualFold(args[0], "all")
}

// splitLine returns the keyword and arguments of one configuration line, or
// an empty keyword for blank and comment lines.
func splitLine(line string) (keyword string, args []string, err error) {
	line = strings.TrimSpace(line)
	if line == "" || line[0] == '#' {
		return "", nil, nil
	}
	end := strings.IndexAny(line, " \t=")
	switch {
	case end < 0:
		return "", nil, fmt.Errorf("no argument after keyword %q", line)
	case end == 0:
		return "", nil, errors.New("missing keyword")
	}
	keyword = line[:end]
	rest := strings.TrimLeft(line[end:], " \t")
	rest = strings.TrimPrefix(rest, "=")
	if args, err = splitArgs(rest); err != nil {
		return "", nil, fmt.Errorf("keyword %s: %w", keyword, err)
	}
	if len(args) == 0 {
		return "", nil, fmt.Errorf("no argument after keyword %q", keyword)
	}
	return keyword, args, nil
}

// splitArgs splits s into whitespace-separated arguments, honoring single
// and double quotes and backslash escapes of quotes, backslashes and spaces.
// An unquoted token starting with "#" ends the line.
func splitArgs(s string) ([]string, error) {
	var args []string
	for {
		s = strings.TrimLeft(s, " \t")
		if s == "" || s[0] == '#' {
			return args, nil
		}
		var (
			arg   strings.Builder
			quote byte
			i     int
		)
	token:
		for ; i < len(s); i++ {
			c := s[i]
			switch {
			case quote == 0 && (c == ' ' || c == '\t'):
				break token
			case c == '\\' && i+1 < len(s) && isEscapable(s[i+1], quote):
				i++
				arg.WriteByte(s[i])
			case quote == 0 && (c == '"' || c == '\''):
				quote = c
			case quote != 0 && c == quote:
				quote = 0
			default:
				arg.WriteByte(c)
			}
		}
		if quote != 0 {
			return nil, errors.New("unterminated quoted argument")
		}
		args = append(args, arg.String())
		s = s[i:]
	}
}

func isEscapable(c, quote byte) bool {
	return c == '"' || c == '\'' || c == '\\' || (quote == 0 && c == ' ')
}

// ParseSeconds parses an sshd time value such as "90", "2m" or "1h30m" into
// seconds. Units are s, m, h, d and w (case-insensitive); a number without a
// unit is seconds.
func ParseSeconds(s string) (int, error) {
	if s == "" {
		return 0, errors.New("empty time value")
	}
	total := 0
	for rest := s; rest != ""; {
		n := 0
		for n < len(rest) && rest[n] >= '0' && rest[n] <= '9' {
			n++
		}
		if n == 0 {
			return 0, fmt.Errorf("invalid time value %q", s)
		}
		value, err := strconv.Atoi(rest[:n])
		if err != nil {
			return 0, fmt.Errorf("invalid time value %q: %w", s, err)
		}
		rest = rest[n:]

		multiplier := 1
		if rest != "" {
			switch rest[0] {
			case 's', 'S':
			case 'm', 'M':
				multiplier = 60
			case 'h', 'H':
				multiplier = 60 * 60
			case 'd', 'D':
				multiplier = 24 * 60 * 60
			case 'w', 'W':
				multiplier = 7 * 24 * 60 * 60
			default:
				return 0, fmt.Errorf("invalid time unit in %q", s)
			}
			rest = rest[1:]
		}
		if value > (math.MaxInt32-total)/multiplier {
			return 0, fmt.Errorf("time value %q out of range", s)
		}
		total += value * multiplier
	}
	return total, nil
}
