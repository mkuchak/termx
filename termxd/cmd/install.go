package cmd

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/mkuchak/termx/termxd/cmd/internal"
	"github.com/spf13/cobra"
)

const shellHooksStub = `#!/usr/bin/env sh
# termx shell hooks — TODO: P4.3 will replace this stub with real preexec /
# precmd wrappers that emit events into ~/.termx/events.ndjson.
# For now this file is sourced by .bashrc / .zshrc as a no-op.
`

const bashrcBlockBody = `export PATH="$HOME/.local/bin:$PATH"
[ -f "$HOME/.termx/termx-shell-hooks.sh" ] && . "$HOME/.termx/termx-shell-hooks.sh"`

const tmuxBlockBody = `set-hook -g session-created  'run-shell "~/.local/bin/termx _on-session-created #{session_name}"'
set-hook -g session-closed   'run-shell "~/.local/bin/termx _on-session-closed #{session_name}"'
set-hook -g window-linked    'run-shell "~/.local/bin/termx _on-window-linked #{session_name} #{window_name}"'
set-hook -g window-unlinked  'run-shell "~/.local/bin/termx _on-window-unlinked #{session_name} #{window_name}"'`

// Change is one entry of the --dry-run JSON output.
type Change struct {
	Type string `json:"type"`
	Path string `json:"path"`
	Mode string `json:"mode,omitempty"`
	Diff string `json:"diff,omitempty"`
	Note string `json:"note,omitempty"`
}

type dryRunReport struct {
	Changes []Change `json:"changes"`
}

func newInstallCmd() *cobra.Command {
	var dryRun bool
	var installDeps bool

	c := &cobra.Command{
		Use:   "install",
		Short: "Provision ~/.termx/, self-install to ~/.local/bin/termx, inject marked rc blocks",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return runInstall(cmd.OutOrStdout(), cmd.ErrOrStderr(), dryRun, installDeps)
		},
	}
	c.Flags().BoolVar(&dryRun, "dry-run", false, "print JSON diff of proposed changes; no disk mutation")
	c.Flags().BoolVar(&installDeps, "install-deps", false, "attempt sudo install of missing system packages (mosh, tmux)")
	return c
}

func runInstall(stdout, stderr io.Writer, dryRun, installDeps bool) error {
	paths, err := internal.ResolvePaths()
	if err != nil {
		return err
	}

	if dryRun {
		report, err := buildDryRun(paths)
		if err != nil {
			return err
		}
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(report)
	}

	// Phase A: distro detection + dependency check (always reported to stderr
	// so stdout can carry structured output from other commands in the future).
	distro, derr := internal.DetectDistro()
	if derr != nil {
		fmt.Fprintf(stderr, "warning: could not read /etc/os-release: %v\n", derr)
	}
	if distro == internal.DistroUnknown {
		fmt.Fprintln(stderr, "warning: unknown distro — shell/tmux/claude steps will still run, system packages not installed")
	} else {
		fmt.Fprintf(stdout, "distro: %s\n", distro)
	}

	if err := handleDependencies(stdout, stderr, distro, installDeps); err != nil {
		return err
	}

	// Phase B: filesystem tree.
	if err := ensureTermxTree(paths, stdout); err != nil {
		return err
	}

	// Phase C: self-install.
	if err := selfInstallBinary(paths, stdout); err != nil {
		return err
	}

	// Phase D: rc-file injection.
	for _, p := range rcShellFiles(paths) {
		changed, err := internal.UpsertMarkedBlock(p, bashrcBlockBody, 0o644)
		if err != nil {
			return fmt.Errorf("inject %s: %w", p, err)
		}
		reportFileOp(stdout, "inject_block", p, changed)
	}
	if changed, err := internal.UpsertMarkedBlock(paths.TmuxConf, tmuxBlockBody, 0o644); err != nil {
		return fmt.Errorf("inject %s: %w", paths.TmuxConf, err)
	} else {
		reportFileOp(stdout, "inject_block", paths.TmuxConf, changed)
	}
	if changed, err := internal.UpsertClaudeSettings(paths.ClaudeSettings); err != nil {
		return fmt.Errorf("update %s: %w", paths.ClaudeSettings, err)
	} else {
		reportFileOp(stdout, "update_json", paths.ClaudeSettings, changed)
	}

	fmt.Fprintln(stdout, "termx install complete.")
	return nil
}

// rcShellFiles returns the shell rc files to manage. .bashrc is always
// included; .zshrc is only included if it already exists (don't create it on
// bash-only systems).
func rcShellFiles(p *internal.Paths) []string {
	out := []string{p.Bashrc}
	if internal.Exists(p.Zshrc) {
		out = append(out, p.Zshrc)
	}
	return out
}

func reportFileOp(w io.Writer, op, path string, changed bool) {
	if changed {
		fmt.Fprintf(w, "%s: %s\n", op, path)
	} else {
		fmt.Fprintf(w, "%s: %s (unchanged)\n", op, path)
	}
}

