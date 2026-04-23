# termxd

The VPS companion for the [termx](../README.md) Android app. A single Go
static binary that turns your server into a structured surface the phone
can read — session registry, event stream, and (in later phases)
permission broker for Claude Code.

## Install

Grab the latest release for your architecture:

```bash
# linux/amd64
curl -LO https://github.com/mkuchak/termx/releases/download/termxd-vX.Y.Z/termx-linux-amd64
chmod +x termx-linux-amd64
sudo mv termx-linux-amd64 /usr/local/bin/termx

# linux/arm64
curl -LO https://github.com/mkuchak/termx/releases/download/termxd-vX.Y.Z/termx-linux-arm64
chmod +x termx-linux-arm64
sudo mv termx-linux-arm64 /usr/local/bin/termx
```

Replace `vX.Y.Z` with the latest termxd tag from the
[releases page](https://github.com/mkuchak/termx/releases).

## Usage

```bash
termx --version
```

More subcommands (`install`, `uninstall`, tmux/shell hooks) land in Phase
4.2+ of the roadmap.

## Architecture

- **No daemon.** termxd is a short-lived CLI invoked by tmux hooks, shell
  preexec/precmd, and Claude Code's `PreToolUse`/`PostToolUse` hooks.
- **File-based protocol** under `~/.termx/`:
  - `sessions/<id>.json` — one file per tmux session (status, metadata)
  - `events.ndjson` — append-only event stream the phone `tail -F`s
  - `commands/<uuid>.json` — phone→VPS inbox
  - `approvals/<id>.json` — permission broker decisions
  - `diffs/<id>.json` — captured Write/Edit hook output
- **No extra ports.** Everything flows through the existing SSH/mosh
  channel. Nothing to firewall.
- **Single static binary.** No runtime deps; `CGO_ENABLED=0`.

## Build from source

```bash
make build          # -> dist/termx
make run            # builds + prints version
VERSION=0.1.0 make build
```

## Release

Maintainers: `scripts/release.sh 0.1.0` tags `termxd-v0.1.0` and pushes.
GitHub Actions (`.github/workflows/termxd-release.yml`) picks up the tag
and runs GoReleaser — cross-builds linux/amd64 + linux/arm64, uploads
binaries + checksums to the release.

## License

MIT — see [root LICENSE](../LICENSE) (when present). See the main
[termx README](../README.md) for the full project.
