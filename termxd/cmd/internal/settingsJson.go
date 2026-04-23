package internal

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// ManagedKey is the JSON sentinel that tags every hook entry termx writes
// into ~/.claude/settings.json. Uninstall strips entries where this key is
// true, leaving any user-authored hooks untouched.
const ManagedKey = "_termx_managed"

// PreToolUseCommand / PostToolUseCommand are the shell commands Claude will
// invoke for the respective hook events.
const (
	PreToolUseCommand  = "$HOME/.local/bin/termx _hook-pretooluse"
	PostToolUseCommand = "$HOME/.local/bin/termx _hook-posttooluse"
)

// UpsertClaudeSettings reads ~/.claude/settings.json (creating it if
// missing), adds/replaces the termx-managed PreToolUse + PostToolUse entries,
// and writes it back atomically. Returns changed=true when the file was
// modified.
func UpsertClaudeSettings(path string) (bool, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return false, err
	}

	var existing map[string]any
	raw, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return false, err
	}
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &existing); err != nil {
			return false, fmt.Errorf("parse %s: %w", path, err)
		}
	}
	if existing == nil {
		existing = map[string]any{}
	}

	updated := upsertManagedHooks(existing)

	out, err := json.MarshalIndent(updated, "", "  ")
	if err != nil {
		return false, err
	}
	out = append(out, '\n')
	if bytes.Equal(out, raw) {
		return false, nil
	}
	if err := writeAtomic(path, out, 0o600); err != nil {
		return false, err
	}
	return true, nil
}

// upsertManagedHooks mutates settings to contain termx-managed hook entries
// under hooks.PreToolUse and hooks.PostToolUse. Non-termx entries are
// preserved.
func upsertManagedHooks(settings map[string]any) map[string]any {
	hooks, _ := settings["hooks"].(map[string]any)
	if hooks == nil {
		hooks = map[string]any{}
	}
	hooks["PreToolUse"] = upsertManagedHookList(hooks["PreToolUse"], PreToolUseCommand)
	hooks["PostToolUse"] = upsertManagedHookList(hooks["PostToolUse"], PostToolUseCommand)
	settings["hooks"] = hooks
	return settings
}

// upsertManagedHookList walks the list of hook entries for one event, drops
// any existing termx-managed entry, and appends a fresh one. Returns a []any
// that is JSON-marshalable.
func upsertManagedHookList(raw any, command string) []any {
	list, _ := raw.([]any)
	filtered := make([]any, 0, len(list)+1)
	for _, entry := range list {
		m, ok := entry.(map[string]any)
		if !ok {
			filtered = append(filtered, entry)
			continue
		}
		if managed, _ := m[ManagedKey].(bool); managed {
			// drop — we'll append a fresh entry
			continue
		}
		filtered = append(filtered, entry)
	}
	filtered = append(filtered, managedHookEntry(command))
	return filtered
}

func managedHookEntry(command string) map[string]any {
	return map[string]any{
		ManagedKey: true,
		"matcher":  ".*",
		"hooks": []any{
			map[string]any{
				"type":    "command",
				"command": command,
			},
		},
	}
}

// StripManagedFromClaudeSettings removes every termx-managed hook entry from
// the file. Returns changed=true when modifications were written.
func StripManagedFromClaudeSettings(path string) (bool, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return false, nil
		}
		return false, err
	}
	var settings map[string]any
	if err := json.Unmarshal(raw, &settings); err != nil {
		return false, fmt.Errorf("parse %s: %w", path, err)
	}
	if settings == nil {
		return false, nil
	}
	hooks, ok := settings["hooks"].(map[string]any)
	if !ok {
		return false, nil
	}
	changed := false
	for event, rawList := range hooks {
		list, ok := rawList.([]any)
		if !ok {
			continue
		}
		filtered := make([]any, 0, len(list))
		for _, entry := range list {
			m, ok := entry.(map[string]any)
			if ok {
				if managed, _ := m[ManagedKey].(bool); managed {
					changed = true
					continue
				}
			}
			filtered = append(filtered, entry)
		}
		if len(filtered) == 0 {
			delete(hooks, event)
		} else {
			hooks[event] = filtered
		}
	}
	if len(hooks) == 0 {
		delete(settings, "hooks")
	} else {
		settings["hooks"] = hooks
	}
	if !changed {
		return false, nil
	}
	out, err := json.MarshalIndent(settings, "", "  ")
	if err != nil {
		return false, err
	}
	out = append(out, '\n')
	if err := writeAtomic(path, out, 0o600); err != nil {
		return false, err
	}
	return true, nil
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".termx-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() {
		_ = os.Remove(tmpName)
	}()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tmpName, mode); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}
