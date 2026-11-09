// Package secret provides a string type for credentials that redacts itself
// wherever it might be printed.
package secret

import (
	"encoding/json"
	"log/slog"
)

const redacted = "[REDACTED]"

// Token is a bearer token.
//
// Formatting it with fmt, logging it with slog or encoding it as JSON (which
// slog's JSON handler does for nested structs) produces a placeholder, so a
// token cannot leak into logs or error messages by accident. Code that needs
// the value, such as the Authorization header or the state file, calls Reveal
// explicitly. Decoding a Token from JSON keeps the value.
type Token string

// Reveal returns the token's value.
func (t Token) Reveal() string { return string(t) }

// String implements fmt.Stringer.
func (t Token) String() string { return redacted }

// GoString implements fmt.GoStringer, which covers the %#v verb.
func (t Token) GoString() string { return redacted }

// LogValue implements slog.LogValuer.
func (t Token) LogValue() slog.Value { return slog.StringValue(redacted) }

// MarshalJSON implements json.Marshaler.
func (t Token) MarshalJSON() ([]byte, error) { return json.Marshal(redacted) }
