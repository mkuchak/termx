package internal

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestUpsertClaudeSettings_NewFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "settings.json")
	changed, err := UpsertClaudeSettings(p)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Error("expected changed=true for new file")
	}
	raw, _ := os.ReadFile(p)
	var parsed map[string]any
	if err := json.Unmarshal(raw, &parsed); err != nil {
		t.Fatalf("produced invalid JSON: %v\n%s", err, raw)
	}
	hooks := parsed["hooks"].(map[string]any)
	pre := hooks["PreToolUse"].([]any)
	if len(pre) != 1 {
		t.Fatalf("expected 1 PreToolUse entry, got %d", len(pre))
	}
	entry := pre[0].(map[string]any)
	if managed, _ := entry[ManagedKey].(bool); !managed {
		t.Error("missing _termx_managed sentinel")
	}
}

func TestUpsertClaudeSettings_Idempotent(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "settings.json")
	if _, err := UpsertClaudeSettings(p); err != nil {
		t.Fatal(err)
	}
	changed, err := UpsertClaudeSettings(p)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Error("second identical call should be no-op")
	}
}

func TestUpsertClaudeSettings_PreservesUserHooks(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "settings.json")
	seed := map[string]any{
		"theme": "dark",
		"hooks": map[string]any{
			"PreToolUse": []any{
				map[string]any{
					"matcher": "Bash",
					"hooks": []any{
						map[string]any{"type": "command", "command": "echo user-hook"},
					},
				},
			},
		},
	}
	raw, _ := json.MarshalIndent(seed, "", "  ")
	if err := os.WriteFile(p, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := UpsertClaudeSettings(p); err != nil {
		t.Fatal(err)
	}
	after, _ := os.ReadFile(p)
	var parsed map[string]any
	if err := json.Unmarshal(after, &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed["theme"] != "dark" {
		t.Error("unknown top-level key lost")
	}
	pre := parsed["hooks"].(map[string]any)["PreToolUse"].([]any)
	if len(pre) != 2 {
		t.Fatalf("expected 2 PreToolUse entries (user + termx), got %d", len(pre))
	}
	// First entry should be the user's, second should be termx-managed.
	userHook := pre[0].(map[string]any)
	if userHook["matcher"] != "Bash" {
		t.Errorf("user hook reordered/clobbered: %v", userHook)
	}
	termxHook := pre[1].(map[string]any)
	if managed, _ := termxHook[ManagedKey].(bool); !managed {
		t.Error("termx-managed entry missing sentinel")
	}
}

func TestStripManagedFromClaudeSettings(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "settings.json")
	if _, err := UpsertClaudeSettings(p); err != nil {
		t.Fatal(err)
	}
	changed, err := StripManagedFromClaudeSettings(p)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Error("expected changed=true")
	}
	raw, _ := os.ReadFile(p)
	var parsed map[string]any
	if err := json.Unmarshal(raw, &parsed); err != nil {
		t.Fatal(err)
	}
	if hooks, ok := parsed["hooks"]; ok {
		if m, ok := hooks.(map[string]any); ok && len(m) != 0 {
			t.Errorf("expected empty hooks after strip, got %v", m)
		}
	}
}

func TestStripManagedFromClaudeSettings_PreservesUser(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "settings.json")
	seed := map[string]any{
		"hooks": map[string]any{
			"PreToolUse": []any{
				map[string]any{
					"matcher": "Bash",
					"hooks": []any{
						map[string]any{"type": "command", "command": "echo user"},
					},
				},
				map[string]any{
					ManagedKey: true,
					"matcher":  ".*",
					"hooks": []any{
						map[string]any{"type": "command", "command": "termx"},
					},
				},
			},
		},
	}
	raw, _ := json.MarshalIndent(seed, "", "  ")
	if err := os.WriteFile(p, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	changed, err := StripManagedFromClaudeSettings(p)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Error("expected changed=true")
	}
	after, _ := os.ReadFile(p)
	var parsed map[string]any
	if err := json.Unmarshal(after, &parsed); err != nil {
		t.Fatal(err)
	}
	pre := parsed["hooks"].(map[string]any)["PreToolUse"].([]any)
	if len(pre) != 1 {
		t.Fatalf("expected 1 surviving hook, got %d", len(pre))
	}
	userHook := pre[0].(map[string]any)
	if userHook["matcher"] != "Bash" {
		t.Errorf("wrong entry survived: %v", userHook)
	}
}

func TestStripManagedFromClaudeSettings_Missing(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "settings.json")
	changed, err := StripManagedFromClaudeSettings(p)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Error("expected changed=false for missing file")
	}
}
