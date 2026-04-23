// Package cmd wires the termx CLI subcommand tree.
package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

// Version is overridden via -ldflags at build time.
var Version = "dev"

// Execute is the single entry point called from main.
func Execute() {
	root := newRootCmd()
	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func newRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:     "termx",
		Short:   "termx VPS companion — session registry, event stream, permission broker for Claude Code",
		Version: Version,
	}
	root.AddCommand(
		newInstallCmd(),
		newUninstallCmd(),
	)
	for _, c := range newHiddenHelperCmds() {
		root.AddCommand(c)
	}
	return root
}
