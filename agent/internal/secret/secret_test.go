package secret_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/secret"
)

const value = "dwa_s3cr3t-t0ken"

func TestTokenIsRedactedWhenFormatted(t *testing.T) {
	tok := secret.Token(value)
	holder := struct{ Token secret.Token }{tok}
	for _, format := range []string{"%v", "%s", "%q", "%+v", "%#v", "%x"} {
		for _, arg := range []any{tok, holder, &holder} {
			if got := fmt.Sprintf(format, arg); strings.Contains(got, value) {
				t.Errorf("Sprintf(%q, %T) leaked the token: %s", format, arg, got)
			}
		}
	}
}

func TestTokenIsRedactedWhenLogged(t *testing.T) {
	var buf bytes.Buffer
	for _, h := range []slog.Handler{slog.NewJSONHandler(&buf, nil), slog.NewTextHandler(&buf, nil)} {
		slog.New(h).Info("enrolled", slog.Any("token", secret.Token(value)), slog.Any("state", struct{ T secret.Token }{value}))
	}
	if strings.Contains(buf.String(), value) {
		t.Errorf("log output leaked the token:\n%s", buf.String())
	}
	if !strings.Contains(buf.String(), "[REDACTED]") {
		t.Errorf("log output has no redaction placeholder:\n%s", buf.String())
	}
}

func TestTokenIsRedactedWhenEncodedAsJSON(t *testing.T) {
	data, err := json.Marshal(struct {
		T secret.Token `json:"t"`
	}{value})
	if err != nil {
		t.Fatal(err)
	}
	if got, want := string(data), `{"t":"[REDACTED]"}`; got != want {
		t.Errorf("json = %s, want %s", got, want)
	}
}

func TestTokenKeepsItsValueWhenDecodedAndRevealed(t *testing.T) {
	var got struct {
		T secret.Token `json:"t"`
	}
	if err := json.Unmarshal([]byte(`{"t":"`+value+`"}`), &got); err != nil {
		t.Fatal(err)
	}
	if got.T.Reveal() != value {
		t.Errorf("Reveal = %q, want %q", got.T.Reveal(), value)
	}
}
