// Package internal contains shared helpers for the termx CLI subcommands.
package internal

import (
	"os"
	"path/filepath"
)

// Paths holds the canonical filesystem locations termx writes to. All paths
// are absolute and resolved from the current user's home directory.
type Paths struct {
	Home             string
	TermxDir         string
	SessionsDir      string
	ApprovalsDir     string
	DiffsDir         string
	CommandsDir      string
	EventsFile       string
	ShellHooksFile   string
	LocalBin         string
	LocalBinTermx    string
	Bashrc           string
	Zshrc            string
	TmuxConf         string
	ClaudeDir        string
	ClaudeSettings   string
}

// ResolvePaths returns canonical paths based on $HOME (or os/user fallback).
func ResolvePaths() (*Paths, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil, err
	}
	termxDir := filepath.Join(home, ".termx")
	localBin := filepath.Join(home, ".local", "bin")
	claudeDir := filepath.Join(home, ".claude")
	return &Paths{
		Home:           home,
		TermxDir:       termxDir,
		SessionsDir:    filepath.Join(termxDir, "sessions"),
		ApprovalsDir:   filepath.Join(termxDir, "approvals"),
		DiffsDir:       filepath.Join(termxDir, "diffs"),
		CommandsDir:    filepath.Join(termxDir, "commands"),
		EventsFile:     filepath.Join(termxDir, "events.ndjson"),
		ShellHooksFile: filepath.Join(termxDir, "termx-shell-hooks.sh"),
		LocalBin:       localBin,
		LocalBinTermx:  filepath.Join(localBin, "termx"),
		Bashrc:         filepath.Join(home, ".bashrc"),
		Zshrc:          filepath.Join(home, ".zshrc"),
		TmuxConf:       filepath.Join(home, ".tmux.conf"),
		ClaudeDir:      claudeDir,
		ClaudeSettings: filepath.Join(claudeDir, "settings.json"),
	}, nil
}

// Exists reports whether a path exists (file or directory).
func Exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
