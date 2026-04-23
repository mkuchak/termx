# termxd

Go VPS companion for termx. Coming soon.

This directory will hold a single static Go binary distributed per arch via
GitHub releases. It provides the bootstrap installer, the PreToolUse hook
script that blocks waiting for phone approval, the session registry
(`~/.termx/sessions/*.json`), the event stream (`~/.termx/events.ndjson`), and
tmux/shell integration (session-created/closed hooks, preexec/precmd).

Current state: placeholder. First meaningful code will land in a follow-up PR
after the Android bootstrap PR is green.
