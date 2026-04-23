# termx roadmap

Living document. Edit freely as priorities shift. Last rewrite: 2026-04-23.

---

## Where we are (v0.2.7)

The bootstrap is live: signed APK, green CI, release-it pipeline, 11-module
Gradle skeleton that installs on a phone and renders "termx" on a dark screen.
Every module is a stub. The two most load-bearing omissions are:

- No terminal renderer yet (`:libs:terminal-view` is empty)
- No mosh binary (`libs/ssh-native/src/main/jniLibs/<abi>/libmoshclient.so`
  placeholder only)

---

## How to execute this roadmap

This document is the strategic view. Execution happens through **39 discrete
tasks** in the Claude Code task list — `TaskList` in any session. Tasks are
self-contained (project context + exact file paths + acceptance criteria) and
have an explicit blocker graph, so any engineer (AI or human) can claim an
unblocked task and execute without reading the other 38.

Task ID ranges by phase:

| Phase | Task IDs | Sub-tasks |
|---|---|---|
| 1 — Terminal foundations         | 12–18 | 7 |
| 2 — Server manager + keys        | 19–24 | 6 |
| 3 — tmux + multi-tab + mosh      | 25–28 | 4 |
| 4 — termxd + event stream        | 29–34 | 6 |
| 5 — Permission broker + diff     | 35–38 | 4 |
| 6 — Push-to-talk                 | 39–42 | 4 |
| 7 — Notifications + fg service   | 43–45 | 3 |
| 8 — Polish → v1.0                | 46–50 | 5 |

Starter tasks with zero blockers (pick any, parallelizable):
**12** (Termux fork) · **13** (mosh cross-compile) · **14** (SshClient wrapper)
· **19** (Room DB) · **20** (Keystore vault) · **29** (termxd scaffolding) ·
**40** (Gemini client) · **49** (F-Droid metadata).

When a roadmap sub-section and a task disagree on detail, **the task wins** —
it reflects what was actually thought through at execution time.

---

## Architectural ground rules (locked — don't re-litigate)

All 15 decisions from the April 2026 grilling are recorded at:

```
~/.claude/projects/-home-ubuntu-Workspaces-termx/memory/project_termx_purpose.md
```

One-line summaries (for skimming):

- **Product identity**: Claude-Code-first with graceful plain-terminal fallback
- **Tab model**: one tab = one tmux session (1:1), Claude auto-detected
- **Transport**: file-based over SSH — phone tails `~/.termx/events.ndjson`
- **Android stack**: Kotlin + sshj + one NDK binary (`mosh-client` in jniLibs)
- **VPS stack**: single Go static binary (`termxd`) via GitHub releases
- **Permissions**: mirror Claude's own `.claude/settings.json` via a blocking
  `PreToolUse` hook — no parallel whitelist system
- **PTT**: Gemini (`gemini-2.5-flash-lite`), FAB trigger, inject into PTY stream
- **Auth**: Keystore-encrypted blob, biometric on launch, session cache
- **Notifications**: foreground-service SSH tail for MVP (self-hosted FCM relay
  later, never run by us as SaaS)
- **Install UX**: auto during Setup Wizard, skippable, shows dotfile diff,
  reversible via `termx uninstall`
- **Extra keys**: two swipeable user-editable rows, sticky modifiers, presets
- **Plain-shell tracking**: tmux hooks + shell preexec with smart filtering
  (events only for commands >10s or errors >2s)
- **Diff review**: native diff viewer fed by PostToolUse hook (flagship feature)
- **License**: MIT, donations only, no paid tier, no SaaS infra

---

## Phase table

| # | Phase                            | Exit criteria                                                  | Size |
|---|----------------------------------|----------------------------------------------------------------|------|
| 1 | Terminal foundations             | SSH into a real VPS, type commands, see output                 | L    |
| 2 | Server manager + keys            | Add/edit VPSes, biometric-locked key vault, Ed25519 gen+import | M    |
| 3 | tmux + multi-tab + mosh          | Tabs mirror tmux `ls`, mosh survives network flaps             | M    |
| 4 | termxd + event stream            | Auto-install companion, phone renders live events              | L    |
| 5 | Permission broker + diff review  | Phone approves Claude tool calls; diffs render native          | M    |
| 6 | Push-to-talk                     | Hold FAB, speak, text injected into active tab's PTY           | S    |
| 7 | Notifications + foreground svc   | Pings fire for permission / task-done / error / disconnect     | M    |
| 8 | Polish → v1.0                    | Onboarding, config export, theme editor, F-Droid listing       | M    |

