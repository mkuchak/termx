package cmd

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/mkuchak/termx/termxd/cmd/internal"
	"github.com/spf13/cobra"
)

func newUninstallCmd() *cobra.Command {
	var yes bool
	var keepData bool

	c := &cobra.Command{
		Use:   "uninstall",
		Short: "Remove marked blocks, _termx_managed hook entries, and (optionally) ~/.termx/",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return runUninstall(cmd.InOrStdin(), cmd.OutOrStdout(), cmd.ErrOrStderr(), yes, keepData)
		},
	}
	c.Flags().BoolVar(&yes, "yes", false, "skip confirmation prompt")
	c.Flags().BoolVar(&keepData, "keep-data", false, "preserve ~/.termx/ contents (default: delete)")
	return c
}

func runUninstall(stdin io.Reader, stdout, stderr io.Writer, yes, keepData bool) error {
	paths, err := internal.ResolvePaths()
	if err != nil {
		return err
	}

	if !yes {
		fmt.Fprintf(stdout, "This will remove termx marked blocks from rc files, strip _termx_managed hook entries from %s", paths.ClaudeSettings)
		if !keepData {
			fmt.Fprint(stdout, ", and delete ~/.termx/")
		}
		fmt.Fprintln(stdout, ".")
		fmt.Fprint(stdout, "Proceed? [y/N]: ")
		r := bufio.NewReader(stdin)
		ans, _ := r.ReadString('\n')
		ans = strings.TrimSpace(strings.ToLower(ans))
		if ans != "y" && ans != "yes" {
			fmt.Fprintln(stdout, "aborted.")
			return nil
		}
	}

	summary := struct {
		BlocksRemoved  int
		HooksStripped  bool
		ServiceRemoved bool
		DataRemoved    bool
	}{}

	// Strip marked blocks from all rc files.
	rcFiles := []string{paths.Bashrc, paths.Zshrc, paths.TmuxConf}
	for _, f := range rcFiles {
		changed, err := internal.RemoveMarkedBlock(f)
		if err != nil {
			fmt.Fprintf(stderr, "warning: %s: %v\n", f, err)
			continue
		}
		if changed {
			summary.BlocksRemoved++
			fmt.Fprintf(stdout, "removed block: %s\n", f)
		}
	}

	// Remove the herdr-watcher systemd user service. Best-effort: stop +
	// disable (ignore failures — the unit may never have been enabled, or
	// systemd --user may be absent) then delete the unit file.
	summary.ServiceRemoved = removeHerdrWatchService(paths, stdout, stderr)

	// Strip termx-managed hooks from Claude settings.
	changed, err := internal.StripManagedFromClaudeSettings(paths.ClaudeSettings)
	if err != nil {
		fmt.Fprintf(stderr, "warning: %s: %v\n", paths.ClaudeSettings, err)
	} else if changed {
		summary.HooksStripped = true
		fmt.Fprintf(stdout, "stripped _termx_managed hooks: %s\n", paths.ClaudeSettings)
	}

	// Delete ~/.termx/ unless --keep-data.
	if !keepData {
		if internal.Exists(paths.TermxDir) {
			if err := os.RemoveAll(paths.TermxDir); err != nil {
				return fmt.Errorf("remove %s: %w", paths.TermxDir, err)
			}
			summary.DataRemoved = true
			fmt.Fprintf(stdout, "deleted: %s\n", paths.TermxDir)
		}
	} else {
		fmt.Fprintf(stdout, "preserved: %s (--keep-data)\n", paths.TermxDir)
	}

	// Binary removal: print the one-liner rather than self-delete, for portability.
	if internal.Exists(paths.LocalBinTermx) {
		fmt.Fprintf(stdout, "Run `rm %s` manually to remove the binary.\n", paths.LocalBinTermx)
	}

	// A self-hosted ntfy server (if installed via `termx install --with-ntfy`)
	// is intentionally left in place — it is a standalone, root-owned service
	// the user may rely on for other apps.
	fmt.Fprintln(stdout, "Note: a self-hosted ntfy server (if any) was left in place; remove it manually if no longer needed.")

	fmt.Fprintf(stdout, "Summary: blocks_removed=%d hooks_stripped=%t service_removed=%t data_removed=%t\n",
		summary.BlocksRemoved, summary.HooksStripped, summary.ServiceRemoved, summary.DataRemoved)
	return nil
}

// removeHerdrWatchService best-effort stops + disables the watcher user unit
// and deletes its file. Returns true if the unit file was actually removed.
// Never fails the uninstall — systemctl errors (no systemd --user, never
// enabled) are ignored; only a real file-removal error is surfaced as a
// warning.
func removeHerdrWatchService(p *internal.Paths, stdout, stderr io.Writer) bool {
	// Stop/disable are advisory — discard their output and ignore errors.
	_ = runShell("systemctl --user stop "+herdrWatchUnitName, io.Discard, io.Discard)
	_ = runShell("systemctl --user disable "+herdrWatchUnitName, io.Discard, io.Discard)

	unitPath := filepath.Join(p.Home, ".config", "systemd", "user", herdrWatchUnitName)
	if err := os.Remove(unitPath); err != nil {
		if os.IsNotExist(err) {
			return false
		}
		fmt.Fprintf(stderr, "warning: could not remove %s: %v\n", unitPath, err)
		return false
	}
	fmt.Fprintf(stdout, "removed service unit: %s\n", unitPath)
	return true
}
