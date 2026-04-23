package cmd

import (
	"github.com/spf13/cobra"
)

// newHiddenHelperCmds returns every hidden hook subcommand registered on
// the root cmd. The real implementations live in hooks.go; keeping this
// thin wrapper preserves the import site in root.go.
func newHiddenHelperCmds() []*cobra.Command {
	return hookCmds()
}
