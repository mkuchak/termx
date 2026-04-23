package internal

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestAppendEventFormatsLine(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	fixed := time.Date(2026, 4, 23, 12, 34, 56, 0, time.UTC)

	if err := AppendEventAt(path, "session_created", "main", map[string]any{
		"windows": 1,
	}, fixed); err != nil {
		t.Fatalf("AppendEventAt: %v", err)
	}

	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !strings.HasSuffix(string(b), "\n") {
		t.Fatalf("event line should end with newline, got %q", string(b))
	}

	var parsed map[string]any
	if err := json.Unmarshal(b[:len(b)-1], &parsed); err != nil {
		t.Fatalf("json parse: %v; line=%q", err, string(b))
	}
	if parsed["type"] != "session_created" {
		t.Errorf("type = %v, want session_created", parsed["type"])
	}
	if parsed["session"] != "main" {
		t.Errorf("session = %v, want main", parsed["session"])
	}
	if parsed["ts"] != "2026-04-23T12:34:56Z" {
		t.Errorf("ts = %v, want 2026-04-23T12:34:56Z", parsed["ts"])
	}
	if parsed["windows"].(float64) != 1 {
		t.Errorf("windows = %v, want 1", parsed["windows"])
	}
}

func TestAppendEventCannotClobberReservedKeys(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	fixed := time.Date(2026, 4, 23, 12, 0, 0, 0, time.UTC)

	err := AppendEventAt(path, "shell_command_long", "main", map[string]any{
		"ts":      "malicious",
		"type":    "malicious",
		"session": "malicious",
		"cmd":     "sleep 30",
	}, fixed)
	if err != nil {
		t.Fatalf("AppendEventAt: %v", err)
	}

	b, _ := os.ReadFile(path)
	var parsed map[string]any
	if err := json.Unmarshal(b[:len(b)-1], &parsed); err != nil {
		t.Fatalf("parse: %v", err)
	}
	if parsed["type"] != "shell_command_long" {
		t.Errorf("payload clobbered type: %v", parsed["type"])
	}
	if parsed["session"] != "main" {
		t.Errorf("payload clobbered session: %v", parsed["session"])
	}
	if parsed["cmd"] != "sleep 30" {
		t.Errorf("cmd not forwarded")
	}
}

func TestAppendEventAppendsMultipleLines(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	for i := 0; i < 3; i++ {
		if err := AppendEventAt(path, "session_created", "s", nil, time.Now()); err != nil {
			t.Fatal(err)
		}
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	lines := 0
	s := bufio.NewScanner(f)
	for s.Scan() {
		lines++
		var tmp map[string]any
		if err := json.Unmarshal(s.Bytes(), &tmp); err != nil {
			t.Fatalf("line %d invalid json: %v", lines, err)
		}
	}
	if lines != 3 {
		t.Errorf("lines = %d, want 3", lines)
	}
}

func TestRotateIfNeeded(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	// Write 11 MiB of junk.
	big := make([]byte, 11*1024*1024)
	if err := os.WriteFile(path, big, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := RotateIfNeededAt(path, 10*1024*1024); err != nil {
		t.Fatalf("rotate: %v", err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("primary file still exists after rotate")
	}
	if _, err := os.Stat(path + ".1"); err != nil {
		t.Errorf("rotated file missing: %v", err)
	}
}

func TestRotateIfNeededBelowThreshold(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	if err := os.WriteFile(path, []byte("tiny"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := RotateIfNeededAt(path, 10*1024*1024); err != nil {
		t.Fatalf("rotate: %v", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Errorf("primary removed when below threshold: %v", err)
	}
	if _, err := os.Stat(path + ".1"); !os.IsNotExist(err) {
		t.Errorf("rotated file created when below threshold")
	}
}

func TestRotateIfNeededMissingFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "never-written.ndjson")
	if err := RotateIfNeededAt(path, 1); err != nil {
		t.Errorf("rotate missing file should be a noop, got: %v", err)
	}
}
