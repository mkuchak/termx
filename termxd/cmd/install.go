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

// shellHooksScript is sourced by ~/.bashrc and ~/.zshrc (via the marked
// block). It wires termx's _preexec / _precmd hooks into the active
// shell so every long command becomes an event the phone can render.
//
// Both hook invocations are backgrounded (&) and silenced (>/dev/null
// 2>&1) so a broken termx binary can never wedge the user's prompt.
// The bash side guards against the DEBUG trap firing for termx's own
// helper calls via _termx_in_preexec recursion guard.
const shellHooksScript = `#!/usr/bin/env sh
# termx shell hooks — sourced by bash/zsh rc files.
# Invokes $HOME/.local/bin/termx _preexec / _precmd around each command.
#
# Design notes (learned the hard way):
#  * Background calls use a subshell wrapper — "( cmd & )" instead of
#    "cmd &" — so the job lives in a throwaway subshell's table and the
#    parent interactive shell never prints "[1] Done ..." notifications.
#  * The bash DEBUG trap is NOT installed at source time. If it were,
#    every statement remaining in .bashrc (case, PROMPT_COMMAND=..., the
#    default Ubuntu PATH checks) would fire the trap and background a
#    termx call. Instead we schedule trap installation via a one-shot
#    PROMPT_COMMAND entry that arms the trap on the FIRST prompt and
#    removes itself — by that time, rc sourcing is complete and only
#    real user commands will hit the trap.

_termx_bin="$HOME/.local/bin/termx"

if [ -n "$ZSH_VERSION" ]; then
    autoload -Uz add-zsh-hook
    _termx_preexec() {
        local cmd="$1"
        ( "$_termx_bin" _preexec "$(printf '%s' "$cmd" | base64 | tr -d '\n')" >/dev/null 2>&1 & )
    }
    _termx_precmd() {
        local ec=$?
        ( "$_termx_bin" _precmd "$ec" >/dev/null 2>&1 & )
    }
    add-zsh-hook preexec _termx_preexec
    add-zsh-hook precmd  _termx_precmd
elif [ -n "$BASH_VERSION" ]; then
    _termx_in_precmd=0

    _termx_preexec() {
        # Skip the precmd call itself (it fires via PROMPT_COMMAND with
        # DEBUG trap still active) and any command issued from within
        # precmd.
        [ "$BASH_COMMAND" = "_termx_precmd" ] && return
        [ "$_termx_in_precmd" = "1" ] && return
        [ -z "$BASH_COMMAND" ] && return
        ( "$_termx_bin" _preexec "$(printf '%s' "$BASH_COMMAND" | base64 | tr -d '\n')" >/dev/null 2>&1 & )
    }

    _termx_precmd() {
        local ec=$?
        _termx_in_precmd=1
        ( "$_termx_bin" _precmd "$ec" >/dev/null 2>&1 & )
        _termx_in_precmd=0
    }

    # One-shot arm: install the DEBUG trap at the first prompt, then
    # remove ourselves from PROMPT_COMMAND so this never runs again.
    _termx_arm_debug_trap() {
        trap '_termx_preexec' DEBUG
        PROMPT_COMMAND=$(printf '%s' "$PROMPT_COMMAND" | sed 's/_termx_arm_debug_trap;//')
    }

    # Prepend arm + precmd to PROMPT_COMMAND, once.
    case ";$PROMPT_COMMAND;" in
        *";_termx_precmd;"*) : ;;
        *) PROMPT_COMMAND="_termx_arm_debug_trap;_termx_precmd;$PROMPT_COMMAND" ;;
    esac
fi
`

const bashrcBlockBody = `export PATH="$HOME/.local/bin:$PATH"
[ -f "$HOME/.termx/termx-shell-hooks.sh" ] && . "$HOME/.termx/termx-shell-hooks.sh"`

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
	var withNtfy bool

	c := &cobra.Command{
		Use:   "install",
		Short: "Provision ~/.termx/, self-install to ~/.local/bin/termx, inject marked rc blocks",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return runInstall(cmd.OutOrStdout(), cmd.ErrOrStderr(), dryRun, installDeps, withNtfy)
		},
	}
	c.Flags().BoolVar(&dryRun, "dry-run", false, "print JSON diff of proposed changes; no disk mutation")
	c.Flags().BoolVar(&installDeps, "install-deps", false, "attempt sudo install of missing system packages (mosh)")
	c.Flags().BoolVar(&withNtfy, "with-ntfy", false, "also install a self-hosted ntfy server for Tier-2 pushes (optional; needs root + manual TLS)")
	return c
}

