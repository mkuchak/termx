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

## 10. Biometric vault re-lock (quarterly spot-check)
- Click-path: Unlock via biometric. Background the app for 6 min. Foreground.
- Expected: biometric prompt reappears before the server list is accessible.
- Cadence: quarterly — skip on every release; otherwise this doc bloats.