Sizes are relative ("how much work compared to the others"), not calendar
estimates. Phases are mostly sequential — Phase 2 needs Phase 1's terminal;
Phase 3 needs a server model from Phase 2; Phase 4 can start in parallel with
Phase 3 once the server model exists. Phase 5 depends on Phase 4's event
stream. Phases 6 and 7 are independent of each other and can interleave after
Phase 5.

Every phase must produce a shippable APK. Never break `main`.

---

## Phase 1 — Terminal foundations

**Goal.** Open a real interactive shell on a VPS from the phone. The two
blocker work items for *all* downstream phases land here.

### 1.1 Copy Termux's terminal-emulator + terminal-view (Apache 2.0)

- Clone `github.com/termux/termux-app` at a pinned commit SHA (record it in
  `android/libs/terminal-view/NOTICE`)
- Copy `terminal-view/src/main/java/com/termux/view/**` to
  `android/libs/terminal-view/src/main/java/com/termux/view/`
- Copy `terminal-emulator/src/main/java/com/termux/terminal/**` to
  `android/libs/terminal-view/src/main/java/com/termux/terminal/`
- Preserve every Apache-2.0 header on every `.java` file
- Create `android/libs/terminal-view/NOTICE` attributing Termux + pinned SHA
- Create `android/libs/terminal-view/LICENSE.apache-2.0` with the full text
- Verify: `grep -L 'Apache License' libs/terminal-view/src/**/*.java` is empty

**Exit**: `./gradlew :libs:terminal-view:assembleRelease` green, AAR built.

### 1.2 Cross-compile mosh-client

- Use the `termux/package-builder` Docker image (or `termux-packages` repo
  `scripts/run-docker.sh`)
- `PREFIX=/data/data/dev.kuch.termx/files/usr` (the exact app data path)
- Build for `aarch64-linux-android` and `arm-linux-androideabi`
- Statically link OpenSSL and protobuf (Termux packages handle this)
- Rename each output to **`libmoshclient.so`** (the `.so` suffix is required
  by Android's APK install extractor even though it's an executable)
- Drop into `android/libs/ssh-native/src/main/jniLibs/arm64-v8a/libmoshclient.so`
  and `android/libs/ssh-native/src/main/jniLibs/armeabi-v7a/libmoshclient.so`

**Exit**: `unzip -l termx-v*.apk | grep libmoshclient.so` shows the binary
present in both `lib/arm64-v8a/` and `lib/armeabi-v7a/`, and `file` on the
extracted binary reports "ELF 64-bit LSB executable, ARM aarch64" (and the
32-bit equivalent).

### 1.3 SshClient wrapper in :libs:ssh-native

- Pure-Kotlin module; depends on sshj (`com.hierynomus:sshj`) + BouncyCastle
- Public API: `SshClient`, `SshTarget`, `SshAuth` (sealed: `Password` /
  `PublicKey`), `SshSession`, `PtyChannel` (with `output: Flow<ByteArray>`),
  `ExecChannel` (with `exitCode: Deferred<Int>`), `SftpClient`
- Registers BouncyCastle provider at module init for Ed25519 + fallback JCA
- Hides all sshj types from the public surface → feature modules depend on
  our abstractions, not sshj

**Exit**: unit test spins up an in-memory sshd (Apache MINA), connects, runs
`echo hello`, verifies output flow. No sshj types leak through the module API.

### 1.4 Minimal terminal screen

- `:feature:terminal` exposes `@Composable TerminalScreen(serverId: UUID?)`
- Uses `AndroidView` to host Termux's `TerminalView`
- A tiny bridge class implements Termux's `TerminalSession.SessionClient`
  contract against the `PtyChannel` from §1.3
