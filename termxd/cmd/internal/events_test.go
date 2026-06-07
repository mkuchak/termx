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

// countValidJSONLines opens path, asserts every non-empty line is valid JSON,
// and returns the count. Used by the rotation tests.
func countValidJSONLines(t *testing.T, path string) int {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 0
		}
		t.Fatalf("open %s: %v", path, err)
	}
	defer f.Close()
	n := 0
	s := bufio.NewScanner(f)
	s.Buffer(make([]byte, 0, 1<<20), 1<<20)
	for s.Scan() {
		if strings.TrimSpace(s.Text()) == "" {
			continue
		}
		var tmp map[string]any
		if err := json.Unmarshal(s.Bytes(), &tmp); err != nil {
			t.Fatalf("invalid json line in %s: %q (%v)", path, s.Text(), err)
		}
		n++
	}
	return n
}

// TestAppendEventRotatesPastCap drives AppendEventAtCap with a cap sized to a
// couple of lines so exactly ONE rotation fires partway through the run: the
// .1 file must appear, a fresh live file must be recreated and keep taking
// appends, no line from that rotation may be lost (live + .1 together hold all
// written lines), and every line in both files must be valid JSON.
//
// (Note: we keep a SINGLE .1 slot, so a cap so tiny it rotates on every append
// would clobber older .1 copies by design — "older history discarded". This
// test sizes the cap so only one rotation happens, making the no-loss
// guarantee for that rotation observable.)
func TestAppendEventRotatesPastCap(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	fixed := time.Date(2026, 4, 23, 12, 0, 0, 0, time.UTC)

	// Measure one line's byte size (all our lines are identical length), then
	// size the cap to hold exactly N lines so exactly ONE rotation fires across
	// the run: with cap = N*lineSize+1 and N+1 writes, the file crosses the cap
	// on write N (rotating lines 0..N into .1), and write N+1 lands in a freshly
	// recreated live file — no second rotation to clobber .1.
	probe := filepath.Join(dir, "probe.ndjson")
	if err := AppendEventAtCap(probe, "session_created", "s", map[string]any{"n": 0}, fixed, 1<<40); err != nil {
		t.Fatalf("probe append: %v", err)
	}
	lineInfo, err := os.Stat(probe)
	if err != nil {
		t.Fatalf("stat probe: %v", err)
	}
	lineSize := lineInfo.Size()
	const fillLines = 4 // lines that fit under cap before the rotation
	capBytes := fillLines*lineSize + 1
	// fillLines+1 lines fit/cross the cap (rotation fires as the (fillLines+1)th
	// line pushes size to (fillLines+1)*lineSize >= cap); a further write then
	// lands in the recreated live file. So fillLines+2 writes => one rotation,
	// then a live file that is non-empty.
	const writes = fillLines + 2

	for i := 0; i < writes; i++ {
		if err := AppendEventAtCap(path, "session_created", "s", map[string]any{"n": i}, fixed, capBytes); err != nil {
			t.Fatalf("AppendEventAtCap #%d: %v", i, err)
		}
	}

	// .1 must exist (rotation fired).
	if _, err := os.Stat(path + ".1"); err != nil {
		t.Fatalf("rotated file .1 missing: %v", err)
	}
	// A fresh live file must have been recreated and kept accepting appends:
	// the final write lands here, so live holds exactly 1 line.
	live := countValidJSONLines(t, path)
	if live == 0 {
		t.Errorf("live file empty after rotation; recreate-and-continue broken")
	}
	rotated := countValidJSONLines(t, path+".1")
	// Exactly one rotation: the first fillLines+1 lines in .1, the final line in
	// the recreated live file — all `writes` lines preserved, none lost.
	if live+rotated != writes {
		t.Errorf("total lines = %d (live %d + rotated %d), want %d (no line lost in the single rotation)", live+rotated, live, rotated, writes)
	}
}

// TestAppendEventNoRotateBelowCap confirms a generous cap leaves the live file
// in place and never creates a .1.
func TestAppendEventNoRotateBelowCap(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	fixed := time.Date(2026, 4, 23, 12, 0, 0, 0, time.UTC)

	for i := 0; i < 5; i++ {
		if err := AppendEventAtCap(path, "session_created", "s", map[string]any{"n": i}, fixed, 10*1024*1024); err != nil {
			t.Fatalf("AppendEventAtCap #%d: %v", i, err)
		}
	}
	if _, err := os.Stat(path + ".1"); !os.IsNotExist(err) {
		t.Errorf("rotated file created below cap")
	}
	if got := countValidJSONLines(t, path); got != 5 {
		t.Errorf("live lines = %d, want 5", got)
	}
}

// TestAppendEventRotatePreservesTailContract verifies that after rotation the
// live file is a FRESH inode (rename + recreate), not a truncated-in-place
// file. The Android tail uses `tail -F` (reopen-by-name), which depends on
// the live path becoming a new inode so the reader re-seeks from offset 0.
// os.SameFile compares the underlying inode/device, so it distinguishes a
// rename (same inode, new name) from a truncate-in-place (same inode + name).
func TestAppendEventRotatePreservesTailContract(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.ndjson")
	fixed := time.Date(2026, 4, 23, 12, 0, 0, 0, time.UTC)

	// First append with a HUGE cap so no rotation: capture the live inode.
	if err := AppendEventAtCap(path, "session_created", "s", nil, fixed, 1<<40); err != nil {
		t.Fatalf("first append: %v", err)
	}
	beforeInfo, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat live before rotate: %v", err)
	}

	// Second append with cap=1 forces this line's file to rotate to .1.
	if err := AppendEventAtCap(path, "session_created", "s", nil, fixed, 1); err != nil {
		t.Fatalf("second append: %v", err)
	}
	// The first append's content moved to .1; that file must BE the original
	// live file (a rename preserves the inode).
	rotatedInfo, err := os.Stat(path + ".1")
	if err != nil {
		t.Fatalf("stat .1 after rotate: %v", err)
	}
	if !os.SameFile(beforeInfo, rotatedInfo) {
		t.Errorf(".1 is not the original live file; rotation must be a rename, not a copy/truncate")
	}

	// A third append recreates the live file as a NEW inode (rename left the
	// name free; O_CREATE makes a fresh one).
	if err := AppendEventAtCap(path, "session_created", "s", nil, fixed, 1<<40); err != nil {
		t.Fatalf("third append: %v", err)
	}
	afterInfo, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat live after rotate: %v", err)
	}
	if os.SameFile(beforeInfo, afterInfo) {
		t.Errorf("live file is the same inode after rotation; rotation must recreate a fresh file, not truncate in place")
	}
}