// ---- dependency handling ----

var requiredCommands = []struct {
	Name        string
	SystemPkg   string // package manager name; empty means "not a system package"
	ClaudeExtra bool   // true for `claude` (installed via npm, not apt)
}{
	{Name: "mosh-server", SystemPkg: "mosh"},
	{Name: "tmux", SystemPkg: "tmux"},
	{Name: "node", SystemPkg: "nodejs"},
	{Name: "npm", SystemPkg: "npm"},
	{Name: "claude", SystemPkg: "", ClaudeExtra: true},
}

func handleDependencies(stdout, stderr io.Writer, distro internal.Distro, installDeps bool) error {
	missingSys := []string{}
	missingClaude := false

	for _, dep := range requiredCommands {
		if _, err := exec.LookPath(dep.Name); err == nil {
			continue
		}
		if dep.ClaudeExtra {
			missingClaude = true
			continue
		}
		if dep.SystemPkg != "" {
			missingSys = append(missingSys, dep.SystemPkg)
		}
	}

	// Dedup system packages (nodejs + npm often come from the same package on
	// some distros).
	missingSys = dedup(missingSys)

	if len(missingSys) > 0 {
		cmdStr := internal.InstallCmdFor(distro, missingSys...)
		fmt.Fprintf(stdout, "missing system packages: %s\n", strings.Join(missingSys, ", "))
		fmt.Fprintf(stdout, "  suggested: %s\n", cmdStr)
		if !installDeps {
			fmt.Fprintln(stderr, "re-run with --install-deps to attempt automatic sudo install, or run the command above manually.")
			return errors.New("missing system packages; aborting (pass --install-deps to auto-install)")
		}
		fmt.Fprintf(stdout, "  running: %s\n", cmdStr)
		if err := runShell(cmdStr, stdout, stderr); err != nil {
			return fmt.Errorf("package install failed: %w", err)
		}
	}

	if missingClaude {
		// Re-lookup npm in case we just installed it.
		if _, err := exec.LookPath("npm"); err == nil {
			fmt.Fprintln(stdout, "installing claude-code via npm (user-global)...")
			if err := runShell("npm i -g @anthropic-ai/claude-code", stdout, stderr); err != nil {
				fmt.Fprintf(stderr, "warning: claude-code install failed: %v\n", err)
				fmt.Fprintln(stderr, "continue manually with: npm i -g @anthropic-ai/claude-code")
			}
		} else {
			fmt.Fprintln(stderr, "warning: npm not present; skip claude-code install. Install Node.js then run: npm i -g @anthropic-ai/claude-code")
		}
	}

	return nil
}

func runShell(cmdStr string, stdout, stderr io.Writer) error {
	c := exec.Command("sh", "-c", cmdStr)
	c.Stdout = stdout
	c.Stderr = stderr
	return c.Run()
}

func dedup(in []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	return out
}

// ---- filesystem tree ----

func ensureTermxTree(p *internal.Paths, w io.Writer) error {
	dirs := []string{p.TermxDir, p.SessionsDir, p.ApprovalsDir, p.DiffsDir, p.CommandsDir}
	for _, d := range dirs {
		existed := internal.Exists(d)
		if err := os.MkdirAll(d, 0o700); err != nil {
			return fmt.Errorf("mkdir %s: %w", d, err)
		}
		reportFileOp(w, "mkdir", d, !existed)
	}
	if !internal.Exists(p.EventsFile) {
		if err := os.WriteFile(p.EventsFile, []byte{}, 0o600); err != nil {
			return fmt.Errorf("create %s: %w", p.EventsFile, err)
		}
		reportFileOp(w, "write_file", p.EventsFile, true)
	} else {
		reportFileOp(w, "write_file", p.EventsFile, false)
	}
	// Shell hooks stub. Always overwrite with the stub content iff content is
	// missing or unchanged; otherwise preserve user edits.
	existing, err := os.ReadFile(p.ShellHooksFile)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if len(existing) == 0 {
		if err := os.WriteFile(p.ShellHooksFile, []byte(shellHooksStub), 0o600); err != nil {
			return fmt.Errorf("write %s: %w", p.ShellHooksFile, err)
		}
		reportFileOp(w, "write_file", p.ShellHooksFile, true)
	} else {
		reportFileOp(w, "write_file", p.ShellHooksFile, false)
	}
	return nil
}

// ---- self-install ----

