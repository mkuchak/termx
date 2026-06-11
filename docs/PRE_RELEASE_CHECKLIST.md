# Pre-release smoke checklist

Before running `npm run release`, install the **debug** APK on your phone and walk through every flow below. Each box you can't tick is a bug to fix *before* tagging.

Artifact to test: latest green `android-debug.yml` run —

```
gh run download <id> --name termx-debug-<sha> --dir /tmp && adb install /tmp/app-debug.apk
```

## 1. BouncyCastle registers on cold start (key auth)
- Click-path: fresh install (`adb uninstall dev.kuch.termx` first) then open the app. Server list to Add. Pick a saved Ed25519 key or generate one inline. Save then Test connection.
- Expected: "Connected successfully" green check within 3 s.
- Would have caught: v0.3.1 X25519 regression (commit `d97c904`).

## 2. BouncyCastle registers on cold start (password auth)
- Click-path: Add server, switch auth to Password, enter a real VPS password, Test connection.
- Expected: green check. No "no such algorithm: X25519" message in `adb logcat`.
- Would have caught: v0.3.1 again (password flow exercises the same BC path).

## 3. Wizard Step 3 reaches ReadyToDownload with password auth
- Click-path: Server list to `+` setup wizard. Fill Step 1 with password auth. Test passes in Step 2. Advance to Step 3.
- Expected: Step 3 transitions from Detecting to ReadyToDownload (or AlreadyInstalled). No "Password auth isn't wired" error.
- Would have caught: post-v0.3.1 install regression (commit `bfa4364`).

## 4. Wizard Step 3 Retry actually retries
- Click-path: Force a failure in Step 3 (e.g. temporarily block the VPS's outbound `api.github.com`). Hit `Retry`.
- Expected: UI visibly flips to Detecting then to the next state (new Error or progress). Not a silent no-op.

## 5. Terminal connects and renders a live prompt
- Click-path: Server list, tap a saved key-auth server.
- Expected: bash prompt within 3 s. `ls` returns a directory listing.

## 6. Plain-shell connect / disconnect / reconnect
- Click-path: Connect to a saved server. Run `echo phone-a >> /tmp/marker`. Go back (close the tab). Connect again.
- Expected: a fresh plain login shell each time — the prompt appears, `cat /tmp/marker` still shows the line you wrote (the file persists on the VPS), and there are no orphaned termx-spawned multiplexer sessions. termx attaches you to whatever your login shell starts; it does not auto-launch tmux.

## 7. Font pinch-zoom persists across restart
- Click-path: Inside terminal, pinch-zoom to a noticeably different size. Force-stop the app. Reopen and reconnect.
- Expected: terminal font size matches the post-pinch size, not the 14 sp default.

## 8. Sorcerer theme renders across terminal + UI
- Click-path: Open the terminal, then visit Settings and a Card-heavy screen (e.g. the diff viewer or a transcribing card).
- Expected: the Sorcerer palette is applied everywhere — near-black canvas, pink accent, cyan/lime highlights; no stock Material gray cards. There is no theme picker (termx ships only Sorcerer).

## 9. Release APK installs + launches cleanly
- Click-path: download the `android-release.yml` signed APK, `adb install -r app-release.apk`, open once.
- Expected: first Compose frame renders without `Missing class`, `ClassNotFoundException`, or R8-related crashes in `adb logcat`.
- Would have caught: v0.3.0 R8 shrink regressions.

## 10. Permission broker round-trip (phone → VPS)
- Click-path: with the companion installed, run a gated tool (e.g. `Bash`) in Claude Code on the VPS → approve from the phone notification → the tool executes. Repeat approving from the in-app dialog. Repeat with Deny → Claude shows the denial reason. Then "Always approve" → the rule lands in `~/.termx/allowlist.txt` and the next identical call auto-approves silently (no notification).
- Expected: every decision resolves well within the hook's 30 s window; no default-deny while you are actively responding.
- Would have caught: the broker gap where the phone wrote decisions to a path nothing read, so every gated tool call default-denied after 30 s.

## 11. Keyboard focus & input (type immediately, no repair tap)
- Click-path: open a session with the soft keyboard already visible and type at once — characters must appear with no prior tap on the terminal. Then re-test typing immediately after each focus-stealing flow: minimize the sheet and re-maximize; long-press the keyboard chip → compose card → dismiss with ✕ (then once more, ending with Send); double-tap a URL in the scrollback → dismiss the open-link dialog. Finally run `sleep 100`, hold Volume-Down and press `c`.
- Expected: every keystroke lands in the terminal instantly after every step — never a dead keyboard that needs a repair tap first — and Vol-Down+C delivers Ctrl+C (the `sleep` dies with `^C`).
- Would have caught: the 2026-06-11 tap-before-type bug (a Compose focus thief grabbed focus from the TerminalView on session open and after every card/dialog dismissal). The Vol-Down step doubles as a regression guard for the fix itself: Vol-Down-as-Ctrl now routes solely through TerminalView's `setOnKeyListener`, so it silently breaks if the view ever stops holding focus.

## 12. PTT Send drives Claude Code's composer (two-phase submit)
- Click-path: connect to a server and start Claude Code in the shell. (a) PTT a short phrase (<10 words) → Send. (b) PTT a LONG transcription (2+ sentences, well over 64 characters) → Send. (c) PTT a multi-sentence ramble and let the Ready card show several lines → Send. (d) Repeat (b) on a mosh-backed session AND an ssh-backed one (the lone-CR delay differs: 300 ms vs 75 ms). (e) Exit Claude Code to the plain bash prompt, PTT `echo hello` → Send.
- Expected: in (a)–(d) the text appears in Claude's composer AND submits on its own — no manual Enter, exactly one submission per Send, never a mid-text submit. In (e) the line executes normally with NO visible `ESC[200~`/`ESC[201~` marker garbage at the prompt (the bracketed-paste wrap must be gated on the remote app actually enabling DECSET 2004).
- Would have caught: the 2026-06-11 "transcript pastes but never submits" bug — Claude Code's stdin tokenizer only treats `\r` as the Enter key in chunks <64 chars, so the old one-buffer `text+"\r"` send failed on every realistic transcript. v1.3.3 caught only the FIRST layer of this symptom (exotic Unicode line breaks); steps (b)–(d) exercise the second layer (`ConnectionManager.submitLine`'s two-phase bracketed-paste + delayed lone CR).

## 13. Biometric vault re-lock (quarterly spot-check)
- Click-path: Unlock via biometric. Background the app for 6 min. Foreground.
- Expected: biometric prompt reappears before the server list is accessible.
- Cadence: quarterly — skip on every release; otherwise this doc bloats.
