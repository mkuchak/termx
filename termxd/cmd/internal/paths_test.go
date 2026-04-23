package internal

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolvePaths(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("HOME", dir)

	p, err := ResolvePaths()
	if err != nil {
		t.Fatalf("ResolvePaths failed: %v", err)
	}
	if p.Home != dir {
		t.Errorf("Home = %q, want %q", p.Home, dir)
	}
	if p.TermxDir != filepath.Join(dir, ".termx") {
		t.Errorf("TermxDir = %q", p.TermxDir)
	}
	if p.EventsFile != filepath.Join(dir, ".termx", "events.ndjson") {
		t.Errorf("EventsFile = %q", p.EventsFile)
	}
	if p.LocalBinTermx != filepath.Join(dir, ".local", "bin", "termx") {
		t.Errorf("LocalBinTermx = %q", p.LocalBinTermx)
	}
	if p.ClaudeSettings != filepath.Join(dir, ".claude", "settings.json") {
		t.Errorf("ClaudeSettings = %q", p.ClaudeSettings)
	}
}

func TestExists(t *testing.T) {
	dir := t.TempDir()
	if Exists(filepath.Join(dir, "nope")) {
		t.Error("Exists returned true for missing path")
	}
	f := filepath.Join(dir, "file")
	if err := os.WriteFile(f, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if !Exists(f) {
		t.Error("Exists returned false for existing file")
	}
}
