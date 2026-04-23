package internal

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// Session describes a tmux session (or plain-shell pseudo-session) as
// persisted to ~/.termx/sessions/<name>.json. The phone reads this tree
// to render tabs.
type Session struct {
	Name      string `json:"name"`
	CreatedAt string `json:"created_at"`
	Windows   int    `json:"windows"`
	// Status transitions: idle (default) → working (P5 Claude hooks) →
	// awaiting_permission (P5). Kept as a string so schema can evolve
	// without a Go enum change breaking older phone readers.
	Status string `json:"status"`
	// Claude flags whether a claude-code REPL is the tmux session's
	// foreground process. Populated in P5; false in P4.3.
	Claude bool `json:"claude"`
}

// SessionPath returns the absolute path for a session registry entry.
func SessionPath(name string) (string, error) {
	p, err := ResolvePaths()
	if err != nil {
		return "", err
	}
	return filepath.Join(p.SessionsDir, name+".json"), nil
}

// WriteSession persists a session atomically via write-then-rename.
// Creates the parent sessions dir if missing so tmux hooks fired before
// `termx install` ran won't error out silently.
func WriteSession(s Session) error {
	if s.Name == "" {
		return errors.New("WriteSession: empty name")
	}
	path, err := SessionPath(s.Name)
	if err != nil {
		return err
	}
	return WriteSessionAt(path, s)
}

// WriteSessionAt is the path-injected variant used by tests.
func WriteSessionAt(path string, s Session) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// ReadSession loads a session registry entry. Returns (nil, nil) when
// the file is absent so callers can distinguish "never existed" from a
// real error.
func ReadSession(name string) (*Session, error) {
	path, err := SessionPath(name)
	if err != nil {
		return nil, err
	}
	return ReadSessionAt(path)
}

// ReadSessionAt is the path-injected variant used by tests.
func ReadSessionAt(path string) (*Session, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var s Session
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, fmt.Errorf("decode %s: %w", path, err)
	}
	return &s, nil
}

// DeleteSession removes a session's registry file. Idempotent — a
// missing file is not an error (tmux may fire session-closed after the
// user already ran `termx uninstall`).
func DeleteSession(name string) error {
	path, err := SessionPath(name)
	if err != nil {
		return err
	}
	return DeleteSessionAt(path)
}

// DeleteSessionAt is the path-injected variant used by tests.
func DeleteSessionAt(path string) error {
	err := os.Remove(path)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}
