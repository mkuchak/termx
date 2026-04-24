package cmd

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/mkuchak/termx/termxd/cmd/internal"
	"github.com/spf13/cobra"
)

// pretoolusePollInterval is how often the hook re-stat()s the response file
// while blocking for a phone-side decision. 100ms is a sweet spot: it keeps
// the typical end-to-end approval latency under the user's reaction time
// without pegging CPU on a long-running Claude session.
const pretoolusePollInterval = 100 * time.Millisecond

// pretoolusePollTimeout is the wall-clock ceiling after which the hook
// defaults to deny — i.e. how long we'll block Claude while waiting for a
// phone that may be dead/in-pocket/offline.
const pretoolusePollTimeout = 30 * time.Second

// allowlistFileName is the on-disk filename (inside ~/.termx/) that the
// hook reads to decide whether a tool call bypasses the broker entirely.
// Format: one regex per line, matched against `<tool_name>|<cmd-or-path>`.
// Blank lines and `#` comments are ignored.
const allowlistFileName = "allowlist.txt"

// fastPathTools are auto-approved before even checking the allowlist file.
// These are read-only and sit on the hot path of essentially every Claude
// interaction — shipping them through the broker would mean a round-trip
// to the phone for literally every `Read`.
var fastPathTools = map[string]struct{}{
	"Read":  {},
	"Grep":  {},
	"Glob":  {},
	"LS":    {},
	"WebFetch": {},
	"WebSearch": {},
}

// hookPreToolUseInput mirrors the PreToolUse payload shape documented at
// https://docs.claude.com/en/docs/claude-code/hooks.
type hookPreToolUseInput struct {
	SessionID string          `json:"session_id"`
	ToolName  string          `json:"tool_name"`
	ToolInput json.RawMessage `json:"tool_input"`
	Cwd       string          `json:"cwd"`
}

// approvalRequest is the on-disk shape written to
// ~/.termx/approvals/<id>.req.json. The phone's PermissionBrokerViewModel
// reads this file (when it drills into an event) for richer context than
// the NDJSON event carries by itself.
type approvalRequest struct {
	ID               string          `json:"id"`
	Ts               string          `json:"ts"`
	Session          string          `json:"session"`
	ToolName         string          `json:"tool_name"`
	ToolArgs         json.RawMessage `json:"tool_args"`
	Cwd              string          `json:"cwd"`
	MatchesAllowlist bool            `json:"matches_allowlist"`
}

// approvalResponse is what the phone (via EventStreamClient.sendCommand →
// termxd commands dir, or a direct SFTP write) drops at
// ~/.termx/approvals/<id>.res.json.
type approvalResponse struct {
	Decision string `json:"decision"` // "approve" | "deny"
	Reason   string `json:"reason,omitempty"`
}

// newHookPreToolUseCmd returns the cobra command wired as Claude's
// PreToolUse hook. Keep this a thin shell; the real logic lives in
// runHookPreToolUse so tests can call it with injected streams.
func newHookPreToolUseCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_hook-pretooluse",
		Short:  "Claude PreToolUse hook: blocks on phone approval",
		Hidden: true,
		Args:   cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			return runHookPreToolUse(cmd.InOrStdin(), cmd.OutOrStdout(), cmd.ErrOrStderr())
		},
	}
}