func runInstall(stdout, stderr io.Writer, dryRun, installDeps, withNtfy bool) error {
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
		fmt.Fprintln(stderr, "warning: unknown distro — shell/claude steps will still run, system packages not installed")
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

	// Phase C2: herdr-watcher systemd user service. Best-effort throughout —
	// a host without systemd --user must still get a working core install.
	ensureHerdrWatchService(paths, stdout, stderr)

	// Phase D: rc-file injection.
	for _, p := range rcShellFiles(paths) {
		changed, err := internal.UpsertMarkedBlock(p, bashrcBlockBody, 0o644)
		if err != nil {
			return fmt.Errorf("inject %s: %w", p, err)
		}
		reportFileOp(stdout, "inject_block", p, changed)
	}
	// Cleanup: machines provisioned by an older installer carry a stale
	// 4-line tmux hook block in ~/.tmux.conf. termx no longer manages tmux,
	// so strip that block on every install. Non-fatal — a failure here must
	// not abort the rest of the install.
	if changed, err := internal.RemoveMarkedBlock(paths.TmuxConf); err != nil {
		fmt.Fprintf(stderr, "warning: could not clean stale tmux block from %s: %v\n", paths.TmuxConf, err)
	} else {
		reportFileOp(stdout, "remove_block", paths.TmuxConf, changed)
	}
	if changed, err := internal.UpsertClaudeSettings(paths.ClaudeSettings); err != nil {
		return fmt.Errorf("update %s: %w", paths.ClaudeSettings, err)
	} else {
		reportFileOp(stdout, "update_json", paths.ClaudeSettings, changed)
	}

	// Phase E: OPTIONAL self-hosted ntfy server (only when --with-ntfy). Kept
	// fully separate from the core install — it is an advanced, root-requiring
	// step and must never affect the success of everything above.
	if withNtfy {
		ensureNtfyServer(paths, stdout, stderr)
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
	// Shell hooks script. Always overwrite with the canonical content —
	// the script is a pure artifact of install, and preserving user edits
	// here is a footgun that prevents shipping fixes via a new release.
	existing, err := os.ReadFile(p.ShellHooksFile)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if string(existing) != shellHooksScript {
		if err := os.WriteFile(p.ShellHooksFile, []byte(shellHooksScript), 0o700); err != nil {
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

// ---- herdr-watcher systemd user service ----

// herdrWatchUnitName is the systemd user unit filename for the watcher.
const herdrWatchUnitName = "termx-herdr-watch.service"

// herdrWatchUnit is the systemd USER unit that keeps `termx watch-herdr`
// running 24/7 (survives logout/reboot, restarts on crash) so Tier-2 agent
// completions are caught even with no active login. `%h` is systemd's
// home-dir specifier, so no path interpolation is needed here.
//
// Environment=PATH explicitly includes %h/.local/bin: systemd --user runs
// services with a sanitized PATH that omits ~/.local/bin, and the watcher
// shells out to the `herdr` CLI which is commonly installed there. Without
// this the daemon can't find herdr and silently retries forever.
const herdrWatchUnit = `[Unit]
Description=termx herdr watcher
After=network-online.target

[Service]
Environment=PATH=%h/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ExecStart=%h/.local/bin/termx watch-herdr
Restart=always
RestartSec=2

[Install]
WantedBy=default.target
`

// ensureHerdrWatchService writes the systemd user unit and best-effort
// enables it. Every external step is guarded: a host without systemd --user
// (containers, minimal VPSes) prints a manual-start note and the core install
// still succeeds. Never returns an error — failures degrade to warnings.
func ensureHerdrWatchService(p *internal.Paths, stdout, stderr io.Writer) {
	unitDir := filepath.Join(p.Home, ".config", "systemd", "user")
	if err := os.MkdirAll(unitDir, 0o755); err != nil {
		fmt.Fprintf(stderr, "warning: could not create %s: %v\n", unitDir, err)
		fmt.Fprintln(stderr, "  start the watcher manually with: termx watch-herdr")
		return
	}
	unitPath := filepath.Join(unitDir, herdrWatchUnitName)
	if err := os.WriteFile(unitPath, []byte(herdrWatchUnit), 0o644); err != nil {
		fmt.Fprintf(stderr, "warning: could not write %s: %v\n", unitPath, err)
		fmt.Fprintln(stderr, "  start the watcher manually with: termx watch-herdr")
		return
	}
	reportFileOp(stdout, "write_unit", unitPath, true)

	// If systemd --user isn't reachable (no user bus / not PID 1 systemd),
	// don't attempt enable; just tell the user how to run it.
	if !systemdUserAvailable() {
		fmt.Fprintln(stderr, "note: systemd --user not available; the herdr watcher unit was written but not started.")
		fmt.Fprintln(stderr, "  start it manually (e.g. via nohup/tmux) with: termx watch-herdr")
		return
	}

	// linger lets the user service run without an active login. Needs
	// privilege, so a failure is expected on locked-down hosts — guide the
	// user rather than abort.
	if err := runShell("loginctl enable-linger $USER", stdout, stderr); err != nil {
		fmt.Fprintf(stderr, "warning: could not enable linger: %v\n", err)
		fmt.Fprintln(stderr, "  run `sudo loginctl enable-linger $USER` so the watcher runs without an active login")
	}
	if err := runShell("systemctl --user daemon-reload", stdout, stderr); err != nil {
		fmt.Fprintf(stderr, "warning: systemctl --user daemon-reload failed: %v\n", err)
	}
	if err := runShell("systemctl --user enable --now "+herdrWatchUnitName, stdout, stderr); err != nil {
		fmt.Fprintf(stderr, "warning: could not enable/start %s: %v\n", herdrWatchUnitName, err)
		fmt.Fprintf(stderr, "  enable it manually with: systemctl --user enable --now %s\n", herdrWatchUnitName)
		return
	}
	fmt.Fprintf(stdout, "enabled service: %s\n", herdrWatchUnitName)
}

// systemdUserAvailable reports whether a usable systemd --user instance is
// reachable, so we only attempt enable when it can actually succeed.
func systemdUserAvailable() bool {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return false
	}
	// `systemctl --user is-system-running` exits non-zero with no user bus;
	// it succeeds (or returns a known degraded/starting state) when present.
	// Any clean exit means the user manager answered.
	return exec.Command("systemctl", "--user", "is-system-running").Run() == nil
}

// ---- optional self-hosted ntfy server (--with-ntfy) ----

// ntfyServerYML is a minimal starter config. base-url is a CHANGE-ME stub the
// user edits to their real TLS subdomain; listen-http stays loopback-only so
// the reverse proxy terminates TLS.
const ntfyServerYML = `base-url: https://ntfy.CHANGE-ME
listen-http: "127.0.0.1:2586"
`

// ensureNtfyServer is the OPTIONAL assisted install of a self-hosted ntfy
// server. Clearly separate from the core install and best-effort: every step
// is guarded and degrades to printing the manual recipe. Android push requires
// a valid (non-self-signed) TLS cert, which only the user can provision, so
// TLS is always a printed manual step.
func ensureNtfyServer(p *internal.Paths, stdout, stderr io.Writer) {
	fmt.Fprintln(stdout, "--- optional: self-hosted ntfy server ---")

	// 1. Install the ntfy binary if it isn't already present. Mirror the
	//    install pattern used elsewhere (a shell command via runShell). The
	//    upstream one-liner installs the apt repo + package; fall back to the
	//    manual recipe on any failure.
	if _, err := exec.LookPath("ntfy"); err != nil {
		fmt.Fprintln(stdout, "installing ntfy server...")
		install := "curl -sSL https://get.ntfy.sh | sudo sh"
		if err := runShell(install, stdout, stderr); err != nil {
			fmt.Fprintf(stderr, "warning: ntfy install failed: %v\n", err)
			printNtfyManualRecipe(p, stderr)
			return
		}
	} else {
		fmt.Fprintln(stdout, "ntfy already installed.")
	}

	// Verify the binary actually runs before configuring it.
	if err := runShell("ntfy --version", stdout, stderr); err != nil {
		fmt.Fprintf(stderr, "warning: `ntfy --version` did not run: %v\n", err)
		printNtfyManualRecipe(p, stderr)
		return
	}

	// 2. Write a starter server.yml. /etc needs root, so write via sudo tee
	//    and fall back to the manual recipe (incl. the config body) on
	//    failure.
	const serverYMLPath = "/etc/ntfy/server.yml"
	writeCfg := fmt.Sprintf("sudo mkdir -p /etc/ntfy && printf '%%s' %s | sudo tee %s >/dev/null",
		shellQuote(ntfyServerYML), serverYMLPath)
	if err := runShell(writeCfg, stdout, stderr); err != nil {
		fmt.Fprintf(stderr, "warning: could not write %s: %v\n", serverYMLPath, err)
		printNtfyManualRecipe(p, stderr)
		return
	}
	fmt.Fprintf(stdout, "wrote %s (edit base-url to your real TLS subdomain)\n", serverYMLPath)

	// 3. Grant anonymous write-only access to up* topics (the phone-contract
	//    anonymous-write convention). Best-effort.
	if err := runShell("sudo ntfy access '*' 'up*' write-only", stdout, stderr); err != nil {
		fmt.Fprintf(stderr, "warning: could not set ntfy access: %v\n", err)
		fmt.Fprintln(stderr, "  set it manually with: sudo ntfy access '*' 'up*' write-only")
	}

	// 4. TLS is always manual — print the recipe.
	printNtfyTLSRecipe(stdout)
}

// printNtfyTLSRecipe prints the manual TLS / reverse-proxy recipe. Android
// rejects self-signed certs, so the user must point a real DNS subdomain at a
// proxy that terminates valid TLS in front of ntfy's loopback listener.
func printNtfyTLSRecipe(w io.Writer) {
	fmt.Fprintln(w, "ntfy TLS (manual): put a reverse proxy with a valid cert in front of 127.0.0.1:2586.")
	fmt.Fprintln(w, "  Caddyfile:")
	fmt.Fprintln(w, "    ntfy.you.com { reverse_proxy 127.0.0.1:2586 }")
	fmt.Fprintln(w, "  Point a DNS subdomain here; Android requires a valid, non-self-signed cert.")
}

// printNtfyManualRecipe prints the full manual fallback when an assisted step
// fails, including the binary install, the server.yml body, and TLS.
func printNtfyManualRecipe(p *internal.Paths, w io.Writer) {
	fmt.Fprintln(w, "ntfy manual install:")
	fmt.Fprintln(w, "  1. install:  curl -sSL https://get.ntfy.sh | sudo sh")
	fmt.Fprintln(w, "  2. write /etc/ntfy/server.yml:")
	for _, line := range strings.Split(strings.TrimRight(ntfyServerYML, "\n"), "\n") {
		fmt.Fprintf(w, "       %s\n", line)
	}
	fmt.Fprintln(w, "  3. access:   sudo ntfy access '*' 'up*' write-only")
	printNtfyTLSRecipe(w)
}

// shellQuote single-quotes s for safe interpolation into an `sh -c` string,
// escaping embedded single quotes the POSIX way ('\'').
func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
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

	// herdr-watcher systemd user unit.
	unitPath := filepath.Join(p.Home, ".config", "systemd", "user", herdrWatchUnitName)
	uc := Change{
		Type: "write_unit",
		Path: short(unitPath),
		Mode: "0644",
		Note: "best-effort `systemctl --user enable --now` follows (skipped if systemd --user is unavailable)",
	}
	if internal.Exists(unitPath) {
		uc.Note = "unit already present (will be overwritten); " + uc.Note
	}
	changes = append(changes, uc)

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
	// Stale tmux block cleanup (only emitted when an old installer left one).
	if internal.HasMarkedBlock(p.TmuxConf) {
		changes = append(changes, Change{
			Type: "remove_block",
			Path: short(p.TmuxConf),
			Diff: fmt.Sprintf("- block between `%s / %s` (stale tmux hooks)", internal.BeginMarker, internal.EndMarker),
			Note: "left by an older termx installer; tmux is no longer managed",
		})
	}

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
