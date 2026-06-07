// Package cmd wires the termx CLI subcommand tree.
package cmd

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/spf13/cobra"
)

// Version is overridden via -ldflags at build time.
var Version = "dev"

// Execute is the single entry point called from main. It runs under a
// signal-cancelled context so long-running subcommands (e.g. watch-herdr)
// can shut down cleanly on SIGINT/SIGTERM; short-lived commands ignore it.
func Execute() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	root := newRootCmd()
	if err := root.ExecuteContext(ctx); err != nil {
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
		newWatchHerdrCmd(),
	)
	for _, c := range newHiddenHelperCmds() {
		root.AddCommand(c)
	}
	return root
}