func selfInstallBinary(p *internal.Paths, w io.Writer) error {
	if err := os.MkdirAll(p.LocalBin, 0o755); err != nil {
		return fmt.Errorf("mkdir %s: %w", p.LocalBin, err)
	}
	self, err := os.Executable()
	if err != nil {
		return fmt.Errorf("os.Executable: %w", err)
	}
	selfAbs, _ := filepath.EvalSymlinks(self)
	if selfAbs == "" {
		selfAbs = self
	}
	dstAbs, _ := filepath.EvalSymlinks(p.LocalBinTermx)
	if dstAbs == "" {
		dstAbs = p.LocalBinTermx
	}
	if selfAbs == dstAbs && internal.Exists(p.LocalBinTermx) {
		reportFileOp(w, "install_binary", p.LocalBinTermx, false)
		return nil
	}
	if internal.Exists(p.LocalBinTermx) {
		// Compare byte contents; skip if identical.
		if same, _ := filesEqual(self, p.LocalBinTermx); same {
			reportFileOp(w, "install_binary", p.LocalBinTermx, false)
			return nil
		}
	}
	if err := copyFile(self, p.LocalBinTermx, 0o755); err != nil {
		return fmt.Errorf("install %s: %w", p.LocalBinTermx, err)
	}
	reportFileOp(w, "install_binary", p.LocalBinTermx, true)
	return nil
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".termx-tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Chmod(tmp, mode); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, dst)
}

func filesEqual(a, b string) (bool, error) {
	sa, err := os.Stat(a)
	if err != nil {
		return false, err
	}
	sb, err := os.Stat(b)
	if err != nil {
		return false, err
	}
	if sa.Size() != sb.Size() {
		return false, nil
	}
	fa, err := os.Open(a)
	if err != nil {
		return false, err
	}
	defer fa.Close()
	fb, err := os.Open(b)
	if err != nil {
		return false, err
	}
	defer fb.Close()
	bufA := make([]byte, 64*1024)
	bufB := make([]byte, 64*1024)
	for {
		na, ea := io.ReadFull(fa, bufA)
		nb, eb := io.ReadFull(fb, bufB)
		if na != nb {
			return false, nil
		}
		if na > 0 && string(bufA[:na]) != string(bufB[:nb]) {
			return false, nil
		}
		if ea == io.EOF || ea == io.ErrUnexpectedEOF {
			if !(eb == io.EOF || eb == io.ErrUnexpectedEOF) {
				return false, nil
			}
			return true, nil
		}
		if ea != nil {
			return false, ea
		}
		if eb != nil {
			return false, eb
		}
	}
}

// ---- dry-run ----

func buildDryRun(p *internal.Paths) (dryRunReport, error) {
	short := func(abs string) string {
		if strings.HasPrefix(abs, p.Home+"/") {
			return "~/" + strings.TrimPrefix(abs, p.Home+"/")
		}
		return abs
	}

	changes := []Change{}
	for _, d := range []string{p.TermxDir, p.SessionsDir, p.ApprovalsDir, p.DiffsDir, p.CommandsDir} {
		c := Change{Type: "mkdir", Path: short(d), Mode: "0700"}
		if internal.Exists(d) {
			c.Note = "already exists"
		}
		changes = append(changes, c)
	}
	for _, f := range []string{p.EventsFile, p.ShellHooksFile} {
		c := Change{Type: "write_file", Path: short(f), Mode: "0600"}
		if internal.Exists(f) {
			c.Note = "already exists"
		}
		changes = append(changes, c)
	}
	// Self-install.
	c := Change{Type: "install_binary", Path: short(p.LocalBinTermx), Mode: "0755"}
	if internal.Exists(p.LocalBinTermx) {
		c.Note = "already exists"
	}
	changes = append(changes, c)

	// rc blocks.
	rcs := []string{p.Bashrc}
	if internal.Exists(p.Zshrc) {
		rcs = append(rcs, p.Zshrc)
	}
	for _, rc := range rcs {
		ch := Change{
			Type: "inject_block",
			Path: short(rc),
			Diff: fmt.Sprintf("+ 2 lines in `%s / %s`", internal.BeginMarker, internal.EndMarker),
		}
		if internal.HasMarkedBlock(rc) {
			ch.Note = "block already present (will be replaced in place)"
		}
		changes = append(changes, ch)
	}
	// tmux.
	ch := Change{
		Type: "inject_block",
		Path: short(p.TmuxConf),
		Diff: fmt.Sprintf("+ 4 lines in `%s / %s`", internal.BeginMarker, internal.EndMarker),
	}
	if internal.HasMarkedBlock(p.TmuxConf) {
		ch.Note = "block already present (will be replaced in place)"
	}
	changes = append(changes, ch)

	// Claude settings.json.
	sc := Change{Type: "update_json", Path: short(p.ClaudeSettings), Mode: "0600", Diff: "add hooks.PreToolUse + hooks.PostToolUse entries tagged `_termx_managed: true`"}
	if internal.Exists(p.ClaudeSettings) {
		sc.Note = "will preserve existing keys; replace any existing _termx_managed entries"
	} else {
		sc.Note = "will create file"
	}
	changes = append(changes, sc)

	return dryRunReport{Changes: changes}, nil
}
