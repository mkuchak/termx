# termx — Project Knowledge Base

> **Audience:** future maintainers, contributors, and AI agents working on this repository.
> **Produced by:** a full-source discovery audit (every first-party Kotlin/Go/config/doc file read).
> **Snapshot:** 2026-06-10 · `main` @ `278230c` (174 commits) · Android app **v1.6.0** · termxd **v0.1.5**
> **PLUS the post-v1.6.0 redesign wave, present as UNCOMMITTED work-tree changes** at update time:
> connection ownership moved to `ConnectionManager`, terminal-as-sheet, Moshi-style home, the broker
> return-path fix, mosh liveness gating, extra-keys/PTT bar redesign (§16 last row has the full list).
> **Staleness warning:** line numbers and version pins below rot over time — line numbers in sections
> NOT touched by the 2026-06-10 update pass predate the redesign and have likely rotted. Symbols and
> file paths are preferred as anchors; re-verify anything load-bearing before relying on it. The
> "Verified findings" section (§14) includes the exact commands used to verify each claim so you can
> re-run them.

---

## Table of contents

1. [What termx is](#1-what-termx-is)
2. [Repository layout](#2-repository-layout)
3. [System architecture: two halves, one file protocol](#3-system-architecture-two-halves-one-file-protocol)
4. [The `~/.termx/` wire contract (full spec)](#4-the-termx-wire-contract-full-spec)
5. [Android app deep dive (module by module)](#5-android-app-deep-dive)
6. [termxd (Go companion) deep dive](#6-termxd-go-companion-deep-dive)
7. [Connection lifecycle](#7-connection-lifecycle)
8. [Notification architecture (two tiers)](#8-notification-architecture-two-tiers)
9. [Security model — documented vs. real](#9-security-model--documented-vs-real)
10. [Theme system (Sorcerer)](#10-theme-system-sorcerer)
11. [Build, CI, release](#11-build-ci-release)
12. [Testing strategy](#12-testing-strategy)
13. [Engineering conventions & culture](#13-engineering-conventions--culture)
14. [VERIFIED FINDINGS — bugs, gaps, drift (read this first)](#14-verified-findings--bugs-gaps-drift)
15. [Dead / unreachable code registry](#15-dead--unreachable-code-registry)
16. [Project history & major decision timeline](#16-project-history--major-decision-timeline)
17. [Gotchas & load-bearing details (do-not-break list)](#17-gotchas--load-bearing-details)
18. [Deferred work / roadmap state](#18-deferred-work--roadmap-state)
19. [Quick reference: paths, constants, magic numbers](#19-quick-reference)
20. [Audit coverage notes](#20-audit-coverage-notes)

---

## 1. What termx is

termx is **two products sharing one file-based wire protocol**:

1. **An Android app** (`dev.kuch.termx`) — a Kotlin/Compose/Hilt SSH + mosh terminal whose
   differentiator is being a *mobile control surface for Claude Code running on a VPS*:
   permission broker, native diff viewer, event-driven notifications, push-to-talk (Gemini).
2. **A Go CLI** (`termxd/`, installed on the VPS as `termx`) — a short-lived hook binary invoked by
   shell preexec/precmd and Claude Code's PreToolUse/PostToolUse hooks, plus one long-running
   daemon (`termx watch-herdr`).

Product identity decisions (locked in an April 2026 design review, summarized in `docs/ROADMAP.md`):

- **Claude-Code-first, plain-terminal fallback.** Hooks stay dormant for non-Claude use.
- **Plain-shell terminal, bring-your-own-multiplexer.** tmux integration existed and was fully
  *removed* in v1.4.0. Session persistence is the user's tmux/zellij/screen's job. An optional
  per-server "startup command" replaces auto-attach.
- **No SaaS, no telemetry, no maintainer backend.** FCM is not used. Off-device traffic is
  exactly: the user's VPS (SSH), GitHub Releases API (anonymous), and Gemini (user's own key).
- **MIT, donations-only, F-Droid intended** (submission still pending; metadata is stale — see §14).
- Maintained by a single developer (Marcos Kuchak); companion project: `github.com/mkuchak/push-to-talk`
  (the Electron PTT app whose prompts/language catalogue termx mirrors).

A related external tool appears throughout: **herdr** — a Rust workspace/agent manager running on
the VPS. termxd's `watch-herdr` daemon turns herdr "agent finished" states into phone alerts. This
is, as of v1.5/1.6, the most actively developed and most load-bearing daily-use path.

---

## 2. Repository layout

```
termx/
├── android/                    # Gradle multi-module Android app (14 modules)
│   ├── app/                    # shell: MainActivity, NavHost, theme map, notifications, push, fg service
│   ├── core/common/            # VersionTag (shared SemVer-ish comparator)
│   ├── core/domain/            # Compose-free models, repo interfaces, Sorcerer palette, use-case contracts
│   ├── core/data/              # Room, DataStore, vault, session singletons, install/update repos
│   ├── feature/{terminal,servers,keys,ptt,settings,updater,onboarding}/
│   ├── libs/ssh-native/        # sshj wrapper + mosh binaries (jniLibs) + JNI pty + key crypto
│   ├── libs/companion/         # pure-Kotlin termxd protocol client (NDJSON events, approval decisions)
│   └── libs/terminal-view/     # vendored Termux terminal-emulator + terminal-view (Apache-2.0)
├── termxd/                     # Go module github.com/mkuchak/termx/termxd → binary `termx`
│   ├── cmd/                    # cobra commands: install, uninstall, watch-herdr, hidden hooks
│   ├── cmd/internal/           # paths, events (NDJSON append/rotate), registry, marked blocks,
│   │                           # os-release detection, claude settings.json surgery
│   ├── .goreleaser.yaml        # linux/amd64+arm64 static builds
│   └── CHANGELOG.md            # termxd release track (termxd-v* tags)
├── docs/                       # ROADMAP.md (living plan), FDROID.md, PRE_RELEASE_CHECKLIST.md
├── fastlane/metadata/          # F-Droid/Play listing tree (placeholders, stale — §14)
├── metadata/dev.kuch.termx.yml # F-Droid build-bot manifest (stale — §14)
├── .github/workflows/          # android-debug.yml, android-release.yml, termxd-release.yml
├── package.json                # version source of truth for the APK + release-it tooling
├── .release-it.json            # Android release config (tags v*)
├── .release-it.termxd.json     # termxd release config (tags termxd-v*)
├── CHANGELOG.md                # Android release track
├── PRIVACY.md                  # privacy/threat model doc (partially stale — §14)
└── README.md
```

Two **independent release tracks** live in this one repo:

| Track   | Tag pattern      | Version source            | CI workflow            | Artifacts |
|---------|------------------|---------------------------|------------------------|-----------|
| Android | `v*.*.*`         | `package.json` `version`  | `android-release.yml`  | signed APK on GitHub Release |
| termxd  | `termxd-v*.*.*`  | `termxd/package.json`     | `termxd-release.yml`   | `termx-linux-amd64`, `termx-linux-arm64`, `checksums.txt` |

---

## 3. System architecture: two halves, one file protocol

There is **no daemon listening on a port and no extra network protocol**. The phone talks to the
VPS exclusively over the SSH connection it already has, by reading and writing files under
`~/.termx/` on the VPS:

```
+----------------+          SSH / mosh           +--------------------------+
|  termx (APK)   | <--------------------------→  |  VPS                     |
|  Kotlin+Compose|   tail -F events.ndjson       |  your shell + claude     |
|  sshj          |   SFTP read sessions/, diffs/ |  termx (Go, short-lived) |
|  mosh (NDK)    |   SFTP write approvals/*.res, |  termx watch-herdr       |
|                |     allowlist, ntfy-endpoint  |  (systemd --user daemon) |
+----------------+                               |  ~/.termx/  (the bus)    |
                                                 +--------------------------+
```

Key design properties:

- **Events** flow VPS→phone as append-only NDJSON, tailed over an SSH exec channel
  (`tail -F --lines=0 ~/.termx/events.ndjson`, `EventStreamClient.TAIL_COMMAND`).
- **Phone→VPS writes** are one JSON file per permission decision
  (`approvals/<id>.res.json` via `EventStreamClient.respondToApproval`) plus whole-file republishes
  of `allowlist.txt`, all SFTP with atomic temp-write-then-rename (`SftpAtomicWrite.kt`) so the
  polling hook can never see a torn file. (The older `commands/<id>.json` channel survives as a
  dormant schema — §4.2.)
- **Append atomicity:** each Go-side event append is a single `O_APPEND` write under PIPE_BUF
  (4096 B on Linux), which POSIX guarantees atomic — the tail never sees a torn line
  (`termxd/cmd/internal/events.go`, KDoc on `AppendEvent`).
- **Rotation:** `events.ndjson` rename-rotates to `events.ndjson.1` at 5 MiB
  (`DefaultRotateBytes`), decided from the *just-written fd's* fstat (no stat/append TOCTOU).
  Rename (not truncate) is deliberate: `tail -F` reopens by name; truncate would desync offsets.
- The phone treats `~/.termx/` on the VPS as **the source of truth** for session/event state
  (a stated cross-cutting rule in `docs/ROADMAP.md`).

---

## 4. The `~/.termx/` wire contract (full spec)

All paths created by `termx install` with mode 0700 (dirs) / 0600 (files).
Canonical path resolution: `termxd/cmd/internal/paths.go` (Go) — the Kotlin side resolves `$HOME`
via `printf %s "$HOME"` over exec because sshj's SFTP does not expand `~`
(`EventStreamClient.resolveHomeDir`, duplicated in `ConnectionManager.resolveRemoteHome`).

| Path | Writer | Reader | Purpose |
|---|---|---|---|
| `~/.termx/events.ndjson` | termxd (hooks, watch-herdr) | phone (`tail -F` over exec) | the event bus |
| `~/.termx/events.ndjson.1` | termxd (rotation) | nobody (history) | single rotated copy |
| `~/.termx/sessions/<name>.json` | termxd | phone (SFTP) | session registry (tabs metadata) |
| `~/.termx/approvals/<id>.req.json` | termxd `_hook-pretooluse` | phone (optional drill-in) | rich permission request |
| `~/.termx/approvals/<id>.res.json` | phone (`EventStreamClient.respondToApproval`, atomic SFTP) | termxd hook (poll loop) | permission decision (`ApprovalResponse`, §4.2) — §14.1 RESOLVED |
| `~/.termx/diffs/<id>.json` | termxd `_hook-posttooluse` | phone (SFTP, DiffViewer) | captured file-edit diffs |
| `~/.termx/commands/<id>.json` | phone (`EventStreamClient.sendCommand`) | **nobody — dormant schema, deliberately retained** (§4.2) | phone→VPS commands (forward-compat) |
| `~/.termx/ntfy-endpoint` | phone (on every SSH connect) | termxd `watch-herdr` | UnifiedPush endpoint URL for Tier-2 pushes |
| `~/.termx/allowlist.txt` | user (hand-edit) + phone (`EventStreamClient.appendAllowlistRule` on "Always approve") | termxd `_hook-pretooluse` | regex-per-line auto-approve rules |
| `~/.termx/active/<ppid>.json` | termxd `_preexec` | termxd `_precmd` | transient per-shell command timing record |
| `~/.termx/termx-shell-hooks.sh` | termxd `install` (always overwritten) | sourced by rc files | the preexec/precmd shim |

### 4.1 Event schema (`TermxEvent` ⇄ Go `AppendEvent`)

One JSON object per line; top-level discriminator `type`; canonical fields `ts` (RFC3339),
`session` (string). Kotlin decode: `android/libs/companion/.../events/TermxEvent.kt` with
`ignoreUnknownKeys` + `classDiscriminator = "type"`; malformed/unknown lines become
`TermxEvent.Unknown` (never break the stream). Golden wire fixtures:
`android/libs/companion/src/test/resources/events-golden.ndjson`.

| `type` | Extra payload fields | Emitted by | Phone reaction |
|---|---|---|---|
| `session_created` | — | (legacy; no current Go emitter after tmux removal) | none |
| `session_closed` | — | (same) | `termx.disconnect` notification + Reconnect action |
| `shell_command_long` | `cmd`, `duration_ms`, `exit_code`, `pwd` | `_precmd` when duration > 10 s | `termx.task` if > user threshold (default 60 s) |
| `shell_command_error` | same | `_precmd` when exit ≠ 0 and duration > 2 s | `termx.error` |
| `permission_requested` | `request_id`, `tool_name`, `tool_args`, `cwd` | `_hook-pretooluse` | `termx.permission` heads-up w/ Approve/Deny + in-app dialog |
| `permission_resolved` | `request_id`, `decision` (`allow\|deny\|always`), `reason` | `_hook-pretooluse` (after poll) | removes pending dialog entry |
| `diff_created` | `diff_id`, `file_path`, `tool` | `_hook-posttooluse` | `termx.task` w/ Review action |
| `claude_idle` | — | (no current Go emitter; schema reserved) | `termx.task` "Claude finished" |
| `claude_working` | — | (same) | none |
| `agent_finished` | `source` ("herdr"), `agent`, `workspace` | `watch-herdr` | `termx.agent` channel + strong vibration (Tier 1), unless Tier 2 supersedes |

> Note: `session_created/closed` and `claude_idle/working` survive in both schemas but lost their
> Go emitters when tmux session tracking was removed (v1.4.0 / termxd commit `de1fa09`). The
> phone-side handlers still exist and work if a future emitter returns.

### 4.2 Phone→VPS decision schemas (`ApprovalResponse` live, `CompanionCommand` dormant)

**The live path (2026-06 broker fix; resolves §14.1):** `ApprovalResponse`
(`android/libs/companion/.../events/ApprovalResponse.kt`) — written by
`EventStreamClient.respondToApproval` to `approvals/<request_id>.res.json`, the exact file
`_hook-pretooluse` polls. Field names are locked to Go's `approvalResponse` struct
(`hook_pretooluse.go`): `{"decision","reason,omitempty"}`; `reason` mirrors `omitempty` via
kotlinx's default `encodeDefaults = false`. Go lowercases+trims and unblocks ONLY on
`"approve"`/`"allow"` — everything else (including `"always"`) is a deny. "Always approve"
therefore sends `allow` on the wire AND the PHONE persists the rule via
`EventStreamClient.appendAllowlistRule` (idempotent read-modify-write republish; pattern
`^\Q<tool>\E\|.*$` from `Regex.escape` — the `\Q..\E` dialect is RE2-compatible, proven on the Go
side by `hook_pretooluse_contract_test.go:TestAllowlistAcceptsKotlinEscapeDialect`). Byte-exact
golden fixtures: `android/libs/companion/src/test/resources/approvals-golden/` (4 files); the Go
contract test reads them directly across the repo boundary and skips on a standalone Go checkout.

**The dormant path:** `CompanionCommand` (same package). JSON with `type` discriminator,
`encodeDefaults = true`. Filename = `<id>.json` where `id` is a fresh UUIDv4.

| `type` | Fields | Intent |
|---|---|---|
| `approve_permission` | `id`, `request_id`, `remember` | resolve a pending PreToolUse block; `remember=true` should also persist an allow rule |
| `deny_permission` | `id`, `request_id`, `reason?` | counterpart |
| `update_allowlist` | `id`, `pattern` | append regex to `~/.termx/allowlist.txt` |

**⚠ Still unconsumed by termxd.** `sendCommand` and this schema are retained for a future
server-side consumer (decisions superseded, not erased) and no live broker path calls them; the
KDoc on both now states this truthfully instead of assuming a poller exists.

### 4.3 Session registry schema (`SessionState` ⇄ Go `internal.Session`)

`{name, created_at, windows, status, claude}` — `status` is a free string
(`idle|working|awaiting_permission` legend) deliberately not an enum on either side so the schema
can evolve. Phone reads via `EventStreamClient.loadSessionRegistry()`; per-file parse failures are
dropped (reported on the client's `errors` SharedFlow), never fail the whole listing.

### 4.4 Diff record schema (`DiffPayload` ⇄ Go `diffRecord`)

`{id, ts, session, file_path, tool, before, after, unified_diff}` — field names match exactly
between `termxd/cmd/hook_posttooluse.go:diffRecord` and
`feature/terminal/diff/DiffViewerState.kt:DiffPayload` (kotlinx decode with `ignoreUnknownKeys`).

---

## 5. Android app deep dive

### 5.0 Stack & toolchain

- Kotlin **2.2.10**, AGP **8.12.1**, KSP, Java 21 toolchain, compileSdk/targetSdk **35**, minSdk **28**.
- Compose BOM `2026.03.00`, Material 3, Navigation Compose, Hilt **2.58**.
- sshj **0.39.0** + BouncyCastle **1.78.1** (bcprov + bcpkix), OkHttp **4.12.0**,
  kotlinx-serialization, Room **2.7.2**, DataStore, biometric `1.2.0-alpha05`,
  `sh.calvin.reorderable` (catalog entry only since the home redesign — no module uses it),
  zxing-core (declared; QR for key share),
  UnifiedPush connector **3.3.2** (`org.unifiedpush.android:connector`, app module only).
- ABIs: `arm64-v8a` + `armeabi-v7a` only. `extractNativeLibs=true` (mosh runs as a child process).
- Version catalog: `android/gradle/libs.versions.toml` — the only place dependency versions live.

### 5.1 Module graph & layering rules

```
app ─→ feature:* ─→ core:data ─→ core:domain ─→ core:common
          │              │
          │              ├─→ libs:ssh-native (sshj hidden behind interfaces)
          │              └─→ libs:companion  (protocol client)
          └─→ libs:terminal-view (vendored Termux; only :feature:terminal & :app)
```

Hard rules observed throughout (break these and you'll fight the codebase):

- `:core:domain` is **Compose-free** (colors are ARGB `Long`s, not `androidx.compose.ui.graphics.Color`).
- **No `net.schmizz.*` (sshj) type leaks** outside `:libs:ssh-native` — the impl layer translates
  every sshj failure into the `SshException` sealed hierarchy (`impl/Exceptions.kt`).
- Features never depend on `:app`; `:app`-only side effects (notification channels, UnifiedPush,
  updater cards) are injected into feature screens as composable slots / callbacks
  (see `TermxNavHost.kt` — `updateBanner = { UpdateBanner() }` pattern, `NavGateViewModel` docs).
- `:feature:ptt` does not depend on `:feature:terminal` (would be a cycle): the terminal screen
  hands PTT a `(text, appendNewline) -> Unit` writer.

### 5.2 `:core:common`

One object: **`VersionTag`** — SemVer-ish comparator feeding *both* updaters (APK self-updater and
the VPS companion updater). Behavior contracts (all unit-tested):

- strips `v`/`V` prefix, drops `-rc1`/`+build` suffixes, implicit-zero padding (`1.2 == 1.2.0`);
- **unparseable input sorts as version zero** → a malformed `BuildConfig.VERSION_NAME` still
  triggers an update prompt ("over-prompt rather than silently never update");
- companion helpers parse the exact formats in play: installed = cobra's `termx version 0.1.4\n`,
  latest = tag `termxd-v0.1.4`. `companionInstalledVersion` returning `null` MUST be treated by
  callers as "offer (re)install", never "up to date".

### 5.3 `:core:domain`

- Models: `Server` (note: `passwordAlias` → vault key; `startupCommandEnabled`/`startupCommand`
  replaced the removed tmux fields), `KeyPair` (`keystoreAlias` is an opaque vault handle — the
  name predates the Keystore removal), `ServerGroup`, `AuthType` (KEY/PASSWORD),
  `KeyAlgorithm` (ED25519/RSA_4096), `PlannedAction` (loose dry-run row, forward-compatible
  via `extras: Map<String,String>`).
- `PttLanguage`: the 7-locale catalogue (`en-US, pt-BR, es-CO, es-ES, fr-FR, de-DE, hi-IN`)
  mirroring mkuchak/push-to-talk; `normalise()` collapses unknown codes to `en-US`.
- `theme/Sorcerer.kt` + `theme/TerminalTheme.kt` — see §10.
- `usecase/InstallCompanionUseCase` + `InstallStep3State` — the wizard Step-3 state machine
  (Detecting → FetchingRelease → ReadyToDownload → Downloading → PreviewingDiff → Installing →
  Success/Error/AlreadyInstalled). `AlreadyInstalled` optionally carries `updateUrl`/`latestTag`
  when the installed companion is outdated.

### 5.4 `:core:data`

**Room** (`termx.db`, schema **v5**, `exportSchema = true`, JSON under `core/data/schemas/`):

| Migration | What |
|---|---|
| 1→2 | added `custom_themes` (theme editor that never shipped) |
| 2→3 | added `servers.password_alias` |
| 3→4 | dropped `custom_themes` (never had data; Sorcerer-only decision) |
| 4→5 | recreate-table: drop `autoAttachTmux`/`tmuxSessionName`, add `startupCommandEnabled`/`startup_command`. Recreate because minSdk 28 = SQLite 3.22 (no `ALTER TABLE … DROP COLUMN`) |

Conventions: enums stored as `name()` strings (mapper-translated, **not** TypeConverters — keeps
the DB human-readable); FKs use `SET_NULL` on delete; `reorder()` runs in one transaction.
Migration tests use Robolectric + `MigrationTestHelper` with the schemas dir exposed as a test
asset root (see `core/data/build.gradle.kts` sourceSets block).

**The vault** (`vault/FileSystemSecretVault.kt`) — *read its KDoc; it is the project's best
threat-model document*:

- Plaintext JSON map at `filesDir/vault.json` (base64 values), atomic tmp-rename writes.
- Aliases: `key-<uuid>-private` (SSH private PEMs), `password-<uuid>` (SSH passwords),
  `gemini.api.key`.
- **Why no Android Keystore:** v1.1.0–v1.1.6 wrapped entries in AES-256-GCM under a Keystore
  master key; OEM Keymint/Keystore2 bugs (Google IssueTracker 151002502, 323093578, 176085956)
  threw NPE on `Cipher.doFinal` on real devices, bricking the vault. On software-backed Keystore
  devices the master key sat in the same UID sandbox anyway. v1.1.7 dropped to sandbox-as-boundary.
  First access deletes legacy `vault.enc` and the two legacy Keystore aliases
  (`dev.kuch.termx.vault-master`, `-v2`).
- **Lock semantics (subtle, deliberate):** `load()` requires `VaultLockState == Unlocked`
  (throws `VaultLockedException`); `store()`/`delete()` do **not** — refusing writes on a
  plaintext file protects nothing and silently dropped legitimate saves when the auto-lock timer
  fired mid-flow (documented inline). The biometric gate is a *UI policy gate*, not crypto.
- `VaultLifecycleObserver` re-locks after `autoLockMinutes` in background — default **1440 min**
  (was 5; bumped in v1.1.8 because app-switching tripped it and silently dropped saves).

**Preferences** — two DataStore files:

- `app_prefs` (`AppPreferences`): paranoidMode (declared, not yet wired to connect flow),
  autoLockMinutes, fontSizeSp (8–32, default 14), PTT source/target language + context,
  onboardingComplete, APK-updater last-check/skipped-version, companion-updater per-server
  skip tokens (`"$serverId@$tag"` strings) and last-check stamps (`"$serverId=$epochMs"` strings).
- `alert_prefs` (`AlertPreferences`): per-server mute sets (task/error/agent), long-command
  threshold (default 60 s), battery-opt prompt dismissed, agent-finished master switch /
  strong-vibration / bypass-DND, UnifiedPush enabled + endpoint.

**Session singletons** (pure-Kotlin, no Android types, all `@Singleton`):

- `SessionRegistry` — live tabs keyed `(serverId, tabName)`; consumed by the foreground service;
  carries the `disconnectAllRequest` SharedFlow (notification action →
  `ConnectionManager.disconnectAll`; the single collector lives in the manager's `init` so it
  works with zero screens alive).
- `EventStreamHub` — serverId → `{label, EventStreamClient}`. Producer: `ConnectionManager` on
  connect (was `TerminalViewModel` before the ownership refactor). Consumers:
  `EventNotificationRouter`, `PermissionBrokerViewModel`, `DiffViewerViewModel`,
  `ApprovalActionReceiver`. Each consumer calls `client.stream()` independently — **each collector
  = one remote `tail` process**; documented trade ("zero coordination complexity"). Now `open`
  (subclass-fake seam for the ConnectionManager test suite).
- `ReconnectBroker` — notification Reconnect action → `ConnectionManager` redials that server
  (collector also in the manager's `init`, not a ViewModel).
- `PasswordCache` — in-memory only, process-lifetime, `ConcurrentHashMap<UUID,String>`.

**Remote orchestration:**

- `InstallCompanionUseCaseImpl` — the wizard's 3-stage SSH installer. Per-stage idle timeouts
  (detect 15 s / preview 120 s / **install 600 s** — apt-get can sit silent for minutes); the
  detect probe is `command -v termx || [ -x $HOME/.local/bin/termx ]` because non-login sshj
  shells don't have `~/.local/bin` on PATH; download step auto-extracts tar.gz/zip and lands an
  executable at `/tmp/termx`; install runs `/tmp/termx install --install-deps 2>&1` streaming
  lines; post-install sanity = SFTP `exists(~/.termx/events.ndjson)` wrapped in a 15 s timeout
  (sshj blocking I/O doesn't honor coroutine cancellation). A terminal `.catch` converts any leak
  into `InstallStep3State.Error` (an uncaught throw here used to kill the Activity).
  **Every exec drains stdout and stderr concurrently** — sshj gives each stream its own flow-control
  window; reading only stdout can deadlock (load-bearing comment, copied to `CompanionUpdateRepository`).
- `CompanionUpdateRepository` — best-effort on-connect companion update offers (24 h per-server
  TTL also protecting GitHub's 60 req/h anonymous limit; per-(server, tag) skip memory;
  consent-first: only the user's Install tap writes to the VPS, reusing the wizard pipeline).
- `TermxReleaseFetcher` — pulls `repos/mkuchak/termx/releases?per_page=50` and picks the first
  `termxd-v*` tag (NOT `/releases/latest` — that returns whichever of the two tracks tagged last);
  `assetForArch` matches `amd64|x86_64` / `arm64|aarch64` substrings case-insensitively.

### 5.5 `:libs:ssh-native`

**Public surface** (everything else is `internal`): `SshClient.connect(target, auth, timeout)` →
`SshSession` → `openShell` (PTY, optional exec-with-PTY command) / `openExec` / `openSftp`;
`MoshClient.tryConnectDetailed(...)` → `MoshConnectResult` (Success(session) / Failed(reason)) with
`MoshFailureReason` sealed (MissingUtf8Locale / MoshServerMissing / HandshakeTimeout /
Other(detail)) in `MoshConnectResult.kt` — `tryConnect` survives as a lossy null-on-failure
delegator; `SshAuth` sealed (Password / PublicKey-PEM);
`SshException` sealed (AuthFailed / HostUnreachable / HostKeyMismatch / TimedOut / ChannelClosed /
Unknown); crypto helpers `SshKeyPairGenerator` (Ed25519 OpenSSH-PEM, RSA-4096 PKCS#1) and
`OpenSshKeyParser` (OpenSSH/PKCS#1/PKCS#8, encrypted-classic-PEM with passphrase; **encrypted
OpenSSH-binary-format keys are NOT supported** — clear error tells users to re-export).

Critical implementation facts:

- **BouncyCastle bootstrapping** (`SshClient` init): Android ships a stripped "BC" provider with
  no X25519/Ed25519; we `removeProvider("BC")` then `insertProviderAt(BouncyCastleProvider(), 1)`.
  Regression history: v0.3.1 shipped without this on a path and broke all connects
  (pre-release checklist items 1–2 exist because of it).
- **Host-key verification is `PromiscuousVerifier`** — i.e. *none*. `SshConnector.kt:37-40`,
  `TODO(Phase 2 — Task #21)`. See §14.2.
- **Keepalive 30 s** (`SshConnector.KEEPALIVE_INTERVAL_SECONDS`) — added v1.1.13 after
  backgrounded phones kept half-open sockets and keystrokes silently vanished.
- `SshSessionImpl` serializes channel-opens with a Mutex (sshj isn't thread-safe for concurrent
  opens on one transport) and tracks children for close-cascading.
- `PtyChannelImpl`/`MoshSessionImpl` output flows use **`send` (suspending), never `trySend`** —
  dropping frames corrupts full-screen TUI repaints (load-bearing comment in both).
- `ExecChannelImpl` resolves `exitCode` via a background `cmd.join()`.

**The mosh stack** (read `android/libs/ssh-native/BUILD.md` before touching):

- `jniLibs/<abi>/` holds **53 files per ABI**: `libmoshclient.so` (mosh-client 1.4.0 built via
  termux-packages with prefix rewritten to `dev.kuch.termx`) + its whole DT_NEEDED closure
  (protobuf, OpenSSL 3, ncurses, ~45 abseil libs), every SONAME patchelf-renamed to
  `lib<name>_mosh.so` so Android's APK extractor accepts them. ~+19 MB compressed APK cost.
  Reproduction script: `scripts/build-mosh-jni-bundle.sh`; verification one-liner in BUILD.md.
- **Why a JNI pty** (`cpp/pty_fork_exec.c`, `impl/NativePty.kt`): mosh-client calls
  `tcgetattr(STDIN)` at startup; ProcessBuilder pipes → ENOTTY → abort in ~150 ms. The C code does
  the classic `/dev/ptmx → grantpt → unlockpt → ptsname_r → fork → setsid → TIOCSCTTY → dup2 →
  execve` dance, closes fds 3..1024 in the child, and exposes `forkExec/setWindowSize/waitPid/sendSignal`.
  `TIOCSWINSZ` on the master auto-delivers SIGWINCH — no manual signaling.
- **Terminfo** (`impl/TerminfoInstaller.kt` + bundled assets): stock Android has no
  `/usr/share/terminfo`; without extracting `xterm-256color/xterm/vt100/dumb` (~10 KB) into
  `filesDir/terminfo`, ncurses' `setupterm()` fails and mosh-client dies silently in <100 ms.
- **Handshake** (`impl/MoshClientImpl.kt`): short-lived SSH session runs
  `LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i 0.0.0.0 -p 60000:60010`
  (+ ` -- <startupCommand>` verbatim when set — the *remote login shell* does the
  quoting/expansion; do not re-quote; the locale prefix is a POSIX env-assignment applying to
  mosh-server only and exists because `openExec` lands in a non-login shell that minimal images
  leave in C/POSIX, which mosh-server hard-refuses — `moshServerCommand` KDoc). stdout AND stderr
  are drained CONCURRENTLY (per-stream sshj flow-control windows; same deadlock as `execCapture`)
  while stdout is regex-scanned for `MOSH CONNECT (\d+) (\S+)`; then the SSH closes (mosh-server
  has double-forked) and local mosh-client spawns under the pty with env `MOSH_KEY`, `TERMINFO`,
  `LD_LIBRARY_PATH=<nativeLibDir>`. On failure the kept stderr transcript is classified by the
  pure `MoshClientImpl.classifyHandshakeFailure` into a `MoshFailureReason`
  (`MoshHandshakeClassifierTest`). Whole race capped at **8 s**
  (`ConnectionManager.MOSH_HANDSHAKE_TIMEOUT_MS` — the constant moved out of the VM) → fall back
  to sshj. A "successful" handshake is additionally gated on FIRST OUTPUT BYTES within **3 s**
  (`ConnectionManager.MOSH_FIRST_OUTPUT_TIMEOUT_MS`): the `MOSH CONNECT` line travels over
  TCP/SSH, so firewalled UDP used to produce a frozen terminal that claimed Connected — see §7.
- **Exit diagnostics** (`impl/MoshSessionImpl.kt`): first 1 KB of pty output captured; on a
  non-zero exit within 2 s, also greps `logcat -d` (linker/DEBUG/libc tags, pid-filtered — works
  without READ_LOGS for own UID) because bionic linker failures never reach the child's stderr.
  v1.1.16 addition; surfaced through `MoshDiagnostic` → human-readable error in the UI.
- **History note:** v1.1.21 replaced the native client with a pure-Kotlin SSP transport
  (`feat(mosh)!`), v1.1.23 **reverted** it back to the native client. Don't repeat that experiment
  casually; the commit pair is `3a04233` / `9b00fcb`.

### 5.6 `:libs:companion`

`EventStreamClient` (caller owns the `SshSession`; client never closes it; now `open` — the
broker tests subclass-fake it):

- `stream()` — cold flow; reopens the tail forever with **1 s backoff** via `retryWhen`
  (cause surfaced on `errors` SharedFlow); buffered **4096 slots, DROP_OLDEST** (≈1 MiB; prefer
  dropping old heartbeats over stalling the SSH producer).
- `respondToApproval(requestId, decision, reason?)` — THE live broker return path: writes
  `approvals/<requestId>.res.json` (`ApprovalResponse`, §4.2; trailing newline matching termxd's
  `writeJSONAtomic` convention) via `writeAtomic`. Atomicity is load-bearing: the hook stat+reads
  the canonical path on a 100 ms timer.
- `appendAllowlistRule(pattern)` — idempotent read-modify-write republish of `allowlist.txt`
  (missing file = empty; duplicate pattern = no-op). Best-effort BY CONTRACT: callers must not
  gate the decision write on it.
- `loadSessionRegistry()` / `loadDiff(id)` / `sendCommand(cmd)` — SFTP one-shots; `$HOME` cached
  once per client under a mutex (`resolveHomeDir`, `printf` not `echo` to avoid a baked-in
  newline). `sendCommand` is the dormant `commands/` channel (§4.2).
- All SFTP writes go via `SftpClient.writeAtomic(path, bytes)` (extension in
  `SftpAtomicWrite.kt`): unique temp sibling `<path>.tmp.<uuid>` then SFTP rename. Orphaned temps
  on rename failure are deliberately left.
- `EventParser` / `NdjsonBuffer` / `byteFlowToEvents` — line framing handles CRLF, partial chunks,
  and a final unterminated line (`flushRemaining`).
- `EventStreamClientFactory` — trivial factory because sessions are short-lived and Hilt has no
  matching scope; consumers hold the `@Singleton` factory and call `create(session)`.

### 5.7 `:libs:terminal-view` (vendored)

Fork of Termux's `terminal-emulator` + `terminal-view`, **pinned upstream commit
`5b657c6adf4304e5198951ce815fe0205dcac29c` (tag v0.118.3)** — recorded in `NOTICE`, full
Apache-2.0 text in `LICENSE.apache-2.0`, headers preserved on every `.java` file. Treat as
third-party: do not restyle; keep diffs against upstream minimal and deliberate.

Local additions living in the `com.termux.terminal` **package on purpose** (to reach the
package-private `mEmulator` field): `RemoteTerminalSession` (sshj transport adapter) and
`MoshRemoteTerminalSession` (mosh adapter). They are near-duplicates **by decision** — "a third
sibling triggers extraction of a base class" (Task #27 note in the KDoc). Both:
override `initializeEmulator` (no JNI subprocess, no threads), route emulator output bytes through
`write()` → `onInputBytes` callback, accept remote bytes via `feedRemoteBytes` (main-thread posted),
and signal `onRemoteSessionClosed`. The fork also adds `readStickyCtrl/readStickyAlt/
consumeStickyModifiers` to `TerminalViewClient` so the extra-keys bar's sticky modifiers apply to
IME-committed text (v1.1.14 fix), and `TerminalEmulator.isBracketedPasteMode()` (Task #53) — a
read-only DECSET-2004 accessor so `ConnectionManager.submitLine` can replicate `paste()`'s gating
while building its own atomic byte sequence (§5.11; KDoc on the accessor records why `paste()`'s
three separate writes can't be used). Main-thread only, like all emulator state.

Fonts: **JetBrains Mono NL** (no-ligatures) bundled — NL specifically so no layer can ever
substitute a multi-cell ligature glyph (`NOTICE` explains; `liga 0` font features alone are not
guaranteed).

### 5.8 `:feature:terminal` — the heart

**`connection/ConnectionManager.kt` (~1620 lines, `@Singleton`, 13 ctor deps)** owns every
transport — the long-promised "Server-ownership refactor" shipped here, moving everything
lifecycle-shaped out of `TerminalViewModel` (which dropped from ~970 lines to a ~260-line binder).
One connection = one `SshSession` (or one `MoshSession`) = one shell = one `SessionPty` holder
(invariant: exactly one of `pty`/`moshSession` non-null, `init { require(xor) }`). Key facts:

- **Scope lifetime = process.** Own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`,
  never cancelled in production (`internal` purely so tests can drain it). Connections outlive
  every screen/ViewModel; the `disconnectAllRequest` and `ReconnectBroker` collectors moved from
  VM `init` into the manager's `init` because they must work with zero screens alive.
- **API**: `connections: StateFlow<Map<UUID, TermxConnection>>`; `connect(serverId)` (null =
  test-server path) is **BIND-IF-ALIVE** — Connecting/AwaitingPassword/Connected slots are
  returned untouched (same emulator, scrollback intact); only Disconnected/Error dials fresh.
  Plus `submitPassword`, `cancelPasswordPrompt`, `clearError`, `writeToPty`, `submitLine`
  (two-phase PTT line submit — §5.11), `disconnect` (synchronous, idempotent; preserves an
  Error/AwaitingPassword state across the teardown), `disconnectAll`.
- **`TermxConnection`** is a LONG-LIVED per-server slot: `state:
  StateFlow<TransportState>` (Connecting / AwaitingPassword / Connected(session, moshBacked,
  transportFallbackReason) / Error / Disconnected) plus `writeErrors` and `transportNotices`
  one-slot DROP_OLDEST SharedFlows — stable references a screen or home card collects once.
- **Lifecycle semantics (the flip):** a session ENDS only on explicit Disconnect (terminal
  top-bar button), notification "Disconnect all", remote shell EOF, or a failed connect.
  Back/home/backgrounding/vault-relock NEVER touch transports (steady-state paths are
  vault-free). Process death is NOT survived (KDoc'd boundary); the 30 s sshj keepalive is the
  only Doze mitigation.
- **Threading:** the connect pipeline launches on `Dispatchers.Main.immediate` — the vendored
  Termux `TerminalSession` constructor binds a no-arg `Handler()` to the constructing thread's
  looper (TerminalSession.java), so `openTab`/`openMoshTab` must run on main. Byte pumps and PTY
  writes hop to `Dispatchers.IO`; `feedRemoteBytes` posts emulator mutation to main internally.
- `connect(serverId)` → `openSession`: resolve target+auth (vault key bytes, or password from
  vault→cache→prompt via `PasswordRequiredException` → AwaitingPassword) → mosh race (8 s,
  `tryConnectDetailed`) → **first-output liveness gate (3 s)** → on gate failure
  `teardownMoshShellForFallback` and continue down sshj IN THE SAME ATTEMPT → `openTab`/
  `openMoshTab` binds a `RemoteTerminalSession` and a byte-pump `outputJob`.
- **Truthful transport surfacing:** a mosh→SSH fallback sets
  `TransportState.Connected.transportFallbackReason` (persistent subtitle in the UI) and emits a
  one-shot `transportNotices` snackbar "Connected via SSH — mosh unavailable: <reason>". Reasons:
  "VPS missing UTF-8 locale" / "mosh-server not installed" / "handshake timeout" /
  `FALLBACK_REASON_NO_UDP` ("no UDP response — check firewall: allow 60000-60010/udp") /
  truncated stderr detail. The liveness-gate orphan trade is documented at
  `teardownMoshShellForFallback`: the spawned mosh-server is deliberately NOT pkill'd (could kill
  the user's other mosh sessions); stock mosh-server self-exits after ~60 s with no client.
- On connect it fires three detached, failure-swallowed jobs: `eventStreamHub.publish(...)`,
  `syncUnifiedPushEndpoint(session)` (writes `~/.termx/ntfy-endpoint`, mkdir -p first),
  `maybeOfferCompanionUpdate(...)`. None may ever block or fail the terminal.
- **Mosh side channel** (`startMoshSideChannel`): the mosh transport cannot carry exec/SFTP, and
  its bootstrap SSH is closed inside `MoshClientImpl` — so a *second, dedicated* SSH connection is
  opened (best-effort) purely for the event stream + endpoint sync + companion check. Started
  strictly AFTER the liveness gate passes (a fallback never tears down a half-started side
  channel); keeps a torn-down-while-connecting guard (per-server slot + live mosh shell check
  before publishing). **Known limitation (documented, deliberate):** this side SSH does not roam
  like mosh; after a network flap it can go quiet until the next reconnect. A supervised relaunch
  is a planned follow-up — do not "fix" casually.
- `submitPassword` persists the prompted password to the vault AND self-heals rows whose
  `passwordAlias` was nuked by an old save bug (mints `password-$serverId`, upserts the row);
  drops AwaitingPassword → Disconnected before redialing (bind-if-alive would otherwise return
  the prompt slot untouched).
- **Per-shell FIFO write path** (`ShellWriteQueue` + `PtyWriteStep(bytes, delayBeforeMs)`, Task
  #52): ALL input — `writeToPty` (extra-keys/Vol-Down/PTT) AND the emulator's `onInputBytes`
  (IME/hardware keys) — enqueues on ONE unbounded channel drained by a single `Dispatchers.IO`
  coroutine, so each shell has exactly one total write order BY CONSTRUCTION (previously one
  detached `launch` per write — no ordering guarantee). A multi-step sequence travels as ONE
  channel element, atomic against every other enqueue (the seam `submitLine` builds on). Scoped
  per SHELL, not per connection slot — a queue parked on the long-lived `TermxConnection` would
  land stale queued input on the NEXT shell after a reconnect; per-shell, writes aimed at a dead
  shell drop. Failures emit the slot's one-slot DROP_OLDEST `writeErrors` flow → snackbar with
  Reconnect (v1.1.13: silent keystroke loss fix; emulator-originated bytes now get the same
  surfacing instead of the old log-and-drop). Output byte pumps are untouched (§17 item 3).
- `cleanupQuietly` ordering matters: unpublish hub entry **before** closing the session so router
  collectors cancel first; covers the mosh side channel via the same registry key. **Shell EOF
  now runs the FULL teardown**: `onShellFinished` delegates to `cleanupQuietly` — the pre-refactor
  VM closed only pty/mosh on EOF, leaking `sshSession`+`sideSession`+a dangling hub entry per
  EOF→reconnect cycle (real bug, predates the refactor, found by the #44 regression suite;
  post-mortem inline at `onShellFinished`).
- Test-server fallback: `BuildConfig.TEST_SERVER_HOST/_USER/_PORT` env-injected at build +
  `assets/test-key.pem` (debug, gitignored); registry uses the all-zeros `FALLBACK_SERVER_ID` UUID.

**`TerminalViewModel` (~260 lines)** is now a pure binder: keyed `hiltViewModel(key =
"terminal-$serverId")` per server, no `SavedStateHandle` (the terminal is not a nav destination
anymore), no lifecycle jobs, empty-ish `onCleared` — it maps `TermxConnection.state` into
`TerminalUiState` (which gained `transportFallbackReason`) and forwards
connect/submitPassword/writeToPty/submitLine/disconnect to the manager.

**`TerminalSheetHost` + `connection/TerminalSheetState`** — the terminal is NOT a route (Task
#47; `terminal/{serverId}` is deleted). `TerminalSheetState` (`@Singleton`,
`maximizedServerId: StateFlow<UUID?>`) is the one bit of shared truth;
`TerminalSheetViewModel.open(serverId)` = `connectionManager.connect` (bind-if-alive) + maximize.
`TerminalSheetHost` is an `AnchoredDraggable` overlay (anchors Expanded/Hidden — NOT a
`ModalBottomSheet`): only the 32dp drag-handle strip carries the `anchoredDraggable` modifier so
terminal-body touch routing (selection, scrollback, gestures) is untouched; drag past 50% +
release settles Hidden = minimize; `BackHandler` = animate down + minimize. Mounted in
`TermxNavHost` as a root Box sibling ABOVE the NavHost, gated out entirely while the vault is
Locked (lock also clears `maximizedServerId` — minimize, never disconnect), auto-minimized on
navigation to any route except `servers` (`shouldAutoMinimizeSheet`, unit-tested).

**`TerminalScreen` (~1065 lines)** — single AndroidView-hosted `TerminalView` + `ExtraKeysBar` +
`PttSurface`, rendered inside the sheet. The old `DisposableEffect` disconnect-on-dispose is GONE
(minimize semantics); the only in-UI way to end a session is the explicit Disconnect
`IconButton` (`Icons.Filled.PowerSettingsNew`, top-end, shown while Connected). Reconnect lives
on the Error/Disconnected panes. Load-bearing mechanics:

- **The repaint chain**: remote bytes → emulator append → `notifyScreenUpdate()` →
  `SshSessionClient.onTextChanged` → (main-looper handler, NOT `View.post` — detached views drop
  runnables) → `terminalView.onScreenUpdated()` → invalidate. `bindViewToSession`/
  `unbindViewFromSession` manage the back-reference; without it output only repaints on
  app-foreground (the "background and foreground to see output" bug).
- Pinch-zoom (8–32 sp; persist once in `onScaleEnd`), double-tap URL → confirm dialog
  (`TerminalGestureHandler.extractUrlAt` synthesizes a MotionEvent to reuse Termux's cell math),
  touch listener feeds scale + double-tap detectors then ALWAYS falls through to Termux's
  `onTouchEvent` (consuming would starve selection/scrollback).
- **Volume-Down-as-Ctrl** (`handleVolDownAwareKey`): hold Vol-Down + key = Ctrl+key; a >500 ms
  lone hold passes through to the OS volume. Attached ONLY as the TerminalView's native
  `setOnKeyListener` (Tasks #49–51 — the old parallel Compose `onKeyEvent` path is GONE): its
  focusable Column was a focus thief that reclaimed view-focus from the TerminalView after every
  sheet open and card/dialog dismissal — keyboard visible, typing dead until a repair tap (the
  2026-06-11 tap-before-type bug). ONE focus source of truth: the view. WHY inline at
  `ConnectedPane`'s Column; regression-guarded by checklist item 11 (Vol-Down+C doubles as the
  focus canary, since the listener only fires while the view holds focus).
- **Sticky CTRL/ALT** (`ExtraKeysState`, tri-state Off/OneShot/Locked; double-tap inside 300 ms →
  Locked): state is hoisted to `ConnectedPane` and threaded into (a) the bar, (b) the
  Vol-Down/hardware-key path, (c) `MinimalTerminalViewClient.readStickyCtrl/Alt` for
  IME-committed letters. All three were separate v1.1.13/14 bugs.
- **`encodePttPayload`** + `ANY_LINE_BREAK` regex: collapses `\r \n NEL VT FF LSEP PSEP` runs to a
  single `\r` (the only byte readline+mosh agree means accept-line). Empirically derived
  (`PttPayloadProbeTest`); v1.3.3. **NO LONGER the live Send path** (Task #53): PTT Send goes
  through `ConnectionManager.submitLine` (§5.11) because appending the CR to the SAME buffer is
  exactly what broke Claude Code submits. Superseded-not-erased: the function and its tests stay
  as the pinned reference for the collapse `sanitizePtySubmitText` shares.
- `ExtraKeyBytes`: Ctrl+letter → 0x01..0x1A, Ctrl+specials per POSIX, Alt → ESC prefix; arrows/
  F-keys/Home/End/PgUp/PgDn delegate to Termux's `KeyHandler.getCode` (cursor-app mode passed as
  false — toolbar has no emulator state; documented acceptable).
- **`ExtraKeysBar` is a single scrollable row** (Task #37 — replaced the old two-page
  `HorizontalPager`+dots): one `horizontalScroll` Row of 16 keys (`ExtraKeysLayout.KEYS`:
  `ESC TAB CTRL ALT ↑ ↓ ← → HOME END PGUP PGDN | ~ \ /`), fixed 52×40 dp keys, 24 dp
  direction-aware fading edges (a `BlendMode.DstIn` punch-through — offscreen compositing
  required), with the keyboard chip + PTT mic pinned in a non-scrolling trailing Row.
  Sticky tri-state behavior and all three sticky-consumer paths unchanged. User-editable layouts
  remain designed-for but not shipped.
- **Repeat-key gestures fire on RELEASE, never mid-scroll** (`KeyButton`, 2026-06-15 / v1.7.2):
  the arrow + nav keys (↑↓←→ HOME END PGUP PGDN) sit inside the `horizontalScroll` Row, so they
  run through `detectTapGestures` — `onTap` fires on release; `onLongPress` (at the platform
  long-press timeout) fires once then repeats every `LONG_PRESS_REPEAT_MS` (60 ms) until lift;
  `onPress`'s `tryAwaitRelease` cancels the repeat job. detectTapGestures YIELDS its tap/long-press
  detection the instant the scroller consumes the drag, so dragging the bar scrolls it and fires
  ZERO keys — that consumption-based cancellation IS the disambiguation (don't re-implement slop).
  The pre-fix raw `awaitEachGesture` fired on touch-DOWN and ignored consumption, so every
  drag-to-scroll leaked a keystroke and a slow drag auto-repeated it (the 2026-06-15 drag-fires-keys
  bug — gotcha #28); don't regress to fire-on-down. Non-repeat keys (ESC TAB CTRL ALT | ~ \ /) keep
  `Modifier.clickable`, which already fires on release and yields to the scroller. `MicKey` and the
  keyboard chip are pinned OUTSIDE the scroll Row and keep their own raw await loops (§5.11/§17 #2).
- Keyboard chip: tap toggles IME (reads real IME inset, not `isFocused` — that latched), long-press
  opens the PTT compose card (`PttViewModel.composeText`, no-op unless Idle). Uses
  `detectTapGestures` because `combinedClickable.onClick` failed on a real device (cause unknown,
  documented).
- **PTT mic is a bar key** (`MicKey`, same 52×40 dp; Task #39 — the floating FAB is gone). The
  bar takes `onPttPressStart`/`onPttRelease`/`onPttCancel` + a `pttRecording` tint flag as plain
  lambdas (`:feature:terminal` ↛ `:feature:ptt` stays acyclic); `rememberPttStartAction` in
  `:feature:ptt` is the explicit Idle gate that replaced the old structural FAB gating. Both
  hard-won gesture laws are ported and re-documented inline — see §5.11/§17.

**Thumbnails** (`thumbnail/TerminalThumbnailRenderer.kt`, Task #45): `render(emulator, w, h):
Bitmap` — offscreen paint of the live `TerminalEmulator` via the vendored `TerminalRenderer`,
fit-scaled using `com.termux.view/TerminalRendererMetrics.kt` (new file in the `com.termux.view`
package ON PURPOSE to reach the package-private `mFontLineSpacingAndAscent` — same trick as
`RemoteTerminalSession`). `thumbnails(provider, w, h, periodMs = 1000): Flow<Bitmap>` polls at
~1 Hz with every render hopped to `Dispatchers.Main.immediate` — **the emulator buffer is
main-thread-confined** (`feedRemoteBytes` posts all writes to main; rendering off-main races the
byte pump). Robolectric `@GraphicsMode(NATIVE)` tests pixel-check it.

**Diff viewer** (`diff/`): `DiffViewerViewModel` reads SavedStateHandle `diffId` + `serverId`,
fetches via the hub's client (`loadDiff`), decodes `DiffPayload`. Renderer is a deliberate MVP:
+/- row coloring from Sorcerer constants, synthetic gutter line numbers, tiny hunk parser — no
syntax highlighting (ROADMAP defers a tokenizer). Route exists (`diff/{diffId}/{serverId}`) and
its "Open in terminal" footer now pops back to `servers` FIRST and then maximizes the sheet (the
sheet canonically lives over home, not over a stale diff) — but the notification→diff deep link
is still unwired; see §14.4 (partially resolved).

**Permission broker UI** (`permission/`): `PermissionBrokerViewModel` watches the hub, collects
each server's stream, maintains `pendingRequests` (dedup by `request_id`; drops entries when a
server unpublishes); `approve`/`deny`/`alwaysApprove` optimistically remove locally and call
`client.respondToApproval(...)` (`allow`/`deny` on the wire — §4.2). `alwaysApprove` additionally
calls `appendAllowlistRule(allowlistPatternFor(tool))` → `^\Q<tool>\E\|.*$`, best-effort after
the decision write. `PermissionDialogHost` is mounted as the LAST root sibling in `TermxNavHost`
(above the terminal sheet) so the dialog follows the user across screens and over a maximized
terminal; no scrim-dismiss (must choose a button). **The round-trip is live end-to-end as of the
2026-06 broker fix — §14.1 RESOLVED.**

### 5.9 `:feature:servers`

- **The home screen is Moshi-style now (Task #46).** `ServerListScreen` = top bar with ONLY
  VpnKey (keys) + Settings gear, an `activeSessions` composable slot (the app injects
  `ActiveSessionsRail` — same slot-injection seam as `updateBanner`, keeping `:feature:servers`
  off `:feature:terminal`), then a flat "SAVED CONNECTIONS" list. Reorder mode, move-to-group and
  collapse are DELETED (`ServerGroupHeader.kt` removed; `sh.calvin.reorderable` dropped from
  `:feature:servers`; the VM methods went with them — the Room group model and
  `ServerRepository.reorder` are intentionally untouched). Groups render as plain, non-collapsible
  uppercase section labels. Kept: swipe-delete with 5 s undo (deleted row held in memory, delete
  already committed — overwrite semantics if a second swipe lands), update banners, add-server
  FAB, empty state, duplicate-server.
- **`ActiveSessionsRail`** (`feature/terminal/connection/ActiveSessionsRail.kt` +
  `ActiveSessionCardModel.kt`): horizontal rail of 180 dp cards with 110 dp live thumbnails
  (~1 Hz, collected under `repeatOnLifecycle(RESUMED)`), the server label, a LIVE transport badge
  (`TransportState.Connected.moshBacked` — the actual transport, not `Server.useMosh` intent),
  and a per-card disconnect. `activeSessionCardModels` is a pure mapper: ONLY Connected slots
  produce cards (Connecting/Error/Disconnected don't). Tapping a card maximizes the sheet.
- **`AddEditServerViewModel`**: live test-connection (10 s cap, `SshException` → human strings);
  password persisted to vault under `password-$id` + seeded into `PasswordCache`.
  **Load-bearing save rule:** a *blank* password field on a PASSWORD-auth edit **preserves** the
  vault entry (users edit unrelated fields; the old clear-on-blank behavior caused
  re-prompt-on-every-cold-start). Only flipping auth → KEY scrubs the alias. Same logic duplicated
  in the wizard's `save()`. Key-auth "Test connection" in *this sheet* still returns a
  deferred-feature message (the wizard's test does support key auth).
- **`SetupWizardViewModel`** — 5 steps: ① connection fields (+ inline Ed25519 generation so
  first-run users never leave the wizard — deliberate, vs. a navigation round-trip), ② test
  (must succeed to advance; full key-auth support here), ③ companion install (drives
  `InstallCompanionUseCase`; on step entry the draft row is persisted FIRST and **serialized
  with** the detect call — a previous racy version produced one-shot "Server not found" errors),
  ④ review/save (password-auth finishes here), ⑤ public-key share (key-auth only).
  Abandoning the wizard after step 2 leaves the Room row — accepted trade-off, documented.

### 5.10 `:feature:keys`

Generate (Ed25519 default / RSA-4096 on `Dispatchers.Default`), import via SAF (`OpenSshKeyParser`,
interactive passphrase retry driven by `needsPassphrasePrompt` string-matching on the error),
detail (fingerprint `SHA256:` per `ssh-keygen -lf` convention, share/QR), list with
"used by N servers" and **reassign-then-delete** flow (point dependent servers at another key
before removing). Private bytes are `fill(0)`-wiped immediately after the vault store, everywhere.

`unlock/BiometricUnlockViewModel`: BIOMETRIC_STRONG **or** DEVICE_CREDENTIAL; pure app-level gate
(no `CryptoObject` — nothing to bind post-Keystore-removal); user-cancel ≠ failure;
`MainActivity` extends `FragmentActivity` solely because androidx.biometric requires it.

### 5.11 `:feature:ptt`

- **`AudioRecorder`**: MediaRecorder, MP4/AAC, 16 kHz mono, 64 kbps; amplitude polled every 50 ms
  into a 96-sample ring → waveform.
- **`GeminiClient`**: raw OkHttp (own client — 30 s timeouts; the shared NetworkModule client's
  10 s budget is for small JSON), endpoint
  `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent`
  (**GA model — bumped 2026-05-25 after the `-preview` 404'd**; parallel fix in push-to-talk
  upstream), `inline_data` audio/mp4 base64, temperature 0. Two prompt builders mirroring
  push-to-talk: transcribe (source==target) vs. translate (explicit "treat the entire input as
  <source>" guard); optional user "domain context" appendix; **`NO_SPEECH` sentinel** instruction —
  Gemini fabricates plausible transcripts for silence, and the sentinel lets the VM show "No speech
  detected" instead of injecting hallucinated text into the PTY (v1.1.9). Retry: 3 attempts,
  500/1200 ms ±20% jitter, on 429/5xx/IOException/body-regex `overloaded|unavailable|try again|fetch failed`
  (Gemini sometimes returns 200 + JSON error envelope).
- **The two prompts are a PORT of push-to-talk** (`github.com/mkuchak/push-to-talk`,
  `src/main/services/gemini.ts` → `buildPrompt`) and must be re-synced when it evolves — a
  provenance comment now sits above `buildTranscribePrompt` (the drift below happened precisely
  because nothing recorded the lineage). The original termx fork had silently DROPPED two
  load-bearing sentences, re-imported 2026-06-15 (v1.7.2): (a) the numerals rule *"Always use
  numerals instead of words spelled out."* — the transcript feeds shell commands / Claude Code, so
  "3000" must not arrive as "three thousand"; (b) the double-attention language/country clamp
  (*"…double attention(!): you must respect the selected language, both the language and the country
  of origin — <locale>."*) — stops the output dialect drifting off the selected locale; transcribe
  clamps the single locale, **translate clamps the TARGET** (not the source). The upstream pins both
  with E2E tests against real audio on the same `gemini-3.1-flash-lite` model (word→digit; en-US vs
  en-GB spelling), so they are proven, not speculative. **termx-ONLY** additions the reference lacks,
  which MUST survive every re-sync: the trailing `NO_SPEECH` guard (v1.1.9) and the `generationConfig`
  temperature 0.0 / topP 1.0 determinism. Unit tests (`GeminiClientTest`) pin sentence PRESENCE only;
  the behavioral proof lives in the upstream E2E suite — termx has no audio-fixture harness (§17 #29).
- **`PttViewModel`**: Idle → Recording → Transcribing(attempt) → Ready(editable draft) →
  consumeSend. **1500 ms minimum duration** before spending an API call (was 250 ms; sub-second
  recordings are gesture races or accidental taps and produce hallucination-bait room tone).
  `composeText()` reuses the Ready card as an empty type-a-command field (requestFocus=true pops
  the IME — the post-Gemini path deliberately doesn't). Gemini transcripts are `trim()`ed before
  Ready (Task #53) so the card shows exactly what Send will submit;
  `ConnectionManager.sanitizePtySubmitText` re-trims edited drafts as the defensive half.
- **`PttSurface.kt`** (renamed from `PttFab.kt`; Tasks #38/#39) — the floating FAB is deleted;
  the hold-to-record trigger is now `MicKey` inside the extra-keys bar's pinned trailing Row
  (§5.8), driven through `rememberPttStartAction` (the explicit `PttState.Idle` gate that
  replaced structural FAB gating). `PttSurface` keeps the status/Ready overlay cards. The Ready
  card is an outlined pill (28 dp radius, 2 dp primary border, `BasicTextField`, ✕ dismiss,
  48 dp circular primary send) — the separate "Insert without newline" button is REMOVED; the
  card only ever submits, and the `appendNewline` flag left `onSend(text)`'s signature with its
  last `false` caller (Task #53 — the Enter now lives in the submit sequence, not the payload;
  the seam feeds `TerminalViewModel.submitLine`). Two hard-won gesture rules carried over from
  the FAB era — read the
  `MicKey` comments before touching them: (1) the key must stay COMPOSED through `Recording`
  (an AnimatedVisibility exit detaches the LayoutNode mid-hold, cancelling the `pointerInput`
  coroutine at `waitForUpOrCancellation` → stuck-Recording or phantom "too short" errors; the
  bar satisfies this structurally since the mic is always in it); (2) the first pointer down is
  `consume()`d so the Compose interop dispatches ACTION_CANCEL to anything underneath instead
  of letting the gesture be reclaimed mid-hold. The `CancellationException` catch →
  `cancelRecording` is defense-in-depth, not the mechanism. Unlike the arrow keys, the mic must
  NOT auto-repeat — it has a raw `awaitEachGesture` loop, not `KeyButton`'s repeat machinery.

**Driving Claude Code's composer — the submit contract (Task #53).** PTT Send is a TWO-PHASE
submit: `TerminalViewModel.submitLine` → `ConnectionManager.submitLine(serverId, text)`
sanitizes the draft (`sanitizePtySubmitText`: trim → v1.3.3 `ANY_LINE_BREAK` collapse → ESC/C1
strip — `paste()`-parity, collapse FIRST so NEL survives as `\r` instead of dying in the C1
strip), wraps it in `ESC[200~`/`ESC[201~` ONLY when the emulator reports DECSET 2004 on, and
enqueues ONE atomic `ShellWriteQueue` element (§5.8): [payload bytes] then [lone CR `0x0D` with
`delayBeforeMs` = `SSH_SUBMIT_CR_DELAY_MS` 75 ms / `MOSH_SUBMIT_CR_DELAY_MS` 300 ms]. Blank
draft → bare CR, no delay. Never `0x0A`. The paste bit comes from the **vendored accessor
`TerminalEmulator.isBracketedPasteMode()`** (termx fork addition — `paste()` itself is unusable
here: its up-to-3 separate `mSession.write()` calls can't be atomic with the CR on the queue),
read inside a `Main.immediate` launch (emulator state is main-confined, §17 item 22); a null
emulator (no attached view yet) conservatively reads as "no bracketed paste". The facts this
encodes — verified 2026-06-11 by de-minifying the Claude Code v2.1.172 binary plus a
community-tool survey; full WHY lives on `buildSubmitSequence`:

- (a) Claude Code's stdin tokenizer classifies a control char as a `return` (submit) keypress
  ONLY when the chunk arriving in one `read()` is <64 chars; a `\r` embedded in a ≥64-char chunk
  is inserted as a literal composer newline — never submits. Version-dependent implementation
  detail (v2.1.172); PTT transcripts are almost always ≥64 bytes, hence "fails most of the time
  but short test phrases work".
- (b) `\r` = submit; `\n` = Ctrl+J = insert-newline BY DESIGN — never submit with LF (also the
  v1.1.12 raw-mode-over-mosh lesson).
- (c) The stable contract is Anthropic's own injector recipe (their remote-session daemon):
  bracketed-paste-wrapped text, then a LONE `\r` as a separate write after a delay — 10 ms on
  their local pty; termx sizes it per transport: 75 ms ssh, 300 ms mosh, because mosh coalesces
  input into state-sync frames (SEND_INTERVAL_MAX 250 ms + ~8 ms collection) and a faster CR
  re-merges with the text into one server-side `read()`.
- (d) Reference: anthropics/claude-code#15553; claude-squad, omnara and herdr all converge on
  the same text-then-separate-delayed-CR shape.
- (e) SECOND layer of one user symptom: v1.3.3 (`638104a`) fixed layer 1 (exotic Unicode line
  breaks); this fixes layer 2 (the chunk-size heuristic). Superseded-not-erased:
  `encodePttPayload` + its tests remain the pinned collapse reference (§5.8).

No blind retry in v1 — it would double-submit the common case; the verify-and-retry / Escape+CR
variant (autocomplete eating the CR) is documented in-code as a future option (§18). Do NOT
collapse the two writes back into one — §17 item 27.

### 5.12 `:feature:settings`

`SettingsViewModel` — font size, Gemini key presence/save/clear (key lives in the vault, only a
boolean reaches the UI), PTT languages + context, agent-alert toggles, UnifiedPush master switch.
Note the `combine` arity workaround: flows are pre-collapsed into `Triple`/holder classes to stay
within the typed 5-arg overload. `:app`-only side effects (DND channel rebuild + policy-access
launch, distributor picker) come in through screen slots from `NavGateViewModel`.

`configsync/` — `ConfigCrypto` (PBKDF2-HMAC-SHA256 100k iters → AES-256-GCM, versioned `TRMX1\0`
header), `ConfigExport` (bundle schema v1; private keys deliberately excluded), `ConfigImport`
(label-based conflict detection; KeepExisting/Overwrite/KeepBothRename). **Complete, tested, and
entirely unwired — see §15.1.**

### 5.13 `:feature:updater` (APK self-update)

`UpdaterRepository` (singleton state for banner + settings card): cold-start check gated by 24 h
DataStore TTL; **F-Droid installs self-disable** (`UpdateInstallerSource.detect` — installer
package in `{org.fdroid.fdroid, org.fdroid.basic, com.aurora.adroid}`; null installer = sideload =
updater active); auto-download on Wi-Fi only on the cold-start path (manual "Check now" never
auto-downloads); cached-APK reuse; skip-version memory. `UpdateChecker` hits `releases/latest`
(correct here — drafts/prereleases excluded by spec, and the `termxd-v*` tags are not "releases/latest"
material since both tracks publish releases… *note:* this works because GitHub's `latest` ignores
prereleases but NOT other tags' releases; in practice the newest stable of either track wins —
the APK asset filter `.endsWith(".apk")` is what makes a termxd-latest response collapse to
`Error("no .apk asset")` rather than a wrong offer). Install hands the cached APK to the system
installer via FileProvider (`REQUEST_INSTALL_PACKAGES`).

### 5.14 `:feature:onboarding`

3-screen first-run (welcome / permissions explainer / get-started actions that deep-link to the
wizard or biometric setup). Only real state: flips `AppPreferences.onboardingComplete`. The
NavHost's gate defaults to `true` on first emission **deliberately** so existing installs upgrading
to the onboarding build don't get bounced through it (DataStore overrides to false on genuinely
fresh installs before composition settles).

### 5.15 `:app`

- **`MainActivity`**: FragmentActivity (biometric), edge-to-edge
  (`setDecorFitsSystemWindows(false)` — without it `imePadding()` resolves to 0). Now reads
  `EventNotificationRouter.EXTRA_SERVER_ID` in `onCreate` (only when `savedInstanceState ==
  null` — a rotation must not re-maximize a sheet the user minimized) and `onNewIntent` (warm
  SINGLE_TOP delivery) → `connectionManager.connect` (bind-if-alive) + `terminalSheetState
  .maximize` — the connect-then-maximize notification path (§14.4, serverId half resolved).
- **`TermxApplication.onCreate` order matters**: `ThemeBinder.installAsDefault(Sorcerer)` FIRST
  (before any emulator exists — §10), then lifecycle observers (vault auto-lock, foreground
  tracker), `NotificationChannels.ensureAll()` (channels must exist before any post),
  `SessionServiceLauncher.start()`, `updaterRepository.checkOnLaunch`,
  `unifiedPushManager.ensureRegisteredIfEnabled()` (distributors only stay subscribed while asked).
- **`TermxNavHost`** routes: `onboarding`, `servers` (home), `setup-wizard`,
  `keys`, `keys/generate`, `keys/import`, `keys/{id}`, `settings`, `unlock`,
  `diff/{diffId}/{serverId}`. **`terminal/{serverId}` is GONE** (Task #47): the structure is
  `Box { NavHost(...); if (lockState != Locked) TerminalSheetHost(); PermissionDialogHost() }` —
  sheet above every route, dialog above the sheet. A destination-changed listener minimizes the
  sheet on any route except `servers` (`shouldAutoMinimizeSheet`). Vault lock-state transitions
  imperatively navigate to/from `unlock` with full back-stack pops AND clear the maximized sheet
  (minimize — the connection itself stays alive in `ConnectionManager`).
- **`TermxForegroundService`** (`dataSync` type, id 42): started when `SessionRegistry` goes
  0→≥1 (via `SessionServiceLauncher`); `startForeground` within `onCreate` immediately (Android
  12+ 5 s rule); self-stops when the registry empties; hosts `EventNotificationRouter.start()`
  scoped to the service. Persistent low-importance notification: "N tabs on M servers" +
  Open / Disconnect-all actions. Still does NOT own the SSH transport — that is now
  `ConnectionManager` (the "Server-ownership refactor" SHIPPED; the transport no longer dies with
  a ViewModel, but process death is still not survived — KDoc'd boundary on the manager).
- **`EventNotificationRouter`**: per-server stream collectors keyed off the hub; event→channel
  mapping table in §4.1; mute prefs read **at emit time** so toggles apply immediately; stacking
  notification ids for task/error/disconnect (`serverId:bucket`.hashCode), per-event ids for
  permission/diff. Every open-app PendingIntent now carries
  `EXTRA_SERVER_ID = "TERMX_SERVER_ID"` + `FLAG_ACTIVITY_SINGLE_TOP` (Task #47) — same for
  Tier-1 `AgentAlertPoster` alerts; Tier-2 `postRaw` pushes have no server context to attach.
- **Notification channels** (`NotificationChannels`, grouped under "termx"):
  `termx.permission` (HIGH, sound TYPE_NOTIFICATION, pattern 0/120/60/120),
  `termx.task` (DEFAULT, silent), `termx.error` (HIGH, **alarm** sound, 0/200/80/200/80/200),
  `termx.disconnect` (HIGH, **ringtone** sound, 0/400/100/80),
  `termx.agent` (HIGH, sound, channel vibration OFF — vibration fired from code as a controlled
  max-amplitude 3×1 s waveform; DND-bypass flips require delete+recreate of the channel and
  Notification Policy Access), plus `termx.service` (LOW, created lazily by the builder).
- **UnifiedPush (Tier 2)**: `TermxPushService` (connector 3.x `PushService`, manifest action
  `org.unifiedpush.android.connector.PUSH_EVENT`) persists endpoints / posts pushes through the
  shared `AgentAlertPoster`; `UnifiedPushManager` owns register/unregister/distributor pick
  (3.x API only — `register/unregister/getDistributors/getAckDistributor/saveDistributor/
  tryUseCurrentOrDefaultDistributor`; the 2.x names are gone, don't reintroduce).
- **Receivers**: `ApprovalActionReceiver` (Approve/Deny notification actions →
  `client.respondToApproval` with `allow`/`deny` — deny carries reason "Denied from
  notification"; fire-and-forget without `goAsync`, then cancel the notification),
  `ReconnectActionReceiver` (→ `ReconnectBroker` → `ConnectionManager`).

---

## 6. termxd (Go companion) deep dive

Go **1.22**, cobra, google/uuid — that's the whole dependency tree. Built `CGO_ENABLED=0`,
`-trimpath`, `-s -w -X main.version=`. Cold-start speed matters: the shell hooks exec this binary
around *every* command.

### 6.1 CLI surface

```
termx install [--dry-run] [--install-deps] [--with-ntfy]
termx uninstall [--yes] [--keep-data]
termx watch-herdr
termx --version                      # "termx version X.Y.Z" (cobra template — parsed by the app!)
# hidden (invoked by rc blocks / Claude settings, not humans):
termx _preexec <base64-cmd>
termx _precmd <exit-code>
termx _hook-pretooluse               # stdin: Claude PreToolUse JSON
termx _hook-posttooluse              # stdin: Claude PostToolUse JSON
```

`Execute()` runs under a SIGINT/SIGTERM-cancelled context (matters only for watch-herdr).

### 6.2 Shell hooks (`hooks.go`)

- `_preexec` receives the command **base64-encoded** (quoting safety), correlates by
  `os.Getppid()` (the interactive shell is always the parent), writes
  `~/.termx/active/<ppid>.json` atomically with cmd/start/pwd (`/proc/<pid>/cwd`).
- `_precmd` reads+deletes that record, computes duration, emits `shell_command_long` (>10 s) or
  `shell_command_error` (exit≠0 and >2 s) — otherwise silence. Missing record (first prompt,
  Ctrl-C on empty prompt) is a clean no-op.
- Session naming: tmux session name if `tmux display-message -p '#S'` answers, else
  `plain-shell-<ppid>` (stable pseudo-tab for plain shells).
- **The injected shim** (`install.go:shellHooksScript`) is full of hard-won shell lore:
  background calls use `( cmd & )` subshell wrappers so the interactive shell never prints
  `[1] Done`; bash's DEBUG trap is **not** installed at source time (it would fire for every
  remaining .bashrc statement) — a one-shot `PROMPT_COMMAND` entry arms it at the first prompt
  then removes itself; recursion guards prevent the precmd path re-triggering preexec; zsh uses
  `add-zsh-hook`. The script file is **always overwritten** on install (it's an install artifact;
  preserving user edits would block shipping fixes).

### 6.3 `_hook-pretooluse` — the permission broker (server half)

1. Reads Claude's PreToolUse JSON from stdin. **Malformed input fails OPEN** (let Claude's own
   permission system handle it; never wedge the model on schema drift).
2. **Fast path**: `Read, Grep, Glob, LS, WebFetch, WebSearch` auto-approve without touching disk.
3. **Allowlist**: `~/.termx/allowlist.txt`, one Go regex per line (`#` comments), matched against
   `<tool_name>|<command-or-file_path>`. Match = silent approve (no events — by design, no
   "approved: npm test" spam on the phone).
4. Otherwise: write `approvals/<uuid>.req.json`, append `permission_requested`, then **poll
   `approvals/<uuid>.res.json` every 100 ms for up to 30 s** (`pretoolusePollTimeout`).
   Timeout ⇒ **default-deny**. Decision ⇒ append `permission_resolved`, clean up files.
5. Deny path = exit code 2 + reason on stderr (Claude's hook protocol: "block and show stderr to
   the model"); approve = exit 0.

### 6.4 `_hook-posttooluse` — diff capture

Only for `Edit, Write, MultiEdit, NotebookEdit`; always exits 0 (purely observational). Before/after
reconstruction is best-effort: Edit reads the post-write file and reverse-substitutes
`new_string→old_string` (single replacement — Claude enforces old_string uniqueness); MultiEdit
walks edits in reverse; Write's "before" is empty-file in the common case. `unifiedDiff` is a
deliberate non-GNU minimal differ: common prefix/suffix trim + 3 context lines + one hunk header.
Writes `diffs/<uuid>.json`, appends `diff_created`.

### 6.5 `install` / `uninstall` — reversibility as a feature

Install phases: distro detect (`/etc/os-release`, `OSRELEASE` env override for tests; ID + ID_LIKE;
ubuntu/debian/alpine/arch/fedora/centos/rhel) → dependency check (`mosh-server→mosh`,
`node→nodejs`, `npm`, `claude` via `npm i -g @anthropic-ai/claude-code`; refuses to proceed on
missing system packages unless `--install-deps`) → **mosh preflight** (new, print-only, never
privileged: `locale charmap` must report UTF-8 — mosh-server hard-refuses otherwise, with a
distro-aware fix hint; then ufw active+rule-missing → exact remedy
`sudo ufw allow 60000:60010/udp`, ufw absent/inactive/unreadable → generic provider-firewall
note. `moshUDPRange` const cross-references the Kotlin `portRange` default; **excluded from
`--dry-run`**, which returns before it, so the JSON wire contract with the wizard is untouched;
injected-seam tests in `install_preflight_test.go`) → `~/.termx` tree + hooks script → self-copy to
`~/.local/bin/termx` (byte-compare skip) → **systemd user unit** `termx-herdr-watch.service`
(best-effort: `loginctl enable-linger`, `daemon-reload`, `enable --now`; degrades to printed
manual instructions without systemd --user) → rc-file marked blocks → stale `~/.tmux.conf` block
removal (legacy cleanup) → `~/.claude/settings.json` hook upsert → optional `--with-ntfy`
(self-hosted ntfy: loopback listener `127.0.0.1:2586`, anonymous write-only `up*` topics, TLS is
always a printed manual recipe — Android rejects self-signed certs).

**The two reversibility mechanisms — never change their sentinels:**

- Marked blocks: `# --- termx begin ---` / `# --- termx end ---` (`internal/markedBlocks.go`);
  upsert replaces in place, remove collapses the blank-line seam. Uninstall matches verbatim.
- Claude settings: every termx-written hook entry carries `"_termx_managed": true`
  (`internal/settingsJson.go`); upsert drops+re-appends only managed entries (matcher `.*`,
  commands `$HOME/.local/bin/termx _hook-pretooluse|_hook-posttooluse`); uninstall strips only
  managed entries, preserving user-authored hooks.

`--dry-run` emits the JSON `{"changes":[{type,path,mode,diff,note}]}` report the wizard renders
(`DryRunParser` on the Kotlin side is forgiving: unknown fields → `PlannedAction.extras`).

Uninstall: removes blocks (bashrc/zshrc/tmux.conf), stops/disables/removes the systemd unit,
strips managed hooks, deletes `~/.termx` (unless `--keep-data`), prints the `rm ~/.local/bin/termx`
one-liner instead of self-deleting (portability), and leaves any self-hosted ntfy server in place
on purpose.

### 6.6 `watch-herdr` — the daemon

The first (and only) long-running termxd command; designed for systemd --user supervision. Read
the 40-line header comment in `watch_herdr.go` — it documents *why the herdr CLI, not the socket*
(herdr 0.6.8's socket `events.subscribe` demands concrete pane_ids; no wildcard agent-done stream
exists; `herdr wait agent-status <pane> --status …` is the supported surface).

Core machine (`watchState` — split out precisely so tests can drive it synchronously):

- snapshot panes via `herdr agent list` (JSON) every 5 s and on every waiter return;
- one `herdr wait agent-status <pane> --status idle` goroutine per non-finished pane
  (60 s per-wait cap so waiters recycle);
- **finish = `idle` OR `done`** (`isFinishedStatus`) — herdr collapses done→idle when an attached
  UI already "saw" the finish; treating only `done` as finish silently dropped focused-tab
  finishes (fixed v0.1.5, `b9e55e6`);
- **edge-triggering** via `lastStatus` map + `finishedMark` sentinel: emit only on the transition
  INTO finished; the done↔idle ack transition and waiter/snapshot races never double-fire; a pane
  that resumes work re-arms;
- **`prime()` vs `reconcile()`**: first snapshot seeds state WITHOUT emitting (a daemon restart
  must not re-alert every pane resting idle — accepted trade: finishes during daemon downtime are
  lost);
- on finish: Tier 1 `agent_finished` append (field names `agent/workspace/source` are a hard
  contract with Kotlin's `TermxEvent.AgentFinished`) + Tier 2 ntfy POST to the endpoint file
  (re-read per finish so the phone can re-sync without daemon restart; `sanitizeEndpoint` accepts
  only http(s), first line);
- backoff 500 ms → 10 s (×2, no jitter — single daemon) while herdr is away;
- **`herdrBin()` PATH resolution**: `$HERDR_BIN` → `exec.LookPath` → `~/.local/bin/herdr` →
  literal `herdr`. Exists because **systemd --user sanitizes PATH** (omits `~/.local/bin`) — this
  broke the daemon in v0.1.3, fixed v0.1.4 (`a08323d`), and the unit file now also bakes
  `Environment=PATH=%h/.local/bin:…`. (This exact gotcha is recorded in the maintainer's memory
  notes too — it WILL bite again on any new daemon.)

---

## 7. Connection lifecycle

```
ENTRY (any of):  server row tap / active-session card tap / notification tap
                 (EXTRA_SERVER_ID) / DiffViewer "Open in terminal" / ReconnectBroker
  → TerminalSheetViewModel.open(serverId)   [or MainActivity.maximizeSessionFromIntent]
    → ConnectionManager.connect(serverId)   ← BIND-IF-ALIVE: Connecting/AwaitingPassword/
      (launched on Dispatchers.Main.immediate)  Connected slots returned UNTOUCHED, no redial
    → resolveConnection: Room row → auth
         KEY:      vault.load(keyPair.keystoreAlias)  (VaultLocked → user-facing error)
         PASSWORD: vault → PasswordCache → PasswordRequiredException → prompt dialog
                   (submitPassword persists + self-heals alias, retries connect)
    → server.useMosh?  moshClient.tryConnectDetailed (8 s cap)
         ├─ handshake OK → openMoshTab → LIVENESS GATE: first output bytes ≤ 3 s?
         │     ├─ yes: Connected(moshBacked=true); ONLY NOW startMoshSideChannel()
         │     │       opens a SECOND ssh for events/endpoint/companion-check
         │     └─ no ("no UDP response"): teardownMoshShellForFallback → fall through ↓
         └─ Failed(reason): remember human-readable reason → fall through ↓
    → sshClient.connect (sshj, keepalive 30 s, PromiscuousVerifier ⚠)
    → eventStreamHub.publish(serverId, label, clientFor(session))
    → [detached] syncUnifiedPushEndpoint, maybeOfferCompanionUpdate, touchLastConnected
    → openShell(80×24, optional startup command wrapped `${SHELL:-/bin/sh} -lc '… || exec $SHELL -l'`)
    → Connected(moshBacked=false, transportFallbackReason?) + one-shot transportNotices snackbar
    → SessionRegistry.register → SessionServiceLauncher sees 0→1 → TermxForegroundService starts
       → EventNotificationRouter.start collects every hub client's stream
  → TerminalSheetState.maximize(serverId) → TerminalSheetHost slides up over the current route

MINIMIZE (back press / drag handle down / nav to non-servers route / vault lock)
  → sheet hides; NOTHING touches the transport. The connection, registry entry,
    foreground service and event tails all stay up. Home shows a live 1 Hz card.

END OF SESSION — only four ways:
  explicit Disconnect button / notification "Disconnect all" / remote shell EOF / failed connect
  → cleanupQuietly: cancel pump → close pty/mosh → hub.unpublish (BEFORE session.close)
    → close main + side sessions → SessionRegistry.unregister → registry empty → service stopSelf
  (shell EOF runs this SAME full path via onShellFinished — the partial-teardown
   leak of the VM era is fixed; §5.8)
```

`buildStartupCommand` (`StartupCommand.kt`) is shared verbatim by both transports: login-shell
wrapper for PATH (`herdr`/`tmux` live in `~/.local/bin`/`~/.cargo/bin`), POSIX single-quote
escaping, `|| exec $SHELL -l` so a typo'd command drops to a login shell instead of a silent
disconnect; clean exit still ends the session.

---

## 8. Notification architecture (two tiers)

- **Tier 1 (in-connection):** foreground service + per-server `events.ndjson` tails. Works only
  while a session is up (or its mosh side channel is). Source: `EventNotificationRouter`.
- **Tier 2 (UnifiedPush):** survives swipe-kill/reboot/no-session. Phone registers with a
  UnifiedPush distributor (typically the ntfy app) → endpoint synced to VPS
  (`~/.termx/ntfy-endpoint`) on every SSH connect → `watch-herdr` POSTs agent-finish bodies →
  distributor wakes `TermxPushService` → same `AgentAlertPoster` as Tier 1. Currently
  **agent-finished only**; opt-in; self-hostable ntfy via `install --with-ntfy`.
- **Supersede rule** (v1.6.0 refinement, `tier2GenuinelyDeliverable`): Tier 1 agent alerts are
  suppressed ONLY when pref enabled AND endpoint persisted AND an acked distributor is installed
  *right now* — strict AND of positive signals; any unknown → Tier 1 fires. Rationale: a user who
  flips the push pref without finishing setup must not get total silence. The in-app alert is the
  floor; correctly-configured users get exactly one alert. Unit-tested
  (`EventNotificationRouterSupersedeTest`).
- `AgentAlertPoster` is shared by both tiers; strong vibration is a code-fired max-amplitude
  waveform (channel vibration disabled to avoid double-buzz), API 33+ tags
  `USAGE_NOTIFICATION` so DND policy/bypass applies to the vibration too; devices without
  amplitude control fall back to a timings-only waveform.

---

## 9. Security model — documented vs. real

What's true:

- Secrets at rest: sandbox-protected plaintext `vault.json` (deliberate, deeply documented — §5.4).
  Biometric/device-credential gates the UI; auto-lock after 24 h background.
- Passwords: vault (persisted) + in-memory cache; key material wiped post-store.
- VPS files all 0600/0700; approvals/diffs unreadable by other users on shared boxes.
- PTT audio goes only to Gemini with the user's own key; no analytics/telemetry anywhere (verified:
  the only off-device HTTP surfaces are GitHub releases, Gemini, and the user's own ntfy endpoint).
- Config export crypto (PBKDF2/AES-GCM) is sound — but unreachable (§15.1).

What's NOT true (despite docs) — details in §14: host keys unverified (§14.2), PRIVACY.md vault
claim stale (§14.3). (The permission round-trip — §14.1 — is RESOLVED as of the 2026-06 broker
fix and now genuinely works end-to-end.)

Trust boundaries to keep in mind: the phone fully trusts the VPS (it renders whatever events/diffs
the VPS writes); anyone with user-level access to the VPS can approve Claude actions by writing
`.res.json` files or editing the allowlist — consistent with "your VPS is yours", but worth
restating in any future threat-model doc.

---

## 10. Theme system (Sorcerer)

Single shipped theme since v1.3.0 (picker + editor removed; `custom_themes` table dropped in
schema v4). **One source of truth**: `core/domain/theme/Sorcerer.kt` — raw ARGB longs feeding:

1. the 16-ANSI + fg/bg/cursor terminal palette (`Sorcerer.terminalTheme`), and
2. the full Material 3 ColorScheme (`app/SorcererTheme.kt:sorcererColorScheme`), and
3. diff-viewer +/- colors (`ADDED_*`, `REMOVED_*`, `HUNK_BG`).

Design facts that look like bugs but aren't:

- **red == magenta == error == primary == `#FF006A`** — faithful to the upstream Sorcerer
  "limited palette"; destructive UI looks like primary actions ON PURPOSE (KDoc says where to
  change it if ever desired).
- The surface ramp goes **darker** than background ("details: darker" inversion of M3's tonal
  elevation); `surfaceTint = Transparent` to kill M3's pink elevation wash; the full
  `surfaceContainer*` ramp is specified because `Card` defaults to `surfaceContainerHigh` and the
  missing-token fallback is stock M3 gray (the v1.3.0 "gray PTT card" leak).
- **The terminal palette is installed via `ThemeBinder.installAsDefault` from
  `Application.onCreate`** — it mutates Termux's static `TerminalColors.COLOR_SCHEME.mDefaultColors`
  so (a) every new emulator is born themed and (b) in-band reset escapes (RIS, DECSTR, OSC
  104/110/111/112) restore Sorcerer, not xterm defaults. **Do not** try to apply the theme from
  `AndroidView { factory/update }` — `mEmulator` is null at composition time and the apply
  silently no-ops (the v1.3.0/v1.3.1 bug; the KDoc notes every Compose-based Termux fork hits this).

---

## 11. Build, CI, release

### Versioning

- APK `versionName` = `package.json` `"version"` (parsed in `app/build.gradle.kts`).
- APK `versionCode` = `git rev-list --count HEAD` (monotonic; falls back to 1 on shallow clones —
  fine for debug, would be wrong for release on a shallow checkout; release CI uses
  `fetch-depth: 0`).
- termxd version = `-ldflags -X main.version` from GoReleaser's `{{.Version}}`.

### Release flows (both driven by release-it + conventional commits)

- `npm run release:android` → bumps `package.json`, CHANGELOG.md, tags `vX.Y.Z`, pushes →
  `android-release.yml`: JDK 21, unit tests, `assembleRelease` signed from
  `ANDROID_KEYSTORE_BASE64/_PASSWORD/_ALIAS/_KEY_PASSWORD` secrets (keystore materialized to a
  temp file), GitHub Release with `termx-v*-release.apk`. The before:init hook prompts for the
  pre-release checklist walk (skipped non-interactively).
- `npm run release:termxd` → (runs in `termxd/`, config `../.release-it.termxd.json`) tags
  `termxd-vX.Y.Z` → `termxd-release.yml`: **creates a local unpushed `vX.Y.Z` tag** because
  GoReleaser demands a semver tag on HEAD and `termxd-v*` doesn't parse — then GoReleaser builds
  (`release.disable: true`; the workflow's own `gh release create` publishes under the real
  `termxd-v` tag, which is what `TermxReleaseFetcher` filters on). Binary staging uses `find`
  because GoReleaser's dist subdir naming (`_v1` amd64 suffix etc.) drifts between versions.
- **Known operational gotcha** (from maintainer memory): `release-it --dry-run` dirties
  package.json/CHANGELOG without committing — restore before the real `--ci` run.
- Commit discipline: `feat:` → minor, `fix:` → patch; `chore/docs/refactor/test/ci/build/style`
  hidden from changelogs.

### CI (debug)

`android-debug.yml` on main pushes + PRs touching `android/**`: `lintDebug testDebugUnitTest
assembleDebug`, debug APK artifact kept 30 days (the pre-release checklist installs this artifact).

### F-Droid

`docs/FDROID.md` documents the intended flow (fdroiddata MR, reproducible builds, `Builds:` recipe
in `metadata/dev.kuch.termx.yml`). Reproducibility hinges on: stable versionCode-per-commit,
checked-in Gradle wrapper, and the mosh prebuilts being reproducibly rebuildable (BUILD.md).
**Submission has not happened and the metadata is stale — §14.5.**

---

## 12. Testing strategy

~9k lines of tests. The philosophy is **dependency seams, not mocking frameworks** (mockk is
declared but barely used):

- Go: every filesystem/clock/exec dependency has an injected variant
  (`AppendEventAtCap(path, …, now, capBytes)`, `runPreexec(b64, ppid, now)`,
  `OSRELEASE` env override, `lister/waiter/poster` function fields on `herdrWatcher`,
  `watchState` extracted so the edge-trigger logic runs synchronously in tests).
- Kotlin: `open` classes for subclass-fakes (`SshClient`, `TermxReleaseFetcher`, `MoshClient`),
  `internal` parsers tested without I/O (`UpdateChecker.parse`, `GeminiClient.buildPrompt/
  extractTranscript`, `VersionTag`), fake repos per feature module (`fakes/FakeRepositories.kt`
  duplicated rather than shared — accepted duplication).
- Notable suites: `GoldenFileTest` against `events-golden.ndjson` (wire-format lock);
  `MigrationTest` (Robolectric + Room schema assets); `PttPayloadProbeTest` (the empirical
  line-break-leak probe that produced the `ANY_LINE_BREAK` regex); `MoshServerCommandTest`
  (verbatim startup-command passthrough + locale prefix); in-memory Apache MINA sshd for
  `SshClientTest`; `EventNotificationRouterSupersedeTest` (Tier-2 supersede truth table);
  `InstallCompanionUseCaseImplTest` with `@KnownHostsPath` string injection (the qualifier exists
  largely for this).
- New with the redesign wave: `ConnectionManagerBehaviorTest` (8 tests — the lifecycle-flip
  regression suite that caught the EOF partial-teardown leak) + `ConnectionManagerTest` (6) +
  `TerminalViewModelMoshFallbackTest`; `MoshHandshakeClassifierTest` (pure stderr classifier);
  `EventStreamClientApprovalTest` writing the byte-exact `approvals-golden/` fixtures that the Go
  side's `hook_pretooluse_contract_test.go` parses/round-trips directly (cross-language schema
  lock; the Go test `t.Skip`s when the Android tree is absent); `install_preflight_test.go`
  (injected charmap/ufw seams); `TerminalThumbnailRendererTest` (Robolectric
  `@GraphicsMode(NATIVE)` — real Bitmap/Canvas, pinned pre-4.14 where NATIVE isn't yet default);
  `TerminalSheetStateTest` / `TerminalSheetAutoMinimizeTest`; `PermissionBrokerViewModelTest`.
- **`fakes/QuiescentMainDispatcherRule`** is THE Main-dispatcher rule for anything constructing a
  `ConnectionManager`: a looper-backed hand-rolled Handler dispatcher (deliberately NOT
  `asCoroutineDispatcher()` — the stock HandlerDispatcher implements `Delay` via `postDelayed`,
  which Robolectric's mocked SystemClock never fires, hanging the 3 s liveness timeout) plus
  cancel-AND-JOIN of manager scopes BEFORE `resetMain()` — the fix for a cross-class
  "Dispatchers.Main is used concurrently with setting it" flake. Read its 60-line header before
  writing a manager test.
- **Convention deviation, recorded honestly:** `ActiveSessionsMappingTest` uses `mockk` for
  `TerminalSession` (the vendored class is concrete with looper-bound construction; a seam would
  mean forking it further). First real mockk usage in the repo — keep it contained.
- New with the input wave (Tasks #49–53): `TerminalViewFocusContractTest` (the single-source
  focus model), `ConnectionManagerWriteQueueTest` (FIFO ordering, submit atomicity against
  concurrent bursts, measured transport CR delays, live DECSET-2004 detection on the real
  emulator) and `SubmitLineSequenceTest` (pure `buildSubmitSequence`/`sanitizePtySubmitText`
  table tests).
- The former blind spot — no coverage of the broker phone→VPS round-trip — is closed: golden
  fixtures + Go contract tests above, plus `docs/PRE_RELEASE_CHECKLIST.md` item 10 (manual
  round-trip; keyboard focus is item 11, PTT submit item 12, extra-keys bar gestures item 13, the
  biometric item moved to 14).

---

## 13. Engineering conventions & culture

If you contribute here, match these or your change will read as foreign:

1. **Comments are institutional memory.** Every non-obvious decision carries a WHY, usually with
   the version/issue that motivated it ("Issue 2A, v1.1.13"). Bug fixes land with the post-mortem
   inline at the fix site. When you fix something subtle, write the story down next to the code.
2. **KDoc/doc-comments on every public type**, often with state-machine diagrams in ASCII.
3. **Best-effort means best-effort.** Side-band features (endpoint sync, companion offers,
   diagnostics, ntfy) are detached coroutines/guarded calls that swallow failures to logs and may
   NEVER block or break the core path (terminal, install). This is enforced rhetoric throughout —
   keep it.
4. **Forgiving decoders everywhere** (`ignoreUnknownKeys`, Unknown-event buckets, extras maps):
   either side can ship schema additions first.
5. **Decisions are superseded, not erased**: ROADMAP keeps strikethrough/historical blocks;
   deprecated Go helpers (`RotateIfNeeded`) stay with deprecation notes while their tests live.
6. **One source of truth per concern**: versions in package.json, colors in Sorcerer.kt,
   thresholds as named constants with rationale.
7. Conventional commits, `fix(scope):`/`feat(scope):`, breaking marked `!` (used once: the mosh
   SSP experiment).
8. Release cadence: rapid same-day patch trains on one subsystem until it's right
   (mosh v1.1.18→23, theme v1.3.0→2), then move on.

---

## 14. VERIFIED FINDINGS — bugs, gaps, drift

> Each finding includes the verification used. Re-run before acting; this snapshot is `278230c`.

### 14.1 ~~❗ The permission broker's return path does not exist (flagship feature inoperative)~~ — **RESOLVED 2026-06-10** (Tasks #40/#41, uncommitted wave)

> **Resolution:** option (b) below was implemented — the phone now writes
> `approvals/<id>.res.json` directly over SFTP (`EventStreamClient.respondToApproval`, schema
> `ApprovalResponse` locked to Go's `approvalResponse`; §4.2). "Always approve" sends `allow` and
> the PHONE appends `^\Q<tool>\E\|.*$` to `allowlist.txt` (`appendAllowlistRule`).
> `PermissionBrokerViewModel` + `ApprovalActionReceiver` are rewired; `sendCommand`/
> `CompanionCommand` remain as a dormant schema with truth-passed KDoc; the `commands/` dir is
> still mkdir'd and still unconsumed. Cross-language lock: `approvals-golden/` fixtures + Go
> contract tests; checklist gained item 10 (broker round-trip). The `remember=true`
> settings.json persistence mentioned below is STILL unimplemented — allowlist append is the
> shipping equivalent. Original finding kept for history:

- VPS hook blocks on `~/.termx/approvals/<id>.res.json` and **default-denies after 30 s**
  (`termxd/cmd/hook_pretooluse.go:31` timeout, `:171-172` poll target).
- Phone Approve/Deny (dialog `PermissionBrokerViewModel.kt`, notification
  `ApprovalActionReceiver.kt`) writes `CompanionCommand` JSON to `~/.termx/commands/<id>.json`
  (`EventStreamClient.kt:188`).
- **No code consumes the commands directory.** In termxd, `CommandsDir` appears only in
  `paths.go` and `install.go` mkdir calls. No poller exists, and `git log -S` shows none ever did;
  no Kotlin code ever wrote `.res.json` either.
- Each half's comments assume the other half exists (`CompanionCommand.kt` KDoc: "termxd polls
  that directory"; `hook_pretooluse.go:76-78`: "via … termxd commands dir, or a direct SFTP write").
- **Net effect on a hook-installed VPS:** every non-fast-path, non-allowlisted tool call (every
  `Bash`/`Edit`/`Write`) blocks Claude 30 s then denies, regardless of phone taps. Also
  unimplemented by consequence: `remember=true` settings.json persistence and the
  `update_allowlist` command (the "Always approve" button) — `allowlist.txt` is hand-edit only.
- Fix options: (a) a consumer loop in termxd (in `watch-herdr` or a tiny commands→approvals
  translator); (b) phone writes `.res.json` directly over SFTP (the Go comment already anticipates
  this; smallest diff). Add a broker item to `docs/PRE_RELEASE_CHECKLIST.md` either way.
- Verify: `grep -rn "CommandsDir\|commands/" termxd --include='*.go' | grep -v _test` and
  `grep -rn '\.res\.json' android --include='*.kt'` (at finding time: no writer; post-fix this
  grep finds `respondToApproval` — that's the proof the resolution landed).

### 14.2 ❗ Host keys are never verified (`PromiscuousVerifier`)

- `android/libs/ssh-native/.../impl/SshConnector.kt:37-40`: `client.addHostKeyVerifier(
  PromiscuousVerifier())` with `TODO(Phase 2 — Task #21)`. The known_hosts file is created but
  never consulted. Every connection — first and subsequent — accepts any host key (MITM-able).
- Contradicts `PRIVACY.md:34-36` ("trust-on-first-use host-key pinning") and makes the
  "Host key changed. Possible MITM" test-connection error string
  (`AddEditServerViewModel.kt`, `SetupWizardViewModel.kt`) unreachable — `HostKeyMismatch` can
  never be thrown.
- This is the oldest open TODO in the tree and, for a public FOSS SSH client, the security floor.
  Ranked second only to 14.1 at audit time; with 14.1 resolved it is now the **#1 open item**.
- Verify: `grep -rn PromiscuousVerifier android` and confirm nothing reads the known_hosts file.

### 14.3 PRIVACY.md is two generations stale

- `PRIVACY.md:21` claims keys are "encrypted at rest under an Android Keystore AES-256-GCM key at
  `filesDir/vault.enc`" — reality since v1.1.7 is plaintext `vault.json`
  (`FileSystemSecretVault.kt`; README was corrected, PRIVACY.md was not).
- `PRIVACY.md` "What stays on your VPS" still lists tmux hooks / `~/.tmux.conf` injection —
  removed in v1.4.0; install now actively strips that block.
- `PRIVACY.md:51` directs users to "Settings → Config sync" — that UI does not exist (§15.1).
- Also mentions "themes" / "tmux session names" as stored data — both concepts removed.
- Re-checked 2026-06-10: the redesign wave made nothing here WORSE (the new phone-side broker
  writes land under the already-listed `~/.termx/approvals/`), but the full truth pass is still
  owed. One more stale claim spotted: "Server passwords — in-memory only … never written to
  disk" — passwords have persisted to the vault (`password-<serverId>`) since the Add/Edit save
  flow shipped; predates the wave.

### 14.4 Notification deep links don't route — **PARTIALLY RESOLVED 2026-06-10** (Task #47)

> **Resolution (the serverId half):** every open-app notification intent now carries
> `EXTRA_SERVER_ID = "TERMX_SERVER_ID"` + SINGLE_TOP, and `MainActivity` reads it in
> `onCreate(savedInstanceState == null)` + `onNewIntent` → connect-then-maximize the terminal
> sheet. Tapping any session-scoped notification lands you IN that session.
> **Still open (the diff half):** nothing routes a diff notification to
> `diffViewerRoute(diffId, serverId)` — tapping "Review" opens the right session's terminal, not
> the diff. Original finding kept for history:

- `EventNotificationRouter` puts `EXTRA_APPROVAL_ID` (permission `request_id` or `diff_id`) on its
  open-app PendingIntents, and `Routes.diffViewerRoute(diffId, serverId)` exists in
  `TermxNavHost.kt` — but ~~`MainActivity` never reads intent extras~~ (it reads
  `EXTRA_SERVER_ID` now) and nothing calls `diffViewerRoute` from a notification path. The in-app
  permission dialog still appears (it's driven by the event stream, not the intent), so the
  permission case degrades gracefully; the diff case loses its destination.
- Verify: `grep -rn EXTRA_APPROVAL_ID android --include='*.kt'` (writer only, no reader) and
  `grep -rn diffViewerRoute android --include='*.kt'`.

### 14.5 F-Droid metadata frozen at v0.3.4

- `metadata/dev.kuch.termx.yml`: `CurrentVersion: 0.3.4`, `CurrentVersionCode: 74`, build recipe
  pinned to tag `v0.3.4`; description still says "opens your tmux sessions 1:1" and
  "Keystore vault" (`:13`, `:20`).
- `fastlane/metadata/android/en-US/changelogs/` contains exactly one file (`76.txt`) while HEAD's
  versionCode is ~174; icon and screenshots are documented placeholders.
- Consistent with "Pending submission" in the README, but everything here would need a truth pass
  before any fdroiddata MR (checklist exists in `docs/FDROID.md`).

### 14.6 README overstates the broker — **RESOLVED 2026-06-10** (Task #41)

> **Resolution:** the README broker paragraph now says decisions are written to
> `~/.termx/approvals/` for the hook to consume and "Always approve" appends to
> `allowlist.txt` — no `~/.claude/settings.json` claim (that persistence remains unimplemented).
> Original finding kept for history:

- `README.md:33-35`: "decisions round-trip into `~/.claude/settings.json`" — that was the
  unimplemented `remember=true` path from 14.1. Until 14.1 was fixed, the honest description was
  "decisions are written for the hook to consume" (and even that needed the missing consumer).

### 14.7 Minor code-level notes (not bugs, worth knowing)

- `hook_posttooluse.go` has a vestigial `if f, ok := tr["file_size_before"]…{ _ = f }` no-op and a
  `var _ = uuid.NewString` import-holder; harmless.
- `ConfigExport.kt:101-102` exports `activeThemeId` and `pttMode` — concepts removed in
  v1.3.0/v1.1.11 respectively; schema drift inside already-dead code (§15.1).
- `hooks.go` session naming still probes tmux first (`resolveSessionName`) — fine (a tmux user's
  events get nice names), just know `plain-shell-<ppid>` is the post-tmux-era default.
- The KDoc on Room schema export in `core/data/build.gradle.kts` ("1.json intentionally not
  checked in yet — task #51") is stale: schemas 1–5 ARE checked in.
- `docs/ROADMAP.md` references task-list IDs (#12–#51) from the maintainer's Claude Code task
  system — those tasks are not in the repo; treat the numbers as historical context only.
- Post-wave comment drift (2026-06-10): the `approvalResponse` comment in `hook_pretooluse.go`
  (~line 75) still names "`EventStreamClient.sendCommand` → termxd commands dir" as a possible
  writer — the live writer is `respondToApproval`'s direct SFTP write; the commands dir is
  dormant (§4.2). Same vintage: `MoshClient.tryConnect`'s KDoc still points at
  "`TerminalViewModel.openSession`" for the race, which now lives in
  `ConnectionManager.openSession`. Both harmless, fix on next touch.

---

## 15. Dead / unreachable code registry

### 15.1 Config sync (`feature/settings/configsync/`) — complete but unwired

`ConfigCrypto` + `ConfigExport` + `ConfigImport` (+ tests) have **zero references outside the
package**: no SettingsScreen entry, no SAF picker, no ViewModel surface. PRIVACY.md advertises it
(§14.3). This repeats the `custom_themes` pattern (built → never wired → dropped in v4). Decide:
wire it (a Settings row + two file pickers + passphrase dialogs) or delete it and fix PRIVACY.md.
Verify: `grep -rln "ConfigBundle\|ConfigCrypto" android --include='*.kt' | grep -v configsync` → empty.

### 15.2 Event types without emitters

`session_created`, `session_closed`, `claude_idle`, `claude_working` survive in both schemas and
in the phone's router, but lost their Go emitters in the tmux removal. The phone's
`SessionClosed → termx.disconnect` notification is therefore currently dead in practice (transport
drops are noticed by the UI, not by events). Keep the schema; know the wiring state.

### 15.3 Deprecated-but-retained

`internal.RotateIfNeeded` (Go) — superseded by fd-based rotation inside `AppendEventAtCap`; kept
for its tests and out-of-band callers, explicitly marked deprecated in its comment.

### 15.4 Declared-but-unwired preferences

`AppPreferences.paranoidMode` ("biometric on every connect") is persisted and surfaced in docs but
no connect-path code reads it. `AlertPreferences.muteTasks/muteErrors` setters exist; no Settings
UI row flips them yet (router honors them if ever set).

---

## 16. Project history & major decision timeline

| When / version | What happened | Why it matters now |
|---|---|---|
| v0.2.x (2026-04) | Bootstrap: 11-module skeleton, CI, release-it. ROADMAP written; 15 architecture decisions "grilled" and locked | ROADMAP §"locked" list is still the constitution |
| v0.3.0–0.3.4 | Phase 1–2: terminal, Termux fork, mosh bundle, Room, vault v1, wizard | v0.3.0 R8 crash + v0.3.1 BouncyCastle regression → pre-release checklist items 1, 2, 9 |
| v1.1.0–v1.1.6 | Keystore-encrypted vault era | OEM Keymint NPEs brick vaults in the field |
| **v1.1.7** | **Keystore removed** → plaintext `vault.json`, legacy cleanup | The vault KDoc is the canonical rationale |
| v1.1.8–v1.1.16 | Hardening train: auto-lock 5min→24h, PTT hallucination guard, write-error surfacing, keepalive 30 s, sticky-modifier fixes, mosh linker-logcat diagnostics | Most load-bearing comments date from here |
| v1.1.17 | In-app APK updater (GitHub Releases, F-Droid-aware) | |
| v1.1.21 / **v1.1.23** | Pure-Kotlin mosh SSP transport shipped (`feat!`)… **and reverted** to the native client | Don't re-attempt casually; commits `3a04233`/`9b00fcb` |
| v1.2.0 | JetBrains Mono NL bundled | |
| **v1.3.0–v1.3.2** | **Sorcerer becomes the only theme**; picker/editor deleted, `custom_themes` dropped (schema v4); two follow-up fixes ending at `installAsDefault` | The static-defaults install is the only correct theme path |
| v1.3.3–v1.3.4 | PTT: line-break collapse to CR; Gemini model bump to GA `gemini-3.1-flash-lite` (preview retired upstream) | Model name will need future bumps; watch for 404s. The collapse (`638104a`) was only LAYER 1 of the "Send doesn't submit" symptom — layer 2 (Claude Code's <64-char chunk rule) was fixed post-v1.7.0 by the two-phase submit; §5.11 |
| **v1.4.0** / termxd 0.1.2 | **tmux integration removed** end-to-end (schema v5, startup command replaces auto-attach, installer strips old tmux blocks) | "Plain shell, BYO multiplexer" identity finalized |
| v1.5.0 / termxd 0.1.3 | `watch-herdr` daemon + `agent_finished` + UnifiedPush Tier 2 | The current daily-driver feature |
| termxd 0.1.4 | systemd --user PATH fix for herdr resolution | The PATH gotcha; applies to any future daemon |
| **v1.6.0** / termxd 0.1.5 (2026-06-07) | mosh side channel (alerts over mosh), companion on-connect update offers, supersede-only-when-deliverable, idle-finish catching, unified rotation | HEAD at the 2026-06-10 full audit |
| **post-v1.6.0 redesign wave** (2026-06-10, shipped in **v1.7.0**) | 14 tasks: broker return path FIXED (phone writes `.res.json` + allowlist append; §14.1 resolved, golden fixtures + Go contract tests); mosh stderr classification + locale prefix + 3 s first-output liveness gate + truthful fallback surfacing + termxd install preflight; **`ConnectionManager` ownership refactor + lifecycle flip** (sessions survive navigation/backgrounding; explicit disconnect; EOF full-teardown leak fixed); terminal-as-sheet (`terminal/{serverId}` route deleted, notification connect-then-maximize); Moshi-style home with live-thumbnail session cards (reorder/move-to-group/collapse deleted); single-row extra-keys bar with in-bar PTT mic; pill Ready card (Insert button removed) | The state THIS document describes; sections untouched by the wave may carry pre-wave line numbers |
| **post-v1.7.0 input wave** (2026-06-11, shipped in **v1.7.1**) | Tasks #49–53: tap-before-type focus fix (single-source focus — the TerminalView; Compose focus thief + parallel `onKeyEvent` path deleted); per-shell FIFO write queue (`ShellWriteQueue`); PTT two-phase submit (bracketed-paste wrap + delayed lone CR via `submitLine`) | The Claude Code composer contract — §5.11; gotcha #27; checklist items 11–12 |
| **v1.7.2 input/prompt fixes** (2026-06-15) | extra-keys repeat keys (arrows + nav) now fire on RELEASE via `detectTapGestures` — drag-to-scroll no longer leaks/auto-repeats a keystroke (the THIRD latent flaw the v1.7.0 redesign promoted to a daily bug, after tap-before-type and the 64-byte submit rule); Gemini transcription/translation prompts re-synced with the push-to-talk upstream (re-imported the dropped numerals rule + double-attention language clamp) | gotchas #28–29; §5.8 bar gestures, §5.11 prompt provenance; checklist item 13 |

---

## 17. Gotchas & load-bearing details

The "if you change this without reading its comment, you will reintroduce a shipped bug" list:

1. **`ThemeBinder.installAsDefault` from `Application.onCreate`** — never theme from
   AndroidView factory/update (null emulator; silent no-op). §10.
2. **PTT mic gesture rules** (`MicKey` in the extra-keys bar — ex-`PttFab`, same two laws, new
   home): the key must stay COMPOSED through Recording; first pointer-down consumed. §5.11.
3. **`send` not `trySend`** in PTY/mosh output flows — frame drops corrupt TUI repaints.
4. **Concurrent stdout+stderr drain on every sshj exec** — per-stream flow-control windows
   deadlock otherwise (`execCapture` comments).
5. **Blank password on edit preserves the vault entry** — only auth-type flip clears it
   (both Add/Edit and Wizard saves).
6. **Vault `store()`/`delete()` are NOT lock-gated; `load()` is** — deliberate; §5.4.
7. **Marked-block sentinels and `_termx_managed` are wire constants** — uninstall matches
   verbatim; never reword.
8. **Hook fail-open vs fail-closed**: malformed PreToolUse stdin fails OPEN; timeout fails CLOSED
   (default-deny). Both deliberate; preserve the asymmetry.
9. **Rename-rotation only** for `events.ndjson` — truncation desyncs `tail -F` offsets.
10. **`mosh-server … -- <startupCommand>` is appended VERBATIM** — the remote login shell does the
    quoting; re-escaping in `moshServerCommand` breaks it (unit-tested contract).
11. **Bash DEBUG-trap one-shot arming** in the shell hooks script — installing the trap at source
    time fires it for every remaining .bashrc statement. §6.2.
12. **systemd --user PATH omits `~/.local/bin`** — any daemon exec'ing user-installed CLIs needs
    both `Environment=PATH=` in the unit AND code-side path resolution (`herdrBin()` pattern).
13. **`agent_finished` payload field names** (`agent`, `workspace`, `source`) are a Go↔Kotlin wire
    contract; golden-file tested on the Kotlin side.
14. **`VersionTag.companionInstalledVersion` null = "offer reinstall"**, never "up to date" — the
    whole companion-update UX leans on this.
15. **Hub unpublish before session close** in `cleanupQuietly` (which now lives in
    `ConnectionManager`) — reversed order makes router collectors die on transient exec errors.
    Shell EOF must keep delegating to the FULL `cleanupQuietly` — closing only pty/mosh there
    re-introduces the one-transport-per-EOF leak (§5.8).
16. **EventStreamHub consumers each spawn their own remote `tail`** — adding a consumer costs one
    SSH exec per server; documented trade-off, don't "optimize" into shared flows without
    rethinking lifecycle.
17. **`tryUseCurrentOrDefaultDistributor(context)` deprecation suppression** in
    `UnifiedPushManager` is intentional — the Activity overload is for the in-UI picker; cold
    start has no Activity.
18. **Compose `combine` 5-arg limit workaround** in SettingsViewModel (pre-collapse into holders)
    — extending state? extend the holders, don't switch to the vararg overload.
19. **`onboardingComplete` StateFlow initial value `true`** — prevents bouncing existing installs
    through onboarding on upgrade; don't "fix" to false.
20. **Detect probe must check `$HOME/.local/bin/termx` explicitly** — non-login sshj shells don't
    have it on PATH; `command -v` alone reports "not installed" on every healthy install.
21. **The connect pipeline must launch on `Dispatchers.Main.immediate`** — the vendored Termux
    `TerminalSession` constructor binds a no-arg `Handler()` to the constructing thread's looper;
    constructing it from the manager scope's Default dispatcher crashes/misbinds. §5.8 threading
    note + `ConnectionManager.connect` inline comment.
22. **The emulator buffer is main-thread-confined** — `feedRemoteBytes` posts every append to the
    main looper, so ANY read of emulator state for rendering (the thumbnail renderer) must hop to
    `Dispatchers.Main.immediate` too; rendering off-main races the byte pump.
23. **`ConnectionManager.connect` is bind-if-alive** — Connecting/AwaitingPassword/Connected
    slots return untouched. Anything that needs a redial after one of those states must drop the
    state first (`submitPassword` resets AwaitingPassword → Disconnected before retrying; copy
    that pattern, don't bypass it).
24. **Only the sheet's 32 dp drag-handle strip carries `anchoredDraggable`** — putting the drag
    modifier on the terminal body (or wrapping in `ModalBottomSheet`) steals
    selection/scrollback/gesture touches from `TerminalView`. §5.8.
25. **Manager tests must use `QuiescentMainDispatcherRule`** — looper-backed Main (hand-rolled, NOT
    `asCoroutineDispatcher()`: Robolectric's mocked clock never fires its `Delay`) + cancel-AND-JOIN
    of manager scopes before `resetMain()`; plain `scope.cancel()` in `@After` re-creates the
    cross-class "Dispatchers.Main is used concurrently with setting it" flake. §12.
26. **Vault lock must MINIMIZE the sheet, never disconnect** — steady-state transport paths are
    vault-free by design; only fresh connects read the vault. Re-introducing a vault check on a
    live-session path breaks the lifecycle flip.
27. **PTT submit is a TWO-write sequence by design** — bracketed-paste-wrapped text, then a lone
    CR as a SEPARATE write after a transport-aware delay (75 ms ssh / 300 ms mosh). Collapsing
    it back into one `text+"\r"` write resurrects the 64-byte paste bug: Claude Code's stdin
    tokenizer only treats `\r` as the Enter key in chunks <64 chars. §5.11 /
    `buildSubmitSequence`.
28. **Extra-keys repeat keys fire on RELEASE via `detectTapGestures`** — arrows + nav live in a
    `horizontalScroll` row; the old raw `awaitEachGesture` fired on touch-DOWN and ignored
    consumption, so every drag-to-scroll leaked a keystroke and a slow drag auto-repeated it
    (2026-06-15 drag-fires-keys bug). detectTapGestures' consumption-based cancellation IS the
    disambiguation — never revert to fire-on-down or re-implement slop logic. §5.8.
29. **`feature/ptt` Gemini prompts are a fork of push-to-talk's** — keep them synced and never drop
    the numerals rule or the double-attention language clamp (their omission was the 2026-06-15
    numbers-as-words / language-drift bug). The `NO_SPEECH` guard and `generationConfig`
    temperature 0.0 are termx-ONLY — keep them on every re-sync. §5.11.

---

## 18. Deferred work / roadmap state

From `docs/ROADMAP.md` "explicitly deferred past v1.0" + observed TODOs, still open as of the
2026-06-10 redesign wave:

- **Known-hosts verifier (Task #21)** — the `SshConnector` TODO; §14.2. Highest-value security item.
- ~~**Permission broker server half**~~ — DONE 2026-06-10 (phone-side `.res.json` writes; §14.1).
- Supervised relaunch of the **mosh side channel** after transport death (documented limitation in
  `ConnectionManager.startMoshSideChannel`) — STILL OPEN; the wave moved the code, not the gap.
- ~~The "Server-ownership refactor"~~ — SHIPPED as `ConnectionManager` (Task #42/#43). What
  remains deferred is **process-death session resurrection**: the manager's KDoc explicitly
  scopes it out (swipe-kill/OOM/force-stop drop every transport, no resurrection on relaunch).
- NEW follow-up: the home rail's **1 Hz thumbnail loop keeps rendering under an expanded
  terminal sheet** — the host route stays RESUMED beneath the overlay, so
  `repeatOnLifecycle(RESUMED)` doesn't pause it (the comment in `ActiveSessionCardItem` assumes
  navigation, which the sheet bypasses). Perf nit; future fix is gating on
  `TerminalSheetState.maximizedServerId`.
- NEW follow-up: the **diff-notification deep link** is still unwired (§14.4's remaining half).
- NEW follow-up: **PTT submit verify-and-retry** — watch whether the Claude Code composer
  actually cleared after the lone CR and, if not, send Escape + CR (covers autocomplete eating
  the CR, e.g. a leading `/` opening the command popup; anthropics/claude-code#15553).
  Documented in-code at `buildSubmitSequence` as the KNOWN FUTURE OPTION; a blind retry would
  double-submit the common case, so v1 deliberately ships without it.
- User-editable **extra-keys layouts/presets**; pull-to-refresh ping (`ServerListViewModel.onRefresh`
  is a no-op); mute-toggle Settings UI for task/error channels; paranoid-mode wiring.
- Diff viewer syntax highlighting (tokenizer deferred); side-by-side mode.
- SFTP file browser, overlay-bubble PTT, Bluetooth PTT, on-device Whisper fallback, multi-device
  config sync, Play Store — all explicitly post-v1.0.
- **F-Droid submission** — metadata truth pass required first (§14.5).
- Key-auth "Test connection" in the Add/Edit *sheet* (wizard already supports it).

---

## 19. Quick reference

### VPS paths
`~/.termx/{events.ndjson, events.ndjson.1, sessions/, approvals/, diffs/, commands/, active/,
allowlist.txt, ntfy-endpoint, termx-shell-hooks.sh}` · binary `~/.local/bin/termx` ·
unit `~/.config/systemd/user/termx-herdr-watch.service` · hooks in `~/.claude/settings.json`
(tagged `_termx_managed`) · rc blocks in `~/.bashrc`, `~/.zshrc`.

### Phone paths
`filesDir/vault.json` (secrets) · `filesDir/known_hosts` (created, unused — §14.2) ·
`filesDir/terminfo/` (mosh) · DataStore `app_prefs`, `alert_prefs` · Room `termx.db` (v5) ·
`cacheDir/ptt-<uuid>.m4a` (transient).

### Vault aliases
`key-<uuid>-private` · `password-<serverId>` · `gemini.api.key`.

### Redesign-wave landmarks (post-v1.6.0, new/renamed files)
`feature/terminal/.../connection/{ConnectionManager, TerminalSheetState, ActiveSessionsRail,
ActiveSessionCardModel}.kt` · `feature/terminal/.../TerminalSheetHost.kt` ·
`feature/terminal/.../thumbnail/TerminalThumbnailRenderer.kt` (+ its package-trick sibling
`com/termux/view/TerminalRendererMetrics.kt`) · `feature/ptt/.../PttSurface.kt` (renamed from
`PttFab.kt`) · `libs/ssh-native/.../MoshConnectResult.kt` ·
`libs/companion/.../events/ApprovalResponse.kt` ·
`libs/companion/src/test/resources/approvals-golden/` ·
`termxd/cmd/hook_pretooluse_contract_test.go` · deleted: `ServerGroupHeader.kt`, the
`terminal/{serverId}` route, the PTT FAB.

### Magic numbers (rationale lives at the definition site)
| Value | Where | Meaning |
|---|---|---|
| 8 s | `ConnectionManager.MOSH_HANDSHAKE_TIMEOUT_MS` | mosh race before sshj fallback |
| 3 s | `ConnectionManager.MOSH_FIRST_OUTPUT_TIMEOUT_MS` | mosh first-output liveness gate (catches firewalled UDP) |
| 2 s | `MOSH_EARLY_EXIT_WINDOW_MS` (`ConnectionManager` + `MoshSessionImpl`) | exit < this = startup failure |
| 30 s | `SshConnector.KEEPALIVE_INTERVAL_SECONDS` | sshj keepalive |
| 100 ms / 30 s | `hook_pretooluse.go` | approval poll interval / default-deny ceiling |
| 10 s / 2 s | `hooks.go` | long-command / error-command event thresholds |
| 5 MiB | `events.go DefaultRotateBytes` | event log rotation |
| 4096 / DROP_OLDEST | `EventStreamClient` | stream buffer |
| 1 s | `EventStreamClient.RECONNECT_DELAY_MS` | tail retry backoff |
| 24 h | updater + companion-update TTLs; vault auto-lock default | anti-nag windows |
| 60 s | `AlertPreferences` default long-cmd notification threshold | |
| 1500 ms | `PttViewModel.MIN_USEFUL_DURATION_MS` | min recording before Gemini call |
| 75 ms / 300 ms | `SSH_SUBMIT_CR_DELAY_MS` / `MOSH_SUBMIT_CR_DELAY_MS` (`ConnectionManager.kt`) | PTT submit phase-2 lone-CR delay: ssh margin over packet coalescing vs. mosh's 250 ms input-frame ceiling (§5.11) |
| 3 / 500 ms / 1200 ms ±20% | `GeminiClient` | retry budget |
| 15 s / 120 s / 600 s | `InstallCompanionUseCaseImpl` | detect/preview/install SSH idle timeouts |
| 8–32 sp (default 14) | font size clamp | |
| 5 s | server-list delete undo window; watch-herdr re-list tick | |
| 500 ms→10 s | watch-herdr backoff | |
| 60000:60010 / 0.0.0.0 | mosh-server port range / bind (`moshUDPRange` in install.go mirrors it) | |
| 1 s | `TerminalThumbnailRenderer.thumbnails` `periodMs` default | home-card thumbnail poll |
| 180 dp / 110 dp | `ActiveSessionsRail` `CARD_WIDTH` / `THUMBNAIL_HEIGHT` | session card geometry |
| 52×40 dp / 24 dp | `ExtraKeysBar` `KEY_WIDTH`×`KEY_HEIGHT` / `EDGE_FADE_WIDTH` | bar key size / fading edge |
| 60 ms / 300 ms | `ExtraKeysBar` `LONG_PRESS_REPEAT_MS` / `DOUBLE_TAP_WINDOW_MS` | held arrow/nav repeat interval (initial delay now delegated to the platform long-press timeout — `LONG_PRESS_INITIAL_DELAY_MS` removed 2026-06-15) / CTRL·ALT double-tap-to-lock window |
| 32 dp | `TerminalSheetHost.DRAG_HANDLE_HEIGHT` | the ONLY draggable strip of the sheet |
| 42 | foreground service notification id | |

### Key external endpoints
`api.github.com/repos/mkuchak/termx/releases?per_page=50` (companion, filter `termxd-v*`) ·
`api.github.com/repos/mkuchak/termx/releases/latest` (APK) ·
`generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent` (PTT) ·
user's own ntfy endpoint (Tier 2).

### Commands
```bash
# Android
cd android && ./gradlew :app:assembleDebug          # build
cd android && ./gradlew testDebugUnitTest lintDebug # what CI runs
# termxd
cd termxd && make build test                        # dist/termx
# releases
npm run release:android                             # tags vX.Y.Z
npm run release:termxd                              # tags termxd-vX.Y.Z
```

---

## 20. Audit coverage notes

What this document is based on (honesty section):

- **Read line-by-line:** all Kotlin main sources in every module (~24k lines), all Go sources
  (~3.5k lines non-test), every `build.gradle.kts`, the version catalog, all three CI workflows,
  both release-it configs, GoReleaser config, Makefile, all docs (README, ROADMAP, FDROID,
  PRE_RELEASE_CHECKLIST, PRIVACY), F-Droid/fastlane metadata, manifests, `BUILD.md`, the JNI C
  file, and the git/changelog history.
- **Skimmed, not line-read:** the vendored Termux Java (`libs/terminal-view`, 8.6k lines —
  upstream code at a pinned tag; only the fork-added hooks were studied), the test suites
  (~9k lines — absorbed via their documented seams and names rather than full reads), binary
  assets (`.so`, fonts, terminfo, images).
- **Not executed:** no builds, tests, or device runs were performed during the audit; all findings
  are static-analysis + git-history based. §14 findings were each verified with the quoted
  grep/git commands.
- **Out of repo:** the maintainer's Claude Code task list (ROADMAP's task IDs), the herdr project
  itself, and the push-to-talk sibling repo are referenced but were not audited.
- **Update pass (2026-06-10, redesign wave):** every claim added/changed for the post-v1.6.0
  wave was re-verified by reading/grepping the named symbols in the uncommitted work tree
  (ConnectionManager, MoshClientImpl, EventStreamClient, hook_pretooluse.go, install.go, the
  sheet/home/bar/PTT composables, the new test suites, the golden fixtures). The wave's tests
  were green per-task; this doc pass re-ran only `:feature:ptt:compileDebugKotlin` (for its KDoc
  fix), not the full suites.
- **Update pass (2026-06-11, input wave):** every claim added/changed for Tasks #49–53 (focus
  single-sourcing, `ShellWriteQueue`, two-phase PTT submit, the vendored
  `isBracketedPasteMode()` accessor, the 75/300 ms constants) was re-verified by reading/grepping
  the named symbols in the uncommitted work tree; `:feature:terminal:testDebugUnitTest` re-run
  green after this doc pass. The Claude Code findings (the <64-char chunk rule, the first-party
  injector recipe) come from de-minifying the v2.1.172 binary — re-verify against the binary
  before leaning on them for a NEW feature; the stable contract is (c) in §5.11, not (a).

*Last full audit: 2026-06-10 @ `278230c`; last truth-pass update: 2026-06-11, covering the
post-v1.7.0 input wave (uncommitted work tree — keyboard focus, write queue, PTT submit
contract). If you materially change the broker, vault, mosh stack, connection lifecycle,
release flow, or wire schema — update the relevant section AND the findings registry here.*
