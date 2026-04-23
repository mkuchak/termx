package cmd

import (
	"github.com/spf13/cobra"
)

// newHiddenHelperCmds returns the stub subcommands that reserve the names
// used by tmux hooks, shell preexec/precmd, and Claude Code tool-use hooks.
// Each prints a TODO line and exits 0. Real behavior lands in Phase 4.3/5.1/5.3.
func newHiddenHelperCmds() []*cobra.Command {
	stub := func(use, phase string) *cobra.Command {
		return &cobra.Command{
			Use:    use,
			Short:  "internal hook (stub)",
			Hidden: true,
			Args:   cobra.ArbitraryArgs,
			Run: func(cmd *cobra.Command, args []string) {
				cmd.Printf("TODO: implement in Phase %s\n", phase)
			},
		}
	}
	return []*cobra.Command{
		stub("_on-session-created", "4.3"),
		stub("_on-session-closed", "4.3"),
		stub("_on-window-linked", "4.3"),
		stub("_on-window-unlinked", "4.3"),
		stub("_hook-pretooluse", "5.1"),
		stub("_hook-posttooluse", "5.3"),
	}
}