- Wires keypresses → channel.write, channel.output → terminal session,
  layout-size changes → channel.resize

**Exit**: hardcoded "test server" env var (`TERMX_TEST_SERVER=user@host`) +
temporary private key in assets (debug build only, .gitignore'd) → app boots
to a "Connect" button → tap → bash prompt appears, `ls` returns real
remote output, characters typed on the on-screen keyboard reach the VPS.

### 1.5 Extra-keys bar

Per the grilled decision (two swipeable rows, user-editable *later*).
For Phase 1 ship two hardcoded rows:

- Row 1: `ESC TAB CTRL ALT ↑ ↓ ← →`
- Row 2: `HOME END PGUP PGDN | ~ \ /`

Sticky modifier behavior:
- Tap `CTRL`/`ALT` once → highlighted, next keypress is modified, then
  un-highlights
- Double-tap → lock (stays highlighted until tapped again)
- Long-press any key → repeat while held (useful for arrows)

Volume Down = Ctrl binding per original plan (users expect it from Termux).
Haptic on every tap.

### 1.6 Gestures

- **Pinch-to-zoom** font size (8sp–32sp, persisted via DataStore)
- **Two-finger vertical scroll** → scrollback (falls through to the
  terminal's own ring buffer in Phase 1; tmux scrollback is Phase 3)
- **Long-press** → text selection handles appear, long-press again on
  selection copies
- **Double-tap on URL** → browser open confirmation dialog

### 1.7 Theme pack

Six built-in themes, no custom editor yet:

- Dracula (default)
- Nord
- Gruvbox Dark
- Tokyo Night
- Catppuccin Mocha
- Solarized Dark

Store active theme id in DataStore. Settings screen has a theme picker list;
switching is live without restart.

Theme model: `data class TerminalTheme(val id, val name, val background,
val foreground, val cursor, val ansi: List<Color> /* 16 entries */)`.

---

## Phase 2 — Server manager + keys

**Goal.** Replace Phase 1's hardcoded test server with a persisted vault.

- **Room DB** in `:core:data`
  - `Server` entity (id, label, host, port, username, authType, keyPairId,
    groupId, useMosh, autoAttachTmux, tmuxSessionName, lastConnected,
    pingMs, sortOrder)
  - `KeyPair` entity (id, label, algorithm, publicKey, keystoreAlias, createdAt)
  - `ServerGroup` entity (id, name, sortOrder, isCollapsed)
  - Repository interfaces in `:core:domain`
- **Auth flow** (`:core:data`)
  - Android Keystore-backed AES key wraps an encrypted blob holding all
    private keys
  - Biometric prompt on app launch unlocks the blob → cached in memory
  - 5-minute background timeout re-locks
  - "Paranoid mode" setting → biometric on every connect
- **Server list** (`:feature:servers`)
  - LazyColumn of cards: label, host, ping dot, last-connected timestamp
  - Swipe-delete with 5 s undo snackbar
  - Drag-to-reorder
  - Expandable server groups (collapsed state persisted)
  - FAB → add server bottom sheet
  - Empty state with "Add your first server" CTA
- **Add/edit sheet**
  - Fields: label, host, port, username, auth method (key / password),
    key picker or password entry
  - "Test connection" button — opens sshj handshake, reports error clearly
  - Save → insert row → returns to list
- **Key management** (`:feature:keys`)
  - Generate Ed25519 via BouncyCastle `Ed25519KeyPairGenerator`
  - Wrap private key under Keystore alias, store wrapped bytes + public in Room
  - Import OpenSSH `.pem` / `id_rsa` via `SAF` file picker
  - Export public key as `.pub` text, share sheet, or QR code (Phase 2 uses
    a tiny QR lib — pin `com.google.zxing:core:3.5.3` or similar)
  - Show SHA256 fingerprint

**Exit**: add a real VPS through the UI, tap it, shell opens. No more env-var
test server.

---

## Phase 3 — tmux + multi-tab + mosh

**Goal.** Tabs represent tmux sessions; mosh handles network hiccups.

- **tmux auto-attach on connect**
  - Connection flow: sshj open shell → immediately `tmux new-session -A
    -s <label>` (from Server row's `tmuxSessionName`, default `main`)
- **Session registry for tabs** (pre-termxd — uses plain tmux)
  - Run `tmux ls -F '#{session_name}|#{session_activity}|#{session_attached}'`
    on demand (tab bar refresh + periodic poll every 30 s)
  - Parse into `List<TmuxSession>` rendered as bottom tab bar
  - "+" button = new named session via `tmux new-session -s`
  - Close tab = detach only (session survives server-side)
- **Multi-session tab UI**
  - Horizontal scroll at >3 tabs
  - Activity dot when output arrives in a background tab
  - Long-press → rename (sends `tmux rename-session`)
  - Tab badge shows transport: `[mosh]` or `[ssh]`
- **Mosh integration**
  - `MoshConnection` in `:libs:ssh-native` wraps `ProcessBuilder(File(ctx
    .applicationInfo.nativeLibraryDir, "libmoshclient.so").absolutePath, ...)`
  - Handshake via `mosh-server` (needs to be installed server-side — will be
    covered by Phase 4's termxd bootstrap; for Phase 3, assume it exists or
    document the one-liner)
  - 8-second timeout → fall back to sshj → update `Server.useMosh = false`
    (optional: retry mosh on next connect per grilled preference)
  - UDP port discovery, key exchange per mosh protocol
- **Scrollback** → two-finger scroll now triggers `tmux copy-mode` when
  in a tmux session (vs local terminal ring buffer in plain SSH)

**Exit**: tab bar shows every tmux session on the VPS (including sessions
you started from a laptop), tapping a tab attaches, closing a tab detaches
the mosh connection without killing the tmux session.

---

## Phase 4 — termxd + event stream

**Goal.** Install the Go companion on the VPS and start reading its event
stream from the phone. Introduces a new Gradle module `:libs:companion` on
the Android side — pure-Kotlin NDJSON parser + event schema + SSH-backed
event reader, testable without a device.

- **`termxd/` Go module**
  - `go mod init github.com/mkuchak/termx/termxd`
  - Single `termx` binary, subcommand-dispatched
  - GoReleaser config (termxd/.goreleaser.yaml): linux/amd64, linux/arm64
    static builds, goreleaser publishes to GitHub Releases
  - New CI workflow `termxd-release.yml` triggered on `termxd-v*.*.*` tags
    (so the Android and companion release cadences can drift)
- **CLI surface**
  - `termx install` — idempotent bootstrap:
    - Detects OS (`ubuntu`, `debian`, `alpine`, `arch`, `fedora`)
    - Installs `mosh` and `tmux` via the right package manager
    - Installs Claude Code if not present (`npm i -g @anthropic-ai/claude-code`)
    - Creates `~/.termx/{sessions,approvals,diffs,commands}` directories
    - Appends marked blocks to `~/.bashrc`, `~/.zshrc`, `~/.tmux.conf`,
      `~/.claude/settings.json` (each block delimited by
      `# --- termx begin ---` ... `# --- termx end ---`)
    - Writes `~/.local/bin/termx` (self-copy)
  - `termx uninstall` — removes every marked block, deletes `~/.termx/`,
    deletes `~/.local/bin/termx` (self-delete last)
  - `termx _on-session-created <name>` — tmux hook, writes
    `~/.termx/sessions/<name>.json` and appends `session_created` event
  - `termx _on-session-closed <name>` — counterpart
  - `termx _preexec <cmd>` — shell hook, records command+start-timestamp
    to an in-memory/tmp state file
  - `termx _precmd <exit-code>` — shell hook, computes duration+exit,
    emits `shell_command_long` event if duration >10 s, or
    `shell_command_error` if exit ≠ 0 and duration >2 s
- **Event schema** (append-only NDJSON to `~/.termx/events.ndjson`)

  ```json
  {"ts":"2026-04-23T12:34:56Z","session":"main","type":"shell_command_long",
   "payload":{"cmd":"./build.sh","duration_ms":42000,"exit":0}}
  ```

  Types emitted by Phase 4: `session_created`, `session_closed`,
  `shell_command_long`, `shell_command_error`. Phase 5 adds the Claude
  types.
- **Setup Wizard integration** (in `:feature:servers`)
  - New wizard step after connection test: "Install termx companion?"
  - Expandable details showing exactly what `termx install` will do
    (invokes `ssh <user@host> termx install --dry-run` and displays diff)
  - [Install] runs the non-dry-run version via sshj with live progress
  - [Skip] leaves the server in "plain SSH" mode (everything still works,
    but Phase 5+ features unavailable for this server)
- **Phone-side reader**
  - New module `:libs:companion` (Kotlin) — wraps sshj exec to run
    `tail -F ~/.termx/events.ndjson` over the active session
  - Parse NDJSON → `Flow<TermxEvent>`
  - Bridge into Phase 3's tab status (session_created triggers a tab refresh)
- **Tab rendering upgrade**
  - Now reads from `~/.termx/sessions/*.json` instead of `tmux ls` parsing
    (richer state: `status: idle|working|awaiting_permission`)

**Exit**: add a new VPS, wizard offers termxd install, accept it, wizard
runs `termx install` via SSH, post-install a long `sleep 30` in any tab
produces a notification on the phone.

---

## Phase 5 — Permission broker + diff review

**Goal.** The two Claude-specific flagship features.

- **PreToolUse blocking hook**
  - `termx install` writes a hook entry in `~/.claude/settings.json` that
    invokes `termx _hook-pretooluse` on every Claude tool call
  - Hook script reads stdin (tool_name, tool_input, session_id, cwd),
    generates a UUID, writes `~/.termx/approvals/<uuid>.req.json`, emits
    `permission_requested` event with that UUID
  - Then blocks: polls for `~/.termx/approvals/<uuid>.res.json` every 500 ms
    with a configurable timeout (default: infinite)
  - On decision file appearance: reads allow/deny, emits
    `permission_resolved` event, returns the appropriate Claude-hook JSON
    output (`"permissionDecision":"allow"` or `"deny"` with
    `"permissionDecisionReason"` echoing user input)
- **Phone-side permission dialog**
  - New receiver `PermissionEventRouter` in `:core:data` listens for
    `permission_requested` events
  - Foregrounds the app (or posts an actionable notification — see Phase 7)
  - Shows a rich native dialog: tool name, tool args (syntax-highlighted
    for `Bash`), three buttons [Deny] [Allow once] [Allow + pattern]
  - On "Allow + pattern": if tool is `Bash`, offer glob edit (e.g., the
    user can generalize `npm test` → `npm *`); writes the rule into
    `~/.claude/settings.json` via a termxd-exposed edit command, then
    writes the one-shot allow to the decision file
- **PostToolUse diff capture**
  - Hook on Write/Edit/NotebookEdit tools reads stdin, writes
    `~/.termx/diffs/<uuid>.json` containing `{file_path, old_content,
    new_content, tool_name}`, emits `diff_created` event
- **Native diff viewer** (`:feature:terminal` or new `:feature:diff`)
  - Slim "N files changed" badge on the active tab (pulsing when new)
  - Tap → full-screen diff viewer:
    - Tokenized syntax highlighting (minimal in-tree highlighter for
      Kotlin, TS/JS, Python, Go, shell, JSON/TOML/YAML — no external dep)
    - Unified/side-by-side toggle (side-by-side needs landscape or narrow
      font; default to unified on phones)
    - Swipe left/right between diffs in the current session
    - Header shows tool (`Write` / `Edit`) + file path
    - Long-press a line for "copy line" / "copy file path"
  - "Files changed" drawer per session — rolling list, tap to re-open any

**Exit**: run Claude from a tab, say "edit README.md to add a note", Claude
calls Edit → phone pops up a permission dialog → approve → Claude runs →
diff viewer shows the change inline with highlighting.

---

## Phase 6 — Push-to-talk

**Goal.** Hold a button, speak, text appears at the PTY cursor.

- **FAB in `TerminalScreen`** (`:feature:ptt`)
  - Hold-to-record, release = transcribe + inject
  - Swipe-left-to-cancel
  - 30 s auto-submit cap
  - Waveform visualizer (animated bars from `AudioRecord.read()` amplitude)
  - Haptic at start/stop/cancel
- **Audio capture**
  - `AudioRecord`, 16 kHz mono
  - Encode to MP4/AAC (`MediaRecorder` with `OutputFormat.MPEG_4` +
    `AudioEncoder.AAC`) — widest Gemini compat, API 21+
- **Gemini call**
  - Google AI client SDK (`com.google.ai.client.generativeai`) OR raw OkHttp
    to `https://generativelanguage.googleapis.com/v1beta/models/
    gemini-2.5-flash-lite:generateContent`
  - Multimodal part: `{inlineData: {data: base64(aac), mimeType: "audio/mp4"}}`
  - Single-prompt transcribe-and-clean — prompt adapted from the
    `github.com/mkuchak/push-to-talk` reference:
    > "You are a precise transcription assistant. Transcribe the following
    > audio faithfully in {lang}. Fix obvious spelling/grammar errors while
    > preserving tone. Punctuate to reflect intonation. Output only the
    > corrected transcription."
  - Retry with exponential backoff (3 attempts, respects Retry-After)
- **Injection**
  - Write transcribed bytes directly into the active session's PTY output
    stream — no clipboard dance (Android's advantage over desktop PTT)
  - Modes (long-press FAB to toggle):
    - **Command mode** (default): inject + append Enter
    - **Text mode**: inject without Enter
  - Mode indicator next to the FAB
- **API key**
  - User pastes in Settings → stored in the Keystore-encrypted blob
    (same vault as SSH keys from Phase 2)
  - "Test" button verifies against Gemini with a trivial call
- **History**
  - Last 100 transcriptions in local Room DB (for re-use / debugging)
  - Clear button

**Exit**: hold FAB in a Claude tab, say "run npm test and show me any
failures", release → sentence appears at the prompt → Enter → Claude runs.

---

## Phase 7 — Notifications + foreground service

**Goal.** Phone-in-pocket awareness. Claude needs approval, a task
completes, an error happens, the connection drops — you get a ping.

- **ForegroundService**
  - Starts on first connection, stops when the last tab closes
  - Persistent notification shows: "N tabs across M servers"
  - Actions: [Open] [Disconnect all]
  - Foreground type `dataSync` (Android 14+ requires a type declaration)
  - Respects Doze; survives for ~30–60 min of idle before the OS reclaims
- **Event router**
  - Service subscribes to every active session's `events.ndjson` tail
  - Routes by event type:
    - `permission_requested` → high-priority notification with
      [Approve] [Deny] [Open app] actions (tap to open = deep link to
      the exact tab's permission dialog)
    - `task_completed` / Claude `Stop` → info channel
    - `shell_command_long` (duration > user threshold) → info channel
    - `shell_command_error` → warning channel
    - `session_disconnected` → critical channel with [Reconnect] action
      and a distinct vibration pattern
- **Notification channels** (separately silenceable)
  - `termx.permission` (default: high priority, vibrate, light)
  - `termx.task` (default: default priority, silent)
  - `termx.error` (default: high priority)
  - `termx.disconnect` (default: high priority)
- **Battery optimization prompt**
  - On first connect, show a one-time sheet explaining Doze's effect on
    background tails → button requesting
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - Skippable; remembered in DataStore

**Exit**: open a tab, start a 5-minute `npm run build`, lock phone, put in
pocket → phone vibrates on completion, tap notification → opens the
exact tab with the completed output ready to read.

---

## Phase 8 — Polish → v1.0

**Goal.** Beta-ready to share publicly.

- **3-screen onboarding** (first launch)
  - Welcome (what termx is, one sentence)
  - Permissions explainer (mic, notifications, ignore-battery-opt, biometric)
  - Import existing SSH config (parse `~/.ssh/config` from a file pick)
    *or* generate new Ed25519 pair *or* skip
- **Config export/import**
  - Export → encrypted JSON with a user-chosen passphrase
  - Includes: servers, groups, alert rules, theme preferences, extra-keys
    layout
  - Excludes private keys unless the user opts into a second passphrase
    (protects against accidental leak)
  - Import via file picker or QR (for short configs, not key-including)
- **Theme editor**
  - Color picker for each of 16 ANSI colors + fg/bg/cursor
  - Preview pane with real ANSI escape sequences
  - Export/import as JSON
  - Custom themes sit alongside the 6 built-ins
- **Flip repo public + F-Droid**
  - Pre-check: `grep -r 'mkuchak@gmail.com\|secret\|TODO:\s*rem' .`
  - Switch repo visibility to public
  - F-Droid metadata: `metadata/en-US/` directory, short description,
    full description, screenshots, `metadata/en-US/changelogs/NNNNN.txt`
    per release
  - Reproducible build check — F-Droid will reject any non-reproducible
    AGP/R8 config
- **README upgrade** with screenshots, install instructions, threat model
- **Privacy policy** doc (no data collection, FCM is opt-in and self-host,
  Gemini API calls made with user's own key)
- **Version bump** 0.x → 1.0.0 (this bump happens via release-it as a
  semver `major` bump — the release-it default picks minor from `feat:`
  commits, so a `release-it -- --ci major` is the intentional call)

**Exit**: someone other than you can install from F-Droid, add a VPS,
authenticate with biometric, run Claude Code, approve tool calls from the
lock screen, and finish the flow without asking you for help.

---

## Cross-cutting concerns

- **Every phase produces a shippable APK.** Never break `main`.
- **Commit discipline.** release-it is driven by conventional-commits
  messages (`feat:` → minor, `fix:` → patch, `chore:`/`docs:`/`refactor:` →
  hidden). Stay disciplined or the changelog rots.
- **Branching.** Direct commits to `main` are fine through Phase 2 (solo
  velocity). From Phase 3 onward, use short-lived branches + self-reviewed
  PRs — catches dumb mistakes earlier and exercises the CI path.
- **Tests.** Write unit tests alongside each feature:
  - `:core:domain` use cases (ConnectUseCase, AlertRule regex, KeyPair gen)
  - `:libs:companion` NDJSON parser
  - Phase 5 diff renderer (golden-file tests for syntax highlighting)
  - Phase 6 Gemini retry logic (mocked HTTP)
  - Don't block MVP on coverage targets; aim for >50% on `:core:*` by v1.0.
- **Device testing.** Phase 1, 6, 7 must be validated on a real Android 13,
  14, 15 device before each phase's closing PR merges. Emulator doesn't
  exercise mic + foreground-service + Doze realistically.
- **The `~/.termx/` dir on the VPS is the source of truth** for session
  and event state. Any phone UI that implies otherwise is a bug.
- **No SaaS lock-in.** If a feature needs a server-side component (FCM
  relay, config sync), it must be buildable and runnable by users from
  the FOSS source. No proprietary server code. Ever.

---

## Explicitly deferred past v1.0

Not because they're unimportant — because they'd delay shipping and none
of them are structurally differentiating on their own:

- SFTP file browser (useful, but not on the Claude hot path)
- Overlay-bubble PTT (requires `SYSTEM_ALERT_WINDOW`; in-app FAB covers ~90%)
- Bluetooth hardware-button PTT (niche)
- On-device Whisper.cpp NDK fallback (Gemini cloud path works; offline is v1.1)
- FCM push relay (self-hosted reference impl in a separate repo post-v1.0)
- Multi-device config sync (needs the relay anyway)
- Play Store listing (F-Droid first; Play Store in v1.1 if appetite)
- Custom Claude slash-command surfacing row in extra-keys (could be neat
  later; not day-one)

---

## Working rhythm suggestion

- Pick the smallest completable slice per work session (half a day's worth)
- Commit with a conventional message
- `npm run release` only at phase boundaries — every patch bump mid-phase
  produces GitHub release noise
- When CI fails for a version-compat reason (like the v0.2.0 → v0.2.7
  chain), fix forward; don't reset history
- The ROADMAP.md is the source of truth for scope — edit it when priorities
  shift, don't rely on chat memory
