package main

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

var version = "dev"

func main() {
	root := &cobra.Command{
		Use:     "termx",
		Short:   "termx VPS companion — session registry, event stream, permission broker for Claude Code",
		Version: version,
	}
	// Subcommands added in P4.2+
	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
