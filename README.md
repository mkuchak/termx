## termx

[![android-release](https://github.com/mkuchak/termx/actions/workflows/android-release.yml/badge.svg)](https://github.com/mkuchak/termx/actions/workflows/android-release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> Your VPS in your pocket. A FOSS Android SSH / mosh terminal designed as a
> mobile control surface for Claude Code.

## Why termx

Claude Code is powerful but it lives on a VPS, which means the interesting
moments — a permission prompt, a file edit to review, a long build finally
finishing — all happen while you are somewhere else. termx connects your
phone to that VPS over SSH and gives you a clean plain-shell terminal, then
adds the Claude-Code-specific magic around it: a permission broker, a native
diff viewer, event-driven notifications, and push-to-talk. Plain shell use
works fine too; the Claude hooks just stay dormant.

termx is a plain-shell terminal — bring your own multiplexer. Session
persistence and multi-window layouts are your multiplexer's job: run tmux,
screen, Zellij, or nothing at all on the VPS and termx talks to whatever
shell you land in. The Claude control surface below works the same
regardless of which multiplexer (if any) you run.

## Features

- **SSH + mosh** — a plain interactive shell over SSH, with optional mosh so
  the connection roams across network flaps and IP changes. Persistence and
  multi-window are your own multiplexer's job (tmux, screen, Zellij, …) — the
  rest of termx works the same whichever you pick.
- **Permission broker** — Claude Code's `PreToolUse` hook pipes approvals
  to the phone; approve, deny, or approve-with-pattern from the lock
  screen. The app writes each decision straight to `~/.termx/approvals/`
  on the VPS for the hook to consume, and "Always approve" appends a rule
  to `~/.termx/allowlist.txt` so identical calls auto-approve silently.
- **Live diff viewer** — `PostToolUse` hooks capture `Write` / `Edit` /
  `NotebookEdit` output to `~/.termx/diffs/`; the phone renders them with
  +/- coloring, syntax highlighting, and a per-session "changed files"
  drawer.
- **Event notifications** — four separately-silenceable Android channels:
  permission / task / error / disconnect. Long-running command done? Ping.
  Claude idle? Ping. Build failed? Ping. Each has its own priority and
  vibration pattern.
- **Push-to-talk** — hold the mic key in the terminal's bottom bar, speak,
  release. Your audio goes to
  Google Gemini (`gemini-3.1-flash-lite`) using your own API key; the
  transcription is injected directly into the active PTY — no clipboard
  dance.
- **Key vault** — SSH private keys and the Gemini API key live in a single
  JSON blob in the app's private storage, protected by Android's per-app
  sandbox (only this app's UID can read it). Biometric / device-credential
  unlock gates access to the vault UI on launch and after an idle timeout.
  Earlier versions wrapped the blob in an Android Keystore AES-256-GCM key,
  but OEM Keymint/Keystore2 bugs threw an NPE on `Cipher.doFinal` on some
  devices, breaking the vault outright; for a single-user FOSS SSH client the
  sandbox boundary is the right trade. See `FileSystemSecretVault`'s KDoc for
  the full rationale.
- **Terminal polish** — ships the **Sorcerer** theme, a limited-palette dark
  scheme that drives the 16 ANSI colors plus fg/bg/cursor and the whole app
  UI from one source of truth (no theme picker or custom editor). Plus
  pinch-to-zoom font sizing, URL double-tap, scrollback, live thumbnails of
  active sessions on the home screen, and a scrollable extra-keys bar with
  sticky modifiers.
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
if missing, creates `~/.termx/{sessions,approvals,diffs,commands}`, and
appends marked blocks (delimited by `# --- termx begin ---` /
`--- termx end ---`) to `~/.bashrc`, `~/.zshrc`, and
`~/.claude/settings.json`. `termx uninstall` reverses every change
cleanly; pass `--keep-data` to preserve `~/.termx/` contents.

## Architecture

```
+----------------+          SSH / mosh          +------------------+
|                |  <------------------------>  |                  |
|  termx (APK)   |    tail -F events.ndjson     |   your VPS       |
|  Kotlin +      |    read sessions/*.json      |                  |
|  Compose +     |    write commands/*.json     |   your shell     |
|  sshj +        |                              |   + claude       |
|  mosh (NDK)    |                              |                  |
|                |                              |   termx (Go)     |
+----------------+                              |   ~/.termx/      |
                                                +------------------+
```

No daemon, no extra ports. termxd is a short-lived CLI invoked by shell
`preexec`/`precmd` and Claude Code's `PreToolUse` / `PostToolUse` hooks.
Everything flows through the existing SSH channel.

See `docs/ROADMAP.md` for the full 8-phase plan and `docs/FDROID.md` for
reproducible-build notes.

## Pre-release smoke

Before cutting any release, maintainers walk `docs/PRE_RELEASE_CHECKLIST.md`
on the latest debug APK against a real VPS.

## License

MIT. See [`LICENSE`](LICENSE). Copyright (c) 2026 Marcos Kuchak.
