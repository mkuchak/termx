package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRunHookPostToolUseIgnoresNonEditTools(t *testing.T) {
	dir := isolateHome(t)
	in := bytes.NewBufferString(`{"tool_name":"Bash","tool_input":{"command":"ls"}}`)
	var out, errb bytes.Buffer
	if err := runHookPostToolUse(in, &out, &errb); err != nil {
		t.Fatalf("unexpected err: %v", err)
	}

	// No diff written, no event emitted.
	entries, _ := os.ReadDir(filepath.Join(dir, "diffs"))
	if len(entries) != 0 {
		t.Errorf("expected no diff files for Bash, got %d", len(entries))
	}
	if _, err := os.Stat(filepath.Join(dir, "events.ndjson")); err == nil {
		t.Errorf("events.ndjson should not exist for skipped tool")
	}
}

func TestRunHookPostToolUseEditCapturesDiffEvent(t *testing.T) {
	dir := isolateHome(t)

	// Simulate the post-edit state: file contains the new content.
	tmpFile := filepath.Join(dir, "target.txt")
	finalContent := "line1\nNEW_LINE\nline3\n"
	if err := os.WriteFile(tmpFile, []byte(finalContent), 0o600); err != nil {
		t.Fatal(err)
	}

	payload := map[string]any{
		"tool_name": "Edit",
		"tool_input": map[string]any{
			"file_path":  tmpFile,
			"old_string": "OLD_LINE",
			"new_string": "NEW_LINE",
		},
		"cwd": "/tmp",
	}
	b, _ := json.Marshal(payload)
	var out, errb bytes.Buffer
	if err := runHookPostToolUse(bytes.NewReader(b), &out, &errb); err != nil {
		t.Fatalf("unexpected err: %v", err)
	}

	entries, err := os.ReadDir(filepath.Join(dir, "diffs"))
	if err != nil {
		t.Fatalf("read diffs dir: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected 1 diff file, got %d", len(entries))
	}

	diffBytes, _ := os.ReadFile(filepath.Join(dir, "diffs", entries[0].Name()))
	var rec diffRecord
	if err := json.Unmarshal(diffBytes, &rec); err != nil {
		t.Fatalf("decode diff record: %v", err)
	}
	if rec.Tool != "Edit" {
		t.Errorf("tool = %q", rec.Tool)
	}
	if rec.FilePath != tmpFile {
		t.Errorf("file_path = %q", rec.FilePath)
	}
	if !strings.Contains(rec.Before, "OLD_LINE") {
		t.Errorf("before missing old content: %q", rec.Before)
	}
	if !strings.Contains(rec.After, "NEW_LINE") {
		t.Errorf("after missing new content: %q", rec.After)
	}
	if !strings.Contains(rec.UnifiedDiff, "+NEW_LINE") || !strings.Contains(rec.UnifiedDiff, "-OLD_LINE") {
		t.Errorf("unified diff missing +/- markers:\n%s", rec.UnifiedDiff)
	}

	// Event emitted?
	events := readEvents(t, dir)
	if len(events) != 1 {
		t.Fatalf("events = %d, want 1", len(events))
	}
	if events[0]["type"] != "diff_created" {
		t.Errorf("event type = %v", events[0]["type"])
	}
	if events[0]["tool"] != "Edit" {
		t.Errorf("event tool = %v", events[0]["tool"])
	}
}

func TestUnifiedDiffEmitsPlusMinus(t *testing.T) {
	out := unifiedDiff("a.txt", "one\ntwo\nthree\n", "one\nTWO\nthree\n")
	if !strings.Contains(out, "-two") {
		t.Errorf("missing - line: %q", out)
	}
	if !strings.Contains(out, "+TWO") {
		t.Errorf("missing + line: %q", out)
	}
}

func TestReconstructDiffPayloadWrite(t *testing.T) {
	payload := json.RawMessage(`{"file_path":"/tmp/x","content":"hello\n"}`)
	path, _, after := reconstructDiffPayload("Write", payload, nil)
	if path != "/tmp/x" {
		t.Errorf("path = %q", path)
	}
	if after != "hello\n" {
		t.Errorf("after = %q", after)
	}
}

// Utility to ensure fmt is linked — keeps the import honest when tests
// evolve.
var _ = fmt.Sprintf
