## termx privacy

termx is a local app. It does not talk to any service we (the maintainers)
run. There is no termx backend.

## What termx sends off-device

| Destination                                 | What                                                              | Why                                                                                                                                                     |
| ------------------------------------------- | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Your VPS (over SSH)                         | Your SSH commands and your PTY input                              | It's an SSH client. That's the whole point.                                                                                                             |
| GitHub API (`api.github.com`)               | Public API call for the latest `termxd-v*` release tag            | The Setup Wizard's companion-install step needs to know which `termx` binary to fetch. Anonymous HTTPS, no auth token, no account or device identifier. |
| Google Gemini API (`generativelanguage.googleapis.com`) | Audio clips recorded via push-to-talk + the transcription prompt | Only when you explicitly press and hold the PTT button. Uses **your** Gemini API key from the vault. Google's privacy policy applies to that request. Don't add an API key (or don't hold the button) to disable. |

**termx does not send anything anywhere else.** No Firebase / FCM, no
analytics, no crash reporting, no maintainer backend, no usage pings, no
telemetry of any kind.

## What stays on the phone

- **SSH private keys** — encrypted at rest under an Android Keystore
  AES-256-GCM key at `filesDir/vault.enc`, unlocked via biometric or
  device credential per operation.
- **Gemini API key** — same vault.
- **Server passwords** — in-memory only (`PasswordCache`). Never written
  to disk. Process kill or app relaunch clears the cache; you re-enter.
- **Servers, groups, themes, font size, tmux session names** — unencrypted
  Room / DataStore. (These are not secrets.)
- **`known_hosts`** — standard OpenSSH format at
  `ApplicationInfo.filesDir/known_hosts`, used for trust-on-first-use
  host-key pinning.

## What stays on your VPS

- `~/.termx/events.ndjson`, `~/.termx/sessions/`, `~/.termx/approvals/`,
  `~/.termx/diffs/`, `~/.termx/commands/` — the file-based protocol
  between termxd and the phone. All `0600`. Rotate or delete anytime.
  `termx uninstall` removes the whole tree unless you pass `--keep-data`.
- tmux + shell hooks injected into `~/.bashrc` / `~/.zshrc` /
  `~/.tmux.conf` — every block is wrapped with
  `# --- termx begin ---` / `# --- termx end ---` sentinels.
  `termx uninstall` strips them cleanly without touching anything you
  wrote by hand.
- Claude Code hook entries in `~/.claude/settings.json` — each tagged
  with `_termx_managed: true` so uninstall can remove only the entries
  termx wrote and leave your hand-authored hooks untouched.

## Data subject rights

Since termx collects and stores nothing on our end, there is nothing for
us to give you, export, or delete. Export and import your own config via
Settings → Config sync (encrypted JSON, passphrase-gated).

## Questions

Open an issue at <https://github.com/mkuchak/termx/issues>.
