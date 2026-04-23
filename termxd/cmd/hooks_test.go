package cmd

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// isolateHome points HOME at a tmp dir and pre-creates ~/.termx so the
// hooks can append to events.ndjson without first running `termx
// install`. Returns the termx dir path.
func isolateHome(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	termxDir := filepath.Join(home, ".termx")
	if err := os.MkdirAll(filepath.Join(termxDir, "sessions"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(termxDir, "active"), 0o700); err != nil {
		t.Fatal(err)
	}
	return termxDir
}

func readEvents(t *testing.T, termxDir string) []map[string]any {
	t.Helper()
	path := filepath.Join(termxDir, "events.ndjson")
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		t.Fatalf("open events: %v", err)
	}
	defer f.Close()
	var out []map[string]any
	s := bufio.NewScanner(f)
	s.Buffer(make([]byte, 0, 1<<20), 1<<20)
	for s.Scan() {
		var m map[string]any
		if err := json.Unmarshal(s.Bytes(), &m); err != nil {
			t.Fatalf("bad ndjson line: %q (%v)", s.Text(), err)
		}
		out = append(out, m)
	}
	return out
}

func TestOnSessionCreatedWritesRegistryAndEvent(t *testing.T) {
	dir := isolateHome(t)

	if err := runOnSessionCreated("main"); err != nil {
		t.Fatalf("runOnSessionCreated: %v", err)
	}

	// Session file.
	path := filepath.Join(dir, "sessions", "main.json")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read session: %v", err)
	}
	var sess map[string]any
	if err := json.Unmarshal(b, &sess); err != nil {
		t.Fatalf("decode session: %v", err)
	}
	if sess["name"] != "main" {
		t.Errorf("session name = %v", sess["name"])
	}
	if sess["status"] != "idle" {
		t.Errorf("session status = %v", sess["status"])
	}

	// Event.
	events := readEvents(t, dir)
	if len(events) != 1 {
		t.Fatalf("events = %d, want 1", len(events))
	}
	if events[0]["type"] != "session_created" {
		t.Errorf("event type = %v", events[0]["type"])
	}
	if events[0]["session"] != "main" {
		t.Errorf("event session = %v", events[0]["session"])
	}
}

func TestOnSessionClosedRemovesRegistryAndEmitsEvent(t *testing.T) {
	dir := isolateHome(t)

	if err := runOnSessionCreated("work"); err != nil {
		t.Fatal(err)
	}
	if err := runOnSessionClosed("work"); err != nil {
		t.Fatalf("runOnSessionClosed: %v", err)
	}

	// Session file gone.
	if _, err := os.Stat(filepath.Join(dir, "sessions", "work.json")); !os.IsNotExist(err) {
		t.Errorf("session file should have been deleted")
	}
	events := readEvents(t, dir)
	if len(events) != 2 {
		t.Fatalf("events = %d, want 2 (created + closed)", len(events))
	}
	if events[1]["type"] != "session_closed" {
		t.Errorf("last event type = %v", events[1]["type"])
	}
}

func TestPreexecPrecmdFlowEmitsLongCommand(t *testing.T) {
	dir := isolateHome(t)

	start := time.Date(2026, 4, 23, 12, 0, 0, 0, time.UTC)
	end := start.Add(15 * time.Second)
	ppid := 31337
	cmd := "sleep 15"
	b64 := base64.StdEncoding.EncodeToString([]byte(cmd))

	if err := runPreexec(b64, ppid, start); err != nil {
		t.Fatalf("runPreexec: %v", err)
	}
	activePath := filepath.Join(dir, "active", "31337.json")
	if _, err := os.Stat(activePath); err != nil {
		t.Fatalf("active entry not written: %v", err)
	}

	if err := runPrecmd(0, ppid, end); err != nil {
		t.Fatalf("runPrecmd: %v", err)
	}
	if _, err := os.Stat(activePath); !os.IsNotExist(err) {
		t.Errorf("active entry should be consumed")
	}

	events := readEvents(t, dir)
	if len(events) != 1 {
		t.Fatalf("events = %d, want 1 (shell_command_long)", len(events))
	}
	ev := events[0]
	if ev["type"] != "shell_command_long" {
		t.Errorf("event type = %v, want shell_command_long", ev["type"])
	}
	if ev["cmd"] != cmd {
		t.Errorf("cmd = %v, want %q", ev["cmd"], cmd)
	}
	if ev["duration_ms"].(float64) != 15000 {
		t.Errorf("duration_ms = %v, want 15000", ev["duration_ms"])
	}
	if ev["exit_code"].(float64) != 0 {
		t.Errorf("exit_code = %v, want 0", ev["exit_code"])
	}
	if !strings.HasPrefix(ev["session"].(string), "plain-shell-") {
		// tmux may not be on PATH in CI; pseudo-session fallback is fine.
		t.Logf("session = %v (ok)", ev["session"])
	}
}

func TestPrecmdShortCommandNoEvent(t *testing.T) {
	dir := isolateHome(t)

	start := time.Now().UTC()
	end := start.Add(100 * time.Millisecond)
	ppid := 4242
	b64 := base64.StdEncoding.EncodeToString([]byte("ls"))

	if err := runPreexec(b64, ppid, start); err != nil {
		t.Fatal(err)
	}
	if err := runPrecmd(0, ppid, end); err != nil {
		t.Fatal(err)
	}

	// events.ndjson may not even exist, or may exist empty.
	events := readEvents(t, dir)
	if len(events) != 0 {
		t.Errorf("events = %d, want 0 (short command should be filtered)", len(events))
	}
}

func TestPrecmdErrorAboveThresholdEmits(t *testing.T) {
	dir := isolateHome(t)

	start := time.Now().UTC()
	end := start.Add(3 * time.Second)
	ppid := 111
	b64 := base64.StdEncoding.EncodeToString([]byte("false"))

	if err := runPreexec(b64, ppid, start); err != nil {
		t.Fatal(err)
	}
	if err := runPrecmd(1, ppid, end); err != nil {
		t.Fatal(err)
	}
	events := readEvents(t, dir)
	if len(events) != 1 {
		t.Fatalf("events = %d, want 1 (shell_command_error)", len(events))
	}
	if events[0]["type"] != "shell_command_error" {
		t.Errorf("event type = %v", events[0]["type"])
	}
	if events[0]["exit_code"].(float64) != 1 {
		t.Errorf("exit_code = %v, want 1", events[0]["exit_code"])
	}
	_ = dir
}

func TestPrecmdErrorBelowThresholdSilent(t *testing.T) {
	dir := isolateHome(t)

	start := time.Now().UTC()
	end := start.Add(500 * time.Millisecond)
	ppid := 222
	b64 := base64.StdEncoding.EncodeToString([]byte("false"))

	if err := runPreexec(b64, ppid, start); err != nil {
		t.Fatal(err)
	}
	if err := runPrecmd(1, ppid, end); err != nil {
		t.Fatal(err)
	}
	events := readEvents(t, dir)
	if len(events) != 0 {
		t.Errorf("events = %d, want 0 (error < 2s filtered)", len(events))
	}
}

func TestPrecmdWithoutPreexecIsNoop(t *testing.T) {
	dir := isolateHome(t)

	if err := runPrecmd(0, 99999, time.Now().UTC()); err != nil {
		t.Fatalf("runPrecmd without preexec: %v", err)
	}
	events := readEvents(t, dir)
	if len(events) != 0 {
		t.Errorf("events = %d, want 0", len(events))
	}
}
