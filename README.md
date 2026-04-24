## termx

[![android-release](https://github.com/mkuchak/termx/actions/workflows/android-release.yml/badge.svg)](https://github.com/mkuchak/termx/actions/workflows/android-release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> Your VPS in your pocket. A FOSS Android SSH / mosh / tmux client designed
> as a mobile control surface for Claude Code.

## Why termx

Claude Code is powerful but it lives on a VPS, which means the interesting
moments — a permission prompt, a file edit to review, a long build finally
finishing — all happen while you are somewhere else. termx connects your
phone to that VPS over SSH, mirrors your tmux sessions 1:1, and adds the
Claude-Code-specific magic around it: a permission broker, a native diff
viewer, event-driven notifications, and push-to-talk. Plain shell use works
fine too; the Claude hooks just stay dormant.

## Features

- **SSH + mosh + tmux** — tabs map 1:1 to tmux sessions (including ones you
  started from a laptop), and mosh keeps them alive across network flaps.
- **Permission broker** — Claude Code's `PreToolUse` hook pipes approvals
  to the phone; approve, deny, or approve-with-pattern from the lock
  screen. No parallel whitelist to maintain — decisions round-trip into
  `~/.claude/settings.json`.
- **Live diff viewer** — `PostToolUse` hooks capture `Write` / `Edit` /
  `NotebookEdit` output to `~/.termx/diffs/`; the phone renders them with
  +/- coloring, syntax highlighting, and a per-session "changed files"
  drawer.
- **Event notifications** — four separately-silenceable Android channels:
  permission / task / error / disconnect. Long-running command done? Ping.
  Claude idle? Ping. Build failed? Ping. Each has its own priority and
  vibration pattern.
- **Push-to-talk** — hold the FAB, speak, release. Your audio goes to
  Google Gemini (`gemini-2.5-flash-lite`) using your own API key; the
  transcription is injected directly into the active PTY — no clipboard
  dance.
- **Key vault** — SSH private keys and the Gemini API key live in a single
  blob encrypted with an Android Keystore AES-256-GCM key that requires
  biometric / device-credential authentication per operation.
- **Terminal polish** — six built-in themes (Dracula, Nord, Gruvbox,
  Tokyo Night, Catppuccin, Solarized) plus a full 16-color + fg/bg/cursor
  custom theme editor, pinch-to-zoom font sizing, URL double-tap,
  tmux-aware scrollback, drag-to-reorder server list, two user-editable
  extra-keys rows with sticky modifiers.
- **No SaaS** — zero telemetry, zero analytics, zero maintainer backend.
  Everything runs on your VPS via SSH. FCM is **not** used; termx relies on
  an Android foreground service tailing `~/.termx/events.ndjson` over the
  live connection.
- **Donations-only FOSS** — MIT licensed. No ads, no pro tier, no feature
  gating.

## Install

### From a GitHub release (recommended)

Download the latest signed APK from
[Releases](https://github.com/mkuchak/termx/releases) and sideload it.
You'll need to enable "Install unknown apps" for whichever app opens the
download (usually your browser or file manager).

### F-Droid

Pending submission. Reproducible-build metadata lives at `fastlane/metadata/`
and `metadata/dev.kuch.termx.yml`; see `docs/FDROID.md` for notes.

### Build from source

```bash
git clone https://github.com/mkuchak/termx
cd termx
npm install
cd android
./gradlew :app:assembleDebug
# APK at android/app/build/outputs/apk/debug/
```

## termxd — the VPS companion

Phase 5+ features (permission broker, diff capture, long-task
notifications) depend on a small Go static binary — the `termx` CLI,
distributed under the `termxd-v*` tag convention — running on your VPS.
The Setup Wizard in the app offers to install it for you over SSH after a
connection test. If you prefer to do it by hand:

```bash
ARCH=$(uname -m | sed -e s/x86_64/amd64/ -e s/aarch64/arm64/)
curl -fsSL "https://github.com/mkuchak/termx/releases/latest/download/termx-linux-${ARCH}" -o /tmp/termx
chmod +x /tmp/termx
/tmp/termx install
```

`termx install` is idempotent — it detects your distro, installs `mosh`
and `tmux` if missing, creates `~/.termx/{sessions,approvals,diffs,commands}`,
and appends marked blocks (delimited by `# --- termx begin ---` /
`--- termx end ---`) to `~/.bashrc`, `~/.zshrc`, `~/.tmux.conf`, and
`~/.claude/settings.json`. `termx uninstall` reverses every change
cleanly; pass `--keep-data` to preserve `~/.termx/` contents.

## Architecture

```
+----------------+          SSH / mosh          +------------------+
|                |  <------------------------>  |                  |
|  termx (APK)   |    tail -F events.ndjson     |   your VPS       |
|  Kotlin +      |    read sessions/*.json      |                  |
|  Compose +     |    write commands/*.json     |   tmux -----+    |
|  sshj +        |                              |             |    |
|  mosh (NDK)    |                              |   claude ---+    |
|                |                              |                  |
+----------------+                              |   termx (Go)     |
                                                |   ~/.termx/      |
                                                +------------------+
```

No daemon, no extra ports. termxd is a short-lived CLI invoked by tmux
hooks, shell `preexec`/`precmd`, and Claude Code's `PreToolUse` /
`PostToolUse` hooks. Everything flows through the existing SSH channel.

See `docs/ROADMAP.md` for the full 8-phase plan and `docs/FDROID.md` for
reproducible-build notes.

## Pre-release smoke

Before cutting any release, maintainers walk `docs/PRE_RELEASE_CHECKLIST.md`
on the latest debug APK against a real VPS.

## License

MIT. See [`LICENSE`](LICENSE). Copyright (c) 2026 Marcos Kuchak.
