package cmd

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/mkuchak/termx/termxd/cmd/internal"
	"github.com/spf13/cobra"
)

// activeDirName holds per-shell-pid preexec state under ~/.termx/active/.
// _preexec writes <pid>.json; _precmd reads it back by ppid so the pair
// correlates without IPC — the shell process is always the common parent.
const activeDirName = "active"

// activeEntry is the transient record a preexec invocation drops for the
// matching precmd to pick up.
type activeEntry struct {
	Session string `json:"session"`
	Cmd     string `json:"cmd"`
	StartAt string `json:"start_at"`
	Pwd     string `json:"pwd"`
}

// Phase-4.3 filter thresholds. Inline constants rather than config to
// keep the hook binary deterministic and the event stream predictable.
const (
	longCommandMs     int64 = 10000
	errorMinCommandMs int64 = 2000
)

// hookCmds returns the cobra commands implementing the shell + Claude
// hooks. Registered as Hidden: true on the root cmd (they're invoked by
// the rc-file marked block / Claude settings, not by users directly).
func hookCmds() []*cobra.Command {
	return []*cobra.Command{
		newPreexecCmd(),
		newPrecmdCmd(),
		newHookPreToolUseCmd(),
		newHookPostToolUseCmd(),
	}
}

// ---- shell: preexec ----

func newPreexecCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_preexec <base64-cmd>",
		Short:  "shell preexec hook",
		Hidden: true,
		Args:   cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runPreexec(args[0], os.Getppid(), time.Now().UTC())
		},
	}
}

// runPreexec is split so tests can inject ppid + clock.
func runPreexec(b64 string, ppid int, now time.Time) error {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return fmt.Errorf("decode cmd: %w", err)
	}
	cmdStr := string(raw)

	paths, err := internal.ResolvePaths()
	if err != nil {
		return err
	}

	session := resolveSessionName(ppid)

	entry := activeEntry{
		Session: session,
		Cmd:     cmdStr,
		StartAt: now.Format(time.RFC3339Nano),
		Pwd:     processCwd(ppid),
	}
	activeDir := filepath.Join(paths.TermxDir, activeDirName)
	if err := os.MkdirAll(activeDir, 0o700); err != nil {
		return err
	}
	path := filepath.Join(activeDir, fmt.Sprintf("%d.json", ppid))
	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// resolveSessionName returns a stable per-shell session name. It prefers a
// multiplexer-supplied name (via the tmux display-message probe, harmless
// and a no-op when tmux isn't present) and otherwise falls back to a
// pseudo-session keyed on the shell PID. The pseudo-session lets the phone
// attribute plain-shell events to a stable "tab" regardless of multiplexer.
func resolveSessionName(ppid int) string {
	if out, err := exec.Command("tmux", "display-message", "-p", "#S").Output(); err == nil {
		s := strings.TrimSpace(string(out))
		if s != "" {
			return s
		}
	}
	return fmt.Sprintf("plain-shell-%d", ppid)
}

// processCwd reads /proc/<pid>/cwd on Linux. Empty string on any error
// or on non-Linux — callers treat pwd as best-effort metadata.
func processCwd(pid int) string {
	link := fmt.Sprintf("/proc/%d/cwd", pid)
	target, err := os.Readlink(link)
	if err != nil {
		return ""
	}
	return target
}

// ---- shell: precmd ----

func newPrecmdCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_precmd <exit-code>",
		Short:  "shell precmd hook",
		Hidden: true,
		Args:   cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			code, err := strconv.Atoi(args[0])
			if err != nil {
				return fmt.Errorf("exit-code not an int: %w", err)
			}
			return runPrecmd(code, os.Getppid(), time.Now().UTC())
		},
	}
}

// runPrecmd is split so tests can inject ppid + clock.
func runPrecmd(exitCode, ppid int, now time.Time) error {
	paths, err := internal.ResolvePaths()
	if err != nil {
		return err
	}
	activeDir := filepath.Join(paths.TermxDir, activeDirName)
	path := filepath.Join(activeDir, fmt.Sprintf("%d.json", ppid))

	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			// No preexec fired — first prompt after shell start, or a
			// Ctrl-C on an empty prompt. Nothing to emit.
			return nil
		}
		return err
	}
	var entry activeEntry
	if err := json.Unmarshal(b, &entry); err != nil {
		// Corrupt record — remove and move on.
		_ = os.Remove(path)
		return nil
	}
	_ = os.Remove(path)

	start, err := time.Parse(time.RFC3339Nano, entry.StartAt)
	if err != nil {
		return nil
	}
	durationMs := now.Sub(start).Milliseconds()

	eventType := ""
	switch {
	case durationMs > longCommandMs:
		eventType = "shell_command_long"
	case exitCode != 0 && durationMs > errorMinCommandMs:
		eventType = "shell_command_error"
	}
	if eventType == "" {
		return nil
	}

	// Rotation is handled inside AppendEvent (rename-rotation from the
	// just-written fd's size); no separate pre-append RotateIfNeeded needed.
	return internal.AppendEvent(eventType, entry.Session, map[string]any{
		"cmd":         entry.Cmd,
		"duration_ms": durationMs,
		"exit_code":   exitCode,
		"pwd":         entry.Pwd,
	})
}
