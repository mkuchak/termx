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

// hookCmds returns the cobra commands implementing tmux + shell hooks.
// Registered as Hidden: true on the root cmd (they're invoked by the
// rc-file marked block, not by users directly).
func hookCmds() []*cobra.Command {
	return []*cobra.Command{
		newOnSessionCreatedCmd(),
		newOnSessionClosedCmd(),
		newOnWindowLinkedCmd(),
		newOnWindowUnlinkedCmd(),
		newPreexecCmd(),
		newPrecmdCmd(),
		// Placeholders for P5.1 / P5.3 — kept here so the subcommand
		// names remain reserved by a single registrar.
		newStubCmd("_hook-pretooluse", "5.1"),
		newStubCmd("_hook-posttooluse", "5.3"),
	}
}

func newStubCmd(use, phase string) *cobra.Command {
	return &cobra.Command{
		Use:    use,
		Short:  "internal hook (stub)",
		Hidden: true,
		Args:   cobra.ArbitraryArgs,
		Run: func(cmd *cobra.Command, _ []string) {
			cmd.Printf("TODO: implement in Phase %s\n", phase)
		},
	}
}

// ---- tmux: session-created ----

func newOnSessionCreatedCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_on-session-created <session>",
		Short:  "tmux session-created hook",
		Hidden: true,
		Args:   cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runOnSessionCreated(args[0])
		},
	}
}

func runOnSessionCreated(name string) error {
	_ = internal.RotateIfNeeded(internal.DefaultRotateBytes)
	now := time.Now().UTC().Format(time.RFC3339Nano)
	sess := internal.Session{
		Name:      name,
		CreatedAt: now,
		Windows:   countWindows(name),
		Status:    "idle",
		Claude:    false,
	}
	if err := internal.WriteSession(sess); err != nil {
		return fmt.Errorf("write session: %w", err)
	}
	return internal.AppendEvent("session_created", name, map[string]any{
		"created_at": now,
		"windows":    sess.Windows,
	})
}

// ---- tmux: session-closed ----

func newOnSessionClosedCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_on-session-closed <session>",
		Short:  "tmux session-closed hook",
		Hidden: true,
		Args:   cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runOnSessionClosed(args[0])
		},
	}
}

func runOnSessionClosed(name string) error {
	_ = internal.RotateIfNeeded(internal.DefaultRotateBytes)
	if err := internal.DeleteSession(name); err != nil {
		return err
	}
	return internal.AppendEvent("session_closed", name, nil)
}

// ---- tmux: window-linked / window-unlinked ----

func newOnWindowLinkedCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_on-window-linked <session> <window>",
		Short:  "tmux window-linked hook",
		Hidden: true,
		Args:   cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runUpdateWindows(args[0])
		},
	}
}

func newOnWindowUnlinkedCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_on-window-unlinked <session> <window>",
		Short:  "tmux window-unlinked hook",
		Hidden: true,
		Args:   cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runUpdateWindows(args[0])
		},
	}
}

func runUpdateWindows(session string) error {
	// No event for Phase 4.3 — window churn is noisy and the phone
	// refreshes the session file itself. Just keep the counter fresh.
	existing, err := internal.ReadSession(session)
	if err != nil {
		return err
	}
	if existing == nil {
		// session-created may fire *after* the first window-linked for
		// tmux <3.3; create a minimal record so the counter still lands.
		existing = &internal.Session{
			Name:      session,
			CreatedAt: time.Now().UTC().Format(time.RFC3339Nano),
			Status:    "idle",
		}
	}
	existing.Windows = countWindows(session)
	return internal.WriteSession(*existing)
}

// countWindows asks tmux. Zero on failure (tmux may not be in PATH when
// running tests; the session still writes, just with 0 windows).
func countWindows(session string) int {
	out, err := exec.Command("tmux", "list-windows", "-t", session, "-F", "x").Output()
	if err != nil {
		return 0
	}
	n := 0
	for _, line := range strings.Split(strings.TrimRight(string(out), "\n"), "\n") {
		if line != "" {
			n++
		}
	}
	return n
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

// resolveSessionName returns the active tmux session name, or a
// pseudo-session keyed on the shell PID when not inside tmux. The
// pseudo-session lets the phone attribute plain-shell events to a
// stable "tab" even when no tmux is involved.
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

	_ = internal.RotateIfNeeded(internal.DefaultRotateBytes)
	return internal.AppendEvent(eventType, entry.Session, map[string]any{
		"cmd":         entry.Cmd,
		"duration_ms": durationMs,
		"exit_code":   exitCode,
		"pwd":         entry.Pwd,
	})
}
