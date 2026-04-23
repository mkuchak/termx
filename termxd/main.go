package main

import "github.com/mkuchak/termx/termxd/cmd"

// version is set via -ldflags at build time and mirrored into cmd.Version.
var version = "dev"

func main() {
	cmd.Version = version
	cmd.Execute()
}
