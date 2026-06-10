package cmd

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

// kotlinGoldenDir points at the Android companion's byte-exact approval
// response fixtures. This is a deliberate cross-language schema lock: the
// Kotlin side's tests are pinned to these exact bytes (what the phone writes
// over SFTP to ~/.termx/approvals/<id>.res.json), and the tests below prove
// the Go hook accepts those same bytes verbatim. If either side drifts,
// exactly one of the two suites breaks and points at the contract.
//
// The path is relative to this test file (Go runs package tests with the
// package directory as cwd). When the Go module is checked out standalone —
// without the Android tree — the tests skip instead of failing.
const kotlinGoldenDir = "../../android/libs/companion/src/test/resources/approvals-golden"

func skipWithoutKotlinGoldens(t *testing.T) {
	t.Helper()
	if _, err := os.Stat(kotlinGoldenDir); err != nil {
		t.Skipf("Kotlin golden fixtures not present (%v) — standalone Go checkout, skipping cross-language contract test", err)
	}
}

func readKotlinGolden(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(kotlinGoldenDir, name))
	if err != nil {
		t.Fatalf("read golden fixture %s: %v", name, err)
	}
	return b
}

// TestKotlinGoldenFixturesParse locks the wire schema: every fixture the
// phone is allowed to write must unmarshal into approvalResponse with the
// exact decision/reason the Kotlin tests promise.
func TestKotlinGoldenFixturesParse(t *testing.T) {
	skipWithoutKotlinGoldens(t)
	cases := []struct {
		fixture      string
		wantDecision string
		wantReason   string
	}{
		{"allow.res.json", "allow", ""},
		// "Always approve" semantics live entirely on the phone (it appends
		// the allowlist rule itself); the wire decision is plain "allow",
		// byte-identical to allow.res.json. Writing a literal "always"
		// decision would be a DENY on this side — never let that regress.
		{"always.res.json", "allow", ""},
		{"deny.res.json", "deny", ""},
		{"deny-with-reason.res.json", "deny", "Denied from notification"},
	}
	for _, c := range cases {
		t.Run(c.fixture, func(t *testing.T) {
			var resp approvalResponse
			if err := json.Unmarshal(readKotlinGolden(t, c.fixture), &resp); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			if resp.Decision != c.wantDecision {
				t.Errorf("decision = %q, want %q", resp.Decision, c.wantDecision)
			}
			if resp.Reason != c.wantReason {
				t.Errorf("reason = %q, want %q", resp.Reason, c.wantReason)
			}
		})
	}
}

// TestKotlinGoldenFixturesRoundTrip drives the full hook entry point with
// each golden fixture published into the approvals dir exactly the way the
// phone does it (write-tmp-then-rename), and asserts the hook's verdict:
// nil error = approve, non-nil = block (the cobra shell turns that into
// exit-2 + stderr, which Claude shows to the model).
func TestKotlinGoldenFixturesRoundTrip(t *testing.T) {
	skipWithoutKotlinGoldens(t)
	cases := []struct {
		fixture    string
		wantAllow  bool
		wantStderr string // substring required on deny
	}{
		{"allow.res.json", true, ""},
		{"always.res.json", true, ""},
		{"deny.res.json", false, "Denied by termx"}, // hook's default reason
		{"deny-with-reason.res.json", false, "Denied from notification"},
	}
	for _, c := range cases {
		t.Run(c.fixture, func(t *testing.T) {
			dir := isolateHome(t)
			payload := readKotlinGolden(t, c.fixture)

			// Mimic the phone: wait for the .req.json to appear, then
			// atomically publish the fixture bytes as the .res.json.
			go func() {
				deadline := time.Now().Add(2 * time.Second)
				for time.Now().Before(deadline) {
					entries, _ := os.ReadDir(filepath.Join(dir, "approvals"))
					for _, e := range entries {
						if !strings.HasSuffix(e.Name(), ".req.json") {
							continue
						}
						id := strings.TrimSuffix(e.Name(), ".req.json")
						resPath := filepath.Join(dir, "approvals", id+".res.json")
						tmp := resPath + ".tmp"
						_ = os.WriteFile(tmp, payload, 0o600)
						_ = os.Rename(tmp, resPath)
						return
					}
					time.Sleep(5 * time.Millisecond)
				}
			}()

			in := bytes.NewBufferString(`{"tool_name":"Bash","tool_input":{"command":"touch /tmp/contract"},"cwd":"/tmp"}`)
			var out, errb bytes.Buffer
			err := runHookPreToolUse(in, &out, &errb)

			if c.wantAllow {
				if err != nil {
					t.Fatalf("expected approve, got %v (stderr: %q)", err, errb.String())
				}
				return
			}
			if err == nil {
				t.Fatalf("expected deny error, got nil")
			}
			if !strings.Contains(errb.String(), c.wantStderr) {
				t.Errorf("stderr = %q, want substring %q", errb.String(), c.wantStderr)
			}
		})
	}
}

// TestAllowlistAcceptsKotlinEscapeDialect locks the regex dialect contract:
// the phone's "Always approve" appends rules built with Kotlin's
// Regex.escape(), which emits \Q...\E quoting. Go's RE2 must keep accepting
// that dialect, both at compile time and through the real allowlist path.
func TestAllowlistAcceptsKotlinEscapeDialect(t *testing.T) {
	re, err := regexp.Compile(`^\QBash\E\|.*$`)
	if err != nil {
		t.Fatalf("RE2 rejected Kotlin Regex.escape dialect: %v", err)
	}
	if !re.MatchString("Bash|anything") {
		t.Errorf(`pattern ^\QBash\E\|.*$ should match "Bash|anything"`)
	}
	dir := isolateHome(t)
	writeAllowlist(t, dir, `^\QBash\E\|.*$`+"\n")
	if !allowlistMatches(dir, "Bash", "anything") {
		t.Errorf("allowlistMatches should accept the Kotlin-escaped rule")
	}
}
