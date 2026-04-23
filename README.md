# termx

Pre-alpha FOSS Android SSH / mosh / tmux client, designed primarily as a mobile
control surface for [Claude Code](https://claude.com/claude-code) running on a
VPS. Plain terminal use is supported, but the product is tuned for the
phone-driven Claude workflow: permission prompts, long-running task
notifications, voice-to-prompt via Gemini, native diff review for Claude's file
changes, and seamless session resume through mosh + tmux.

This is a monorepo:

- `android/` — Kotlin + Jetpack Compose client (this is what builds the APK)
- `termxd/` — Go VPS companion (coming soon — event stream, session registry,
  `PreToolUse` permission broker)
- `scripts/release/` — release automation (runs from your laptop, no JDK needed)
- `docs/` — architecture notes (placeholder)

No centralized infra. Fully open source, donations only, no paid tier. License:
MIT.
