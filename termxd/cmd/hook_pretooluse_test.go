package cmd

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func writeAllowlist(t *testing.T, termxDir, body string) {
	t.Helper()
	path := filepath.Join(termxDir, allowlistFileName)
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write allowlist: %v", err)
	}
}

func TestAllowlistMatchesTrivial(t *testing.T) {
	dir := isolateHome(t)
	writeAllowlist(t, dir, `# comment line
^Bash\|npm test$
^Bash\|ls( .*)?$
`)
	if !allowlistMatches(dir, "Bash", "npm test") {
		t.Errorf("expected exact-match rule to hit")
	}
	if !allowlistMatches(dir, "Bash", "ls -la") {
		t.Errorf("expected parametrized ls rule to hit")
	}
	if allowlistMatches(dir, "Bash", "rm -rf /") {
		t.Errorf("destructive cmd should not match")
	}
}

func TestAllowlistMissingFileIsFalse(t *testing.T) {
	dir := isolateHome(t)
	if allowlistMatches(dir, "Bash", "anything") {
		t.Errorf("expected false when file missing")
	}
}

func TestPollApprovalResponseTimeout(t *testing.T) {
	dir := isolateHome(t)
	path := filepath.Join(dir, "approvals", "missing.res.json")
	start := time.Now()
	_, timedOut, err := pollApprovalResponse(path, 10*time.Millisecond, 60*time.Millisecond)
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if !timedOut {
		t.Fatalf("expected timeout")
	}
	if elapsed := time.Since(start); elapsed < 50*time.Millisecond {
		t.Errorf("returned early: %v", elapsed)
	}
}

func TestPollApprovalResponseApprove(t *testing.T) {
	dir := isolateHome(t)
	approvalsDir := filepath.Join(dir, "approvals")
	if err := os.MkdirAll(approvalsDir, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(approvalsDir, "x.res.json")

	done := make(chan struct{})
	go func() {
		time.Sleep(30 * time.Millisecond)
		payload := []byte(`{"decision":"approve"}`)
		tmp := path + ".tmp"
		_ = os.WriteFile(tmp, payload, 0o600)
		_ = os.Rename(tmp, path)
		close(done)
	}()

	resp, timedOut, err := pollApprovalResponse(path, 5*time.Millisecond, 500*time.Millisecond)
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if timedOut {
		t.Fatalf("unexpected timeout")
	}
	if resp.Decision != "approve" {
		t.Errorf("decision = %v", resp.Decision)
	}
	<-done
}

func TestRunHookPreToolUseFastPath(t *testing.T) {
	_ = isolateHome(t)
	in := bytes.NewBufferString(`{"tool_name":"Read","tool_input":{"file_path":"/etc/hosts"},"cwd":"/tmp"}`)
	var out, errb bytes.Buffer
	if err := runHookPreToolUse(in, &out, &errb); err != nil {
		t.Fatalf("expected nil (approve) on fast path, got %v", err)
	}
}

func TestRunHookPreToolUseAllowlistSkip(t *testing.T) {
	dir := isolateHome(t)
	writeAllowlist(t, dir, `^Bash\|echo .*$`+"\n")
	in := bytes.NewBufferString(`{"tool_name":"Bash","tool_input":{"command":"echo hi"},"cwd":"/tmp"}`)
	var out, errb bytes.Buffer
	if err := runHookPreToolUse(in, &out, &errb); err != nil {
		t.Fatalf("expected approve, got %v", err)
	}
	// No event should have been emitted.
	if _, err := os.Stat(filepath.Join(dir, "events.ndjson")); err == nil {
		t.Errorf("expected no events on allowlist hit")
	}
}

func TestRunHookPreToolUseDeniedByPhone(t *testing.T) {
	dir := isolateHome(t)
	// Response file must appear during the poll.
	go func() {
		time.Sleep(50 * time.Millisecond)
		entries, _ := os.ReadDir(filepath.Join(dir, "approvals"))
		for _, e := range entries {
			if !strings.HasSuffix(e.Name(), ".req.json") {
				continue
			}
			id := strings.TrimSuffix(e.Name(), ".req.json")
			payload := []byte(`{"decision":"deny","reason":"Nope"}`)
			path := filepath.Join(dir, "approvals", id+".res.json")
			tmp := path + ".tmp"
			_ = os.WriteFile(tmp, payload, 0o600)
			_ = os.Rename(tmp, path)
			return
		}
	}()

	in := bytes.NewBufferString(`{"tool_name":"Bash","tool_input":{"command":"rm -rf /"},"cwd":"/tmp"}`)
	var out, errb bytes.Buffer

	err := runHookPreToolUse(in, &out, &errb)
	if err == nil {
		t.Fatalf("expected deny error")
	}
	if !strings.Contains(errb.String(), "Nope") {
		t.Errorf("stderr missing reason, got %q", errb.String())
	}

	// Event stream should have both request + resolved events.
	events := readEvents(t, dir)
	if len(events) < 2 {
		t.Fatalf("expected >= 2 events, got %d", len(events))
	}
	if events[0]["type"] != "permission_requested" {
		t.Errorf("first event type = %v", events[0]["type"])
	}
	if events[1]["type"] != "permission_resolved" {
		t.Errorf("second event type = %v", events[1]["type"])
	}
	if events[1]["decision"] != "deny" {
		t.Errorf("decision = %v", events[1]["decision"])
	}
}

func TestExtractCommandFromArgs(t *testing.T) {
	cases := []struct {
		raw  string
		want string
	}{
		{`{"command":"ls"}`, "ls"},
		{`{"file_path":"/tmp/x"}`, "/tmp/x"},
		{`{"path":"/a"}`, "/a"},
		{`{}`, ""},
		{`not json`, ""},
	}
	for _, c := range cases {
		got := extractCommandFromArgs(json.RawMessage(c.raw))
		if got != c.want {
			t.Errorf("raw %q: got %q, want %q", c.raw, got, c.want)
		}
	}
}

// keep io import live for future buffered-reader test hooks.
var _ io.Reader = (*bytes.Buffer)(nil)