// runHookPreToolUse is the testable entry point for the PreToolUse hook.
// It returns nil on approve and a non-nil error (after already having
// written a stderr explainer) on deny/timeout. The calling cobra shell
// exits 2 when the error is non-nil — Claude's hook protocol interprets
// exit-2 with stderr as "block and show this to the model".
func runHookPreToolUse(stdin io.Reader, stdout, stderr io.Writer) error {
	raw, err := io.ReadAll(stdin)
	if err != nil {
		fmt.Fprintf(stderr, "termx hook: read stdin: %v\n", err)
		return errHookDeny
	}

	var input hookPreToolUseInput
	if err := json.Unmarshal(bytes.TrimSpace(raw), &input); err != nil {
		// Malformed payload — fail-open so we don't wedge Claude on a
		// schema drift. Better to allow and let the user's normal
		// Claude permission system handle it than hang forever.
		fmt.Fprintf(stderr, "termx hook: parse stdin: %v (fail-open)\n", err)
		return nil
	}

	paths, err := internal.ResolvePaths()
	if err != nil {
		fmt.Fprintf(stderr, "termx hook: resolve paths: %v\n", err)
		return nil
	}

	sessionName := detectTmuxSession()

	// Fast path — never touch the filesystem for the read-only tools that
	// dominate every Claude interaction.
	if _, fast := fastPathTools[input.ToolName]; fast {
		return nil
	}

	// Allowlist check. A match silently auto-approves *without* writing
	// approval files or emitting events — the phone doesn't want to see
	// a pile of "approved: npm test" rows scroll past every minute.
	if allowlistMatches(paths.TermxDir, input.ToolName, extractCommandFromArgs(input.ToolInput)) {
		return nil
	}

	id := uuid.NewString()
	now := time.Now().UTC().Format(time.RFC3339)

	req := approvalRequest{
		ID:               id,
		Ts:               now,
		Session:          sessionName,
		ToolName:         input.ToolName,
		ToolArgs:         input.ToolInput,
		Cwd:              input.Cwd,
		MatchesAllowlist: false,
	}
	reqPath := filepath.Join(paths.ApprovalsDir, id+".req.json")
	if err := os.MkdirAll(paths.ApprovalsDir, 0o700); err != nil {
		fmt.Fprintf(stderr, "termx hook: mkdir approvals: %v\n", err)
		return nil
	}
	if err := writeJSONAtomic(reqPath, req, 0o600); err != nil {
		fmt.Fprintf(stderr, "termx hook: write request: %v\n", err)
		return nil
	}

	_ = internal.RotateIfNeeded(internal.DefaultRotateBytes)
	_ = internal.AppendEvent("permission_requested", sessionName, map[string]any{
		"request_id": id,
		"tool_name":  input.ToolName,
		"tool_args":  json.RawMessage(input.ToolInput),
		"cwd":        input.Cwd,
	})

	resPath := filepath.Join(paths.ApprovalsDir, id+".res.json")
	resp, timedOut, err := pollApprovalResponse(resPath, pretoolusePollInterval, pretoolusePollTimeout)

	// Best-effort cleanup so ~/.termx/approvals/ doesn't grow without bound.
	_ = os.Remove(reqPath)
	if err == nil {
		_ = os.Remove(resPath)
	}

	if timedOut {
		_ = internal.AppendEvent("permission_resolved", sessionName, map[string]any{
			"request_id": id,
			"decision":   "deny",
			"reason":     "timeout",
		})
		fmt.Fprintln(stderr, "Approval timeout — default-deny")
		return errHookDeny
	}
	if err != nil {
		_ = internal.AppendEvent("permission_resolved", sessionName, map[string]any{
			"request_id": id,
			"decision":   "deny",
			"reason":     "read_error",
		})
		fmt.Fprintf(stderr, "Approval read error: %v\n", err)
		return errHookDeny
	}

	decision := strings.ToLower(strings.TrimSpace(resp.Decision))
	_ = internal.AppendEvent("permission_resolved", sessionName, map[string]any{
		"request_id": id,
		"decision":   decision,
		"reason":     resp.Reason,
	})
	if decision == "approve" || decision == "allow" {
		return nil
	}
	reason := resp.Reason
	if reason == "" {
		reason = "Denied by termx"
	}
	fmt.Fprintln(stderr, reason)
	return errHookDeny
}

// errHookDeny is returned from runHookPreToolUse to make the calling cobra
// shell exit with code 2 — Claude's hook protocol interprets exit-2 + a
// non-empty stderr as "block the tool call and show stderr to the model".
var errHookDeny = errors.New("termx denied")

// detectTmuxSession tries $TMUX_SESSION, falling back to `tmux display-
// message -p '#S'`. Returns empty string if neither source yields a name;
// the hook still records the request, but the phone won't be able to
// attribute the event to a specific tab.
func detectTmuxSession() string {
	if s := strings.TrimSpace(os.Getenv("TMUX_SESSION")); s != "" {
		return s
	}
	out, err := exec.Command("tmux", "display-message", "-p", "#S").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// extractCommandFromArgs returns the best candidate "thing being matched"
// for the allowlist regex. For Bash it's the command string; for Edit /
// Write / MultiEdit it's the file_path. For anything else we just return
// the tool name again, which is enough for `^Foo$` style rules.
func extractCommandFromArgs(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		return ""
	}
	for _, key := range []string{"command", "cmd", "file_path", "path"} {
		if v, ok := m[key]; ok {
			if s, ok := v.(string); ok {
				return s
			}
		}
	}
	return ""
}

// allowlistMatches tests `<tool_name>|<target>` against every regex line
// in ~/.termx/allowlist.txt. Empty / missing file → no match. A broken
// regex on one line does not disable the rest — we log-skip it and keep
// going.
func allowlistMatches(termxDir, toolName, target string) bool {
	path := filepath.Join(termxDir, allowlistFileName)
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	candidate := toolName + "|" + target
	scanner := bufio.NewScanner(f)
	// Allow long-ish rules without falling over on the scanner default.
	scanner.Buffer(make([]byte, 0, 64*1024), 1<<20)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		re, err := regexp.Compile(line)
		if err != nil {
			continue
		}
		if re.MatchString(candidate) {
			return true
		}
	}
	return false
}

// pollApprovalResponse blocks until <path> exists-and-parses, or until
// <timeout> elapses. Atomic write on the phone side means any non-zero
// file we successfully unmarshal is complete.
func pollApprovalResponse(path string, interval, timeout time.Duration) (approvalResponse, bool, error) {
	deadline := time.Now().Add(timeout)
	for {
		if _, err := os.Stat(path); err == nil {
			b, rerr := os.ReadFile(path)
			if rerr == nil && len(bytes.TrimSpace(b)) > 0 {
				var resp approvalResponse
				if jerr := json.Unmarshal(b, &resp); jerr == nil {
					return resp, false, nil
				}
			}
			// File exists but not yet fully written; fall through and retry.
		}
		if time.Now().After(deadline) {
			return approvalResponse{}, true, nil
		}
		time.Sleep(interval)
	}
}

// writeJSONAtomic marshals v and atomically publishes it at path using
// the rename-from-tmp pattern. Permissions are 0600 on the canonical
// path so approval payloads can't be read by other users on a shared box.
func writeJSONAtomic(path string, v any, mode os.FileMode) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, mode); err != nil {
		return err
	}
	if err := os.Chmod(tmp, mode); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}
