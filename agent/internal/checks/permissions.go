package checks

import (
	"errors"
	"fmt"
	"io/fs"
	"strings"
)

// filePermissions passes when path is owned by uid 0 and none of the
// forbidden permission bits are set.
type filePermissions struct {
	path      string
	forbidden fs.FileMode
	// missing produces the outcome for a file that does not exist. When nil a
	// missing file is an ERROR, because the control cannot be verified.
	missing func() Outcome
}

func (p filePermissions) evaluate(h *Host) Outcome {
	expected := fmt.Sprintf("owner uid 0, mode %04o or stricter", fs.ModePerm&^p.forbidden)

	fi, err := h.root.Stat(p.path)
	if errors.Is(err, fs.ErrNotExist) && p.missing != nil {
		return p.missing()
	}
	if err != nil {
		return errored(expected, err)
	}

	perm := fi.Mode.Perm()
	actual := fmt.Sprintf("owner uid %d, mode %04o", fi.UID, perm)

	var problems []string
	if fi.UID != 0 {
		problems = append(problems, fmt.Sprintf("owned by uid %d instead of 0", fi.UID))
	}
	if extra := perm & p.forbidden; extra != 0 {
		problems = append(problems, fmt.Sprintf("permission bits %04o must not be set", extra))
	}

	evidence := fmt.Sprintf("%s: uid %d, gid %d, mode %04o", p.path, fi.UID, fi.GID, perm)
	if len(problems) > 0 {
		evidence += "; " + strings.Join(problems, "; ")
	}
	return judge(len(problems) == 0, expected, actual, evidence)
}

func shadowPermissions(h *Host) Outcome {
	return filePermissions{path: shadowPath, forbidden: 0o137}.evaluate(h)
}

func passwdPermissions(h *Host) Outcome {
	return filePermissions{path: passwdPath, forbidden: 0o133}.evaluate(h)
}

func crontabPermissions(h *Host) Outcome {
	const path = "/etc/crontab"
	return filePermissions{
		path:      path,
		forbidden: 0o077,
		missing:   func() Outcome { return notApplicable(path + " not found") },
	}.evaluate(h)
}
