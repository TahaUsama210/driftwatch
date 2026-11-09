package checks

import (
	"errors"
	"fmt"
	"io/fs"
	"strconv"
	"strings"
	"unicode"
)

const (
	passwdPath    = "/etc/passwd"
	shadowPath    = "/etc/shadow"
	loginDefsPath = "/etc/login.defs"
)

// record is one entry of a colon-separated account database.
type record struct {
	line   int
	fields []string
}

// parseAccounts parses /etc/passwd or /etc/shadow. Blank lines, comments and
// NIS compat entries ("+..." / "-...") are skipped; any other line with fewer
// than minFields fields is malformed.
func parseAccounts(name string, data []byte, minFields int) ([]record, error) {
	var records []record
	for i, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSuffix(line, "\r")
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "+") || strings.HasPrefix(line, "-") {
			continue
		}
		fields := strings.Split(line, ":")
		if len(fields) < minFields {
			return nil, fmt.Errorf("%s:%d: malformed entry: %d fields, want at least %d", name, i+1, len(fields), minFields)
		}
		records = append(records, record{line: i + 1, fields: fields})
	}
	return records, nil
}

func (h *Host) passwdRecords() ([]record, error) {
	return h.passwd.get(func() ([]record, error) {
		data, err := h.root.ReadFile(passwdPath)
		if err != nil {
			return nil, err
		}
		// name:password:UID:GID:GECOS:home:shell
		return parseAccounts(passwdPath, data, 7)
	})
}

func (h *Host) shadowRecords() ([]record, error) {
	return h.shadow.get(func() ([]record, error) {
		data, err := h.root.ReadFile(shadowPath)
		if err != nil {
			return nil, err
		}
		// name:password:lastchange:min:max:warn:inactive:expire:reserved, but
		// only the first two fields matter here.
		return parseAccounts(shadowPath, data, 2)
	})
}

func uid0OnlyRoot(h *Host) Outcome {
	const expected = "root"
	records, err := h.passwdRecords()
	if err != nil {
		return errored(expected, err)
	}

	var names, locations []string
	for _, r := range records {
		uid, err := strconv.ParseUint(r.fields[2], 10, 32)
		if err != nil {
			return errored(expected, fmt.Errorf("%s:%d: invalid UID %q", passwdPath, r.line, r.fields[2]))
		}
		if uid == 0 {
			names = append(names, r.fields[0])
			locations = append(locations, fmt.Sprintf("%s (%s:%d)", r.fields[0], passwdPath, r.line))
		}
	}

	if len(names) == 0 {
		return judge(false, expected, "none", passwdPath+": no account has UID 0")
	}
	ok := len(names) == 1 && names[0] == "root"
	return judge(ok, expected, strings.Join(names, ", "), "UID 0 accounts: "+strings.Join(locations, ", "))
}

// noEmptyPasswords never reports password hashes, only account names.
func noEmptyPasswords(h *Host) Outcome {
	const expected = "none"
	records, err := h.shadowRecords()
	if err != nil {
		return errored(expected, err)
	}

	var names, locations []string
	for _, r := range records {
		if r.fields[1] == "" {
			names = append(names, r.fields[0])
			locations = append(locations, fmt.Sprintf("%s:%d: %s", shadowPath, r.line, r.fields[0]))
		}
	}
	if len(names) == 0 {
		return judge(true, expected, "none", fmt.Sprintf("%s: %d accounts, all with a password or locked", shadowPath, len(records)))
	}
	return judge(false, expected, strings.Join(names, ", "), "empty password field: "+strings.Join(locations, "; "))
}

// loginDefs maps each /etc/login.defs key to its effective setting. A nil map
// means the file does not exist.
type loginDefs map[string]loginDef

type loginDef struct {
	value string
	line  int
}

// parseLoginDefs follows shadow-utils' def_load: lines starting with "#" are
// comments, keys are case-sensitive, and the last occurrence of a key wins.
// The value is the rest of the line after leading blanks and double quotes, up
// to the next double quote. There are no trailing comments, so
// "PASS_MAX_DAYS 90 # policy" has the value "90 # policy", which shadow-utils
// cannot parse and ignores. A key without a value is ignored.
func parseLoginDefs(data []byte) loginDefs {
	defs := make(loginDefs)
	for i, line := range strings.Split(string(data), "\n") {
		line = strings.TrimLeft(strings.TrimRightFunc(line, unicode.IsSpace), " \t")
		if line == "" || line[0] == '#' {
			continue
		}
		end := strings.IndexAny(line, " \t")
		if end < 0 {
			continue
		}
		value := strings.TrimLeft(line[end:], " \t\"")
		value, _, _ = strings.Cut(value, `"`)
		defs[line[:end]] = loginDef{value: value, line: i + 1}
	}
	return defs
}

func (h *Host) loginDefsFile() (loginDefs, error) {
	return h.loginDefs.get(func() (loginDefs, error) {
		data, err := h.root.ReadFile(loginDefsPath)
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil
		}
		if err != nil {
			return nil, err
		}
		return parseLoginDefs(data), nil
	})
}

// passwordAging checks one integer login.defs setting. An unset value fails:
// without it shadow-utils applies no aging policy at all.
type passwordAging struct {
	key      string
	min, max int // inclusive bounds; max <= 0 means unbounded
}

func (p passwordAging) expected() string {
	if p.max > 0 {
		return fmt.Sprintf("between %d and %d", p.min, p.max)
	}
	return fmt.Sprintf("at least %d", p.min)
}

func (p passwordAging) evaluate(h *Host) Outcome {
	defs, err := h.loginDefsFile()
	if err != nil {
		return errored(p.expected(), err)
	}
	if defs == nil {
		return judge(false, p.expected(), "not set", loginDefsPath+" not found; no password aging policy is configured")
	}
	def, ok := defs[p.key]
	if !ok {
		return judge(false, p.expected(), "not set", fmt.Sprintf("%s is not set in %s", p.key, loginDefsPath))
	}

	evidence := fmt.Sprintf("%s:%d: %s %s", loginDefsPath, def.line, p.key, def.value)
	n, err := strconv.Atoi(def.value)
	if err != nil {
		return errored(p.expected(), fmt.Errorf("%s: not an integer", evidence))
	}
	ok = n >= p.min && (p.max <= 0 || n <= p.max)
	return judge(ok, p.expected(), def.value, evidence)
}
