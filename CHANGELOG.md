# Changelog

## [1.1.12](https://github.com/mkuchak/termx/compare/v1.1.11...v1.1.12) (2026-04-26)

### Bug Fixes

* **terminal:** send CR not LF for PTT Send so the line actually executes ([88b17ee](https://github.com/mkuchak/termx/commit/88b17ee30db8546bf4fa7c648b603030ca3d0aa9))

## [1.1.11](https://github.com/mkuchak/termx/compare/v1.1.10...v1.1.11) (2026-04-26)

### ⚠ BREAKING CHANGES

* **ptt:** editable transcript + per-utterance Insert/Send buttons

### Features

* **ptt:** editable transcript + per-utterance Insert/Send buttons ([cd972c7](https://github.com/mkuchak/termx/commit/cd972c7dcf6b578a01ae480187d7ad7b37c341c0))

## [1.1.10](https://github.com/mkuchak/termx/compare/v1.1.9...v1.1.10) (2026-04-26)

### Bug Fixes

* **ptt:** keep FAB rendered through Recording so the gesture survives ([3467716](https://github.com/mkuchak/termx/commit/3467716d2bb1dbb775769a4bfdfec85508ff724d))

## [1.1.9](https://github.com/mkuchak/termx/compare/v1.1.8...v1.1.9) (2026-04-25)

### Features

* **ptt:** per-language transcribe/translate + retry + context ([0044083](https://github.com/mkuchak/termx/commit/00440839b923e6df862f9cff3702b742f0c470c5))

### Bug Fixes

* **ptt:** hold-to-record actually holds + reject silent-audio hallucinations ([431b3f6](https://github.com/mkuchak/termx/commit/431b3f69428f8c03242c734e627ac33cbba24af2))

## [1.1.8](https://github.com/mkuchak/termx/compare/v1.1.7...v1.1.8) (2026-04-25)

### Bug Fixes

* **vault:** drop write-side lock gate + bump auto-lock default to 24h ([5c06525](https://github.com/mkuchak/termx/commit/5c0652527419a5fad6c5d72ee788aa8f9b056d21))

## [1.1.7](https://github.com/mkuchak/termx/compare/v1.1.6...v1.1.7) (2026-04-24)

### Bug Fixes

* **auth:** stop nuking passwordAlias on blank-field edits + self-heal ([6ac4fab](https://github.com/mkuchak/termx/commit/6ac4fabbdcccddf3f5e2d5686b849008089cda8d))
* **vault:** drop AndroidKeystore, store vault as plaintext JSON in filesDir ([9e2f724](https://github.com/mkuchak/termx/commit/9e2f724cfd563627d0cbd6fcba9912c6fcfee3c4))

## [1.1.6](https://github.com/mkuchak/termx/compare/v1.1.5...v1.1.6) (2026-04-24)

### Features

* **mosh:** launch mosh-client under a pseudo-terminal (fixes tcgetattr) ([a964ce5](https://github.com/mkuchak/termx/commit/a964ce5c022eaabe1082148d64c0b18f6c29184f))

## [1.1.5](https://github.com/mkuchak/termx/compare/v1.1.4...v1.1.5) (2026-04-24)

### Bug Fixes

* **auth:** persist prompted password + migration + CryptoObject invariant ([b915e44](https://github.com/mkuchak/termx/commit/b915e44cf7d1dd77844b02c07d3667ea6b175888))
* **vault:** deterministic v2-alias migration + record lastConnected ([9c7226f](https://github.com/mkuchak/termx/commit/9c7226f3ec17883e580a08eeb5257eb8a7c420c9))

## [1.1.4](https://github.com/mkuchak/termx/compare/v1.1.3...v1.1.4) (2026-04-24)

### Bug Fixes

* **vault:** don't crash save when legacy-key migration misbehaves ([159ffe0](https://github.com/mkuchak/termx/commit/159ffe0b43bfdadc84ab515a237bc5cdbcf04ca4))

## [1.1.3](https://github.com/mkuchak/termx/compare/v1.1.2...v1.1.3) (2026-04-24)

### Bug Fixes

* **vault:** drop per-op user-auth from master key + auto-migrate legacy ([cb096b7](https://github.com/mkuchak/termx/commit/cb096b7ebf6dfb92a9be421a32d4e914d0c2fa2f))

## [1.1.2](https://github.com/mkuchak/termx/compare/v1.1.1...v1.1.2) (2026-04-24)

### Bug Fixes

* **terminal:** make keyboard toggle work on every tap ([3df64a6](https://github.com/mkuchak/termx/commit/3df64a6f437ff1a0f42f4e3fd98cfc779c76a4fb))

## [1.1.1](https://github.com/mkuchak/termx/compare/v1.1.0...v1.1.1) (2026-04-24)

### Bug Fixes

* **terminal:** pad status + nav bar insets and move keyboard toggle ([dad814a](https://github.com/mkuchak/termx/commit/dad814a4e1a83e71d716f96a9594a0d4245e6436))

## [1.1.0](https://github.com/mkuchak/termx/compare/v1.0.0...v1.1.0) (2026-04-24)

### Features

* **auth:** persist SSH passwords in Keystore vault + fix Step 3 race ([8c8be0a](https://github.com/mkuchak/termx/commit/8c8be0ac236e54968bca774897fcf15c7dccc240))
* **keyboard:** show IME on tap + adjustResize + toggle button in tab bar ([e4de44f](https://github.com/mkuchak/termx/commit/e4de44ff50a8f308bdf0bb10e43b146257d14548)), closes [#1](https://github.com/mkuchak/termx/issues/1)

### Bug Fixes

* **mosh:** bundle minimal terminfo DB + diagnostic logging on early exit ([0ffcc7a](https://github.com/mkuchak/termx/commit/0ffcc7a1b76ee6d6a61ad2c1878905bdad7ef2be))
* **terminal:** wire view invalidate on remote bytes + PtyChannel backpressure ([a49e890](https://github.com/mkuchak/termx/commit/a49e8909b62a40cea7f9381a320551e9dc98a48b))

## [1.0.0](https://github.com/mkuchak/termx/compare/v0.3.4...v1.0.0) (2026-04-24)

### Features

* **auth:** in-memory PasswordCache + terminal password prompt dialog ([2480c1b](https://github.com/mkuchak/termx/commit/2480c1bd3f67ecd3b05600a1f78ee3c6cce558b6)), closes [#23](https://github.com/mkuchak/termx/issues/23)
* **mosh:** connection wrapper with 8 s fallback to SSH ([3328e1f](https://github.com/mkuchak/termx/commit/3328e1fa5dd97780e882df6399318acc81ee8bd5))
* **mosh:** cross-compile mosh-client for arm64-v8a + armeabi-v7a ([bb1e2df](https://github.com/mkuchak/termx/commit/bb1e2dfa6dbf02782b4e6be6a21b4ef3c91504ac))
* **notifications:** event router + 4 channels + battery-opt prompt (Phase 7 complete) ([267baac](https://github.com/mkuchak/termx/commit/267baac7681be6d10158e7ad6267ff640b5ffce0))
* **phase5:** permission broker + diff capture + phone-side dialog + diff viewer ([befeced](https://github.com/mkuchak/termx/commit/befeced9423fb3ba7dc386b98203772a8978efd2)), closes [#35](https://github.com/mkuchak/termx/issues/35) [#36](https://github.com/mkuchak/termx/issues/36) [#37](https://github.com/mkuchak/termx/issues/37) [#38](https://github.com/mkuchak/termx/issues/38)
* **phase6:** push-to-talk with Gemini transcription + PTY injection ([66b90a7](https://github.com/mkuchak/termx/commit/66b90a7fff641d38d62d7655952282a7b496aeed))
* **phase8-polish:** onboarding + config sync + theme editor + F-Droid metadata ([3d3f57f](https://github.com/mkuchak/termx/commit/3d3f57ff8aaed2c320824ae6a5f99b7140c9ad33)), closes [#46](https://github.com/mkuchak/termx/issues/46) [#47](https://github.com/mkuchak/termx/issues/47) [#48](https://github.com/mkuchak/termx/issues/48) [#49](https://github.com/mkuchak/termx/issues/49)
* **polish:** tmux-aware scrollback + drag-reorder + Room Robolectric tests ([be5991b](https://github.com/mkuchak/termx/commit/be5991b8bd51e4e66027ad82e03b9f3f1cc4a2f6)), closes [#28](https://github.com/mkuchak/termx/issues/28) [#53](https://github.com/mkuchak/termx/issues/53) [#52](https://github.com/mkuchak/termx/issues/52)
* **service:** foreground service + session registry + persistent notification ([9f50ed2](https://github.com/mkuchak/termx/commit/9f50ed2f36d46aac393eccb709802f914dc5f053)), closes [#44](https://github.com/mkuchak/termx/issues/44)

### Bug Fixes

* **phase8-polish:** add onboarding_complete preference key/default for AppPreferences ([acd7d0e](https://github.com/mkuchak/termx/commit/acd7d0eab9549d77369514d50947147c74565c7c))
* **release:** point release:termxd at termxd/package.json ([343b71a](https://github.com/mkuchak/termx/commit/343b71afc31fe0d591e186c4fb4a805f3a9c6041))
* **termxd:** shell hooks leak job notifications + trap every .bashrc statement ([36e82ac](https://github.com/mkuchak/termx/commit/36e82ac89577f87cf569ad8058c0d8dbe0e08555))

## [0.3.4](https://github.com/mkuchak/termx/compare/v0.3.3...v0.3.4) (2026-04-24)

### Bug Fixes

* **wizard+tmux:** prevent uncaught exceptions from crashing the Activity ([f2623c0](https://github.com/mkuchak/termx/commit/f2623c00927d27651b90cb25f675ff82644ef8e8))

## [0.3.3](https://github.com/mkuchak/termx/compare/v0.3.2...v0.3.3) (2026-04-24)

### Bug Fixes

* **ci:** copy binaries from per-target subdirs before gh release create ([8c60e15](https://github.com/mkuchak/termx/commit/8c60e159e18079a265c18cbdb7095ebca3c93b5e))
* **ci:** create local semver tag so GoReleaser accepts the override ([79d99bd](https://github.com/mkuchak/termx/commit/79d99bda3d188f0d9817c5ff81a48f727c0cba51))
* **ci:** publish termxd release under termxd-v tag, not GoReleaser's semver override ([edb6656](https://github.com/mkuchak/termx/commit/edb6656e494d22c38a79b0fb37098f71d3727113))
* **ci:** use release.disable=true so archives still land in dist root ([638d1b7](https://github.com/mkuchak/termx/commit/638d1b766bacd45bea5c56582691cce070d0277e))
* **ssh-native:** remove deadlocking awaitClose from PTY/exec stream flows ([c7ea47b](https://github.com/mkuchak/termx/commit/c7ea47b8bf389286b66a77302b4fac1f91da8c84))
* **wizard:** stage-aware SSH timeouts + concurrent stderr drain + SFTP op timeout ([0513c32](https://github.com/mkuchak/termx/commit/0513c322b4fbe27d06702374ca322de6ede575a5))

## [0.3.2](https://github.com/mkuchak/termx/compare/v0.3.1...v0.3.2) (2026-04-24)

### Bug Fixes

* **release:** guard pre-release checklist prompt against non-interactive stdin ([b90e47f](https://github.com/mkuchak/termx/commit/b90e47fa4124c6a283f5df877fa2c93271948c41))
* **wizard:** wire password auth through termxd install flow ([bfa4364](https://github.com/mkuchak/termx/commit/bfa4364cb80f88cca83adbf07ac7e6456dc0d901))

## [0.3.1](https://github.com/mkuchak/termx/compare/v0.3.0...v0.3.1) (2026-04-24)

### Bug Fixes

* **ssh-native:** replace Android's stripped BC with full bcprov at init ([d97c904](https://github.com/mkuchak/termx/commit/d97c9044217b6751391e9cd65b1c91023fe4a48c))

## [0.3.0](https://github.com/mkuchak/termx/compare/v0.2.7...v0.3.0) (2026-04-24)

### Features

* **companion:** tail events.ndjson + session registry via SFTP + command writer ([eba1087](https://github.com/mkuchak/termx/commit/eba10875f4a2c4fd00479a49f1766338bd2076d6))
* **core/data:** add EventStreamRepository as Hilt entry point to :libs:companion ([00ac136](https://github.com/mkuchak/termx/commit/00ac13667955ffdde3bbb59311ad9edb732b74cb))
* **core/data:** add Room DB schema for Server, KeyPair, ServerGroup + repository layer ([efa2bee](https://github.com/mkuchak/termx/commit/efa2bee647b4e9c1754e15f84294ca5c0451a722)), closes [#51](https://github.com/mkuchak/termx/issues/51) [#52](https://github.com/mkuchak/termx/issues/52)
* **keys:** SSH key generation, OpenSSH import, QR export ([9cec7d4](https://github.com/mkuchak/termx/commit/9cec7d412fbc38b90e49325e11d15df9f768086e)), closes [PKCS#1](https://github.com/mkuchak/PKCS/issues/1) [PKCS#1](https://github.com/mkuchak/PKCS/issues/1) [PKCS#8](https://github.com/mkuchak/PKCS/issues/8)
* **libs/companion:** add events.ndjson schema + streaming parser ([e074258](https://github.com/mkuchak/termx/commit/e074258e56cb18d1b4990cdc427d9e0092f2fa01))
* **servers:** 5-step setup wizard with guided onboarding ([751b9b3](https://github.com/mkuchak/termx/commit/751b9b3e5fa3fa1570ed16e53394035a49b96d1c)), closes [#33](https://github.com/mkuchak/termx/issues/33)
* **servers:** add/edit server bottom sheet with connection test ([94cee1c](https://github.com/mkuchak/termx/commit/94cee1c86de3f118e069ca03d014e3d87712fd5f)), closes [#22](https://github.com/mkuchak/termx/issues/22) [#20](https://github.com/mkuchak/termx/issues/20) [#23](https://github.com/mkuchak/termx/issues/23) [#21](https://github.com/mkuchak/termx/issues/21)
* **servers:** server list screen with groups, swipe-delete undo, reorder ([995eeb3](https://github.com/mkuchak/termx/commit/995eeb370cf0e6f9688c597c405dd3629fb3b7d7)), closes [#22](https://github.com/mkuchak/termx/issues/22) [#53](https://github.com/mkuchak/termx/issues/53) [#23](https://github.com/mkuchak/termx/issues/23) [#23](https://github.com/mkuchak/termx/issues/23) [#32](https://github.com/mkuchak/termx/issues/32)
* **ssh-native:** add SshClient wrapper around sshj with PTY/exec/SFTP channels ([6072b7f](https://github.com/mkuchak/termx/commit/6072b7f5d3d11838f1057d4e78c3825ec521489a))
* **terminal-view:** fork Termux terminal-view + terminal-emulator ([d83fa4d](https://github.com/mkuchak/termx/commit/d83fa4d9ba2cf8a03e8a264129524c09f4729b98))
* **terminal:** extra-keys toolbar with sticky modifiers + Volume-Down=Ctrl ([f8df51c](https://github.com/mkuchak/termx/commit/f8df51c07af33913ace610aa36fe7a8004e7710d))
* **terminal:** multi-session tab bar with activity flash + rename/kill/detach ([51f805f](https://github.com/mkuchak/termx/commit/51f805f3c03dcff87af7299d1b7c9012d3cce95b))
* **terminal:** pinch-zoom + URL tap gestures + 6-theme pack with live switcher ([089c2a3](https://github.com/mkuchak/termx/commit/089c2a30986795301865fc4c1cb10d427c5db51c)), closes [#17](https://github.com/mkuchak/termx/issues/17) [#18](https://github.com/mkuchak/termx/issues/18)
* **terminal:** wire TerminalScreen composable to sshj PTY channel ([cac0208](https://github.com/mkuchak/termx/commit/cac020860494581ccb128f2951cbbe8dd3baf9cb))
* **termxd:** add install + uninstall commands with marked-block idempotency ([8316090](https://github.com/mkuchak/termx/commit/83160909e946f61bd811a6cacebcc72006ebb644)), closes [#30](https://github.com/mkuchak/termx/issues/30)
* **termxd:** scaffold Go CLI + GoReleaser + CI workflow ([5693066](https://github.com/mkuchak/termx/commit/5693066a00c2e07e8962a79cca74a22b000d678d))
* **termxd:** tmux + shell hooks with smart-filtered events ([1490b02](https://github.com/mkuchak/termx/commit/1490b02a100b97d9718116d738f92345bedf2e81))
* **tmux:** auto-attach on connect + TmuxSessionRepository polling ([31f02d4](https://github.com/mkuchak/termx/commit/31f02d49cafd340eda6ba2e9b341fe763d80cb20)), closes [#26](https://github.com/mkuchak/termx/issues/26)
* **vault:** add Keystore-encrypted SecretVault + biometric unlock flow ([12ff934](https://github.com/mkuchak/termx/commit/12ff93487d180e0b21f88164fa120a8db1877986)), closes [#20](https://github.com/mkuchak/termx/issues/20) [#19](https://github.com/mkuchak/termx/issues/19)
* **wizard:** termxd install step with dry-run diff + live log ([b96f3d9](https://github.com/mkuchak/termx/commit/b96f3d953b0d59a062cb7c82662082c0d226e0b3))

### Bug Fixes

* **app:** add lifecycle-process dep + drop missing SettingsScreen route ([2866bb1](https://github.com/mkuchak/termx/commit/2866bb17603098541d94430b93a21046e83d6d85))
* **app:** declare VIBRATE permission for terminal bell haptic feedback ([43d078b](https://github.com/mkuchak/termx/commit/43d078b300ddaddc95ba8818e1a887c52421a49b))
* **core/data:** drop `internal` from repo impls so Hilt @Binds can bind them ([0fde836](https://github.com/mkuchak/termx/commit/0fde836bbbea68147ba2ee2212ba377760deeefb))
* **core/domain:** rephrase KDoc to avoid nested /* from glob path ([9b62410](https://github.com/mkuchak/termx/commit/9b62410e59965e1e87390d29d17384096d27fd83))
* **core:** finish orphaned tab-bar interface + expose vault state setters ([d72fed8](https://github.com/mkuchak/termx/commit/d72fed8c616887b949bbc48b502f696e0a129f59))
* **keys:** drop MIME glob `*/*` from KDoc to avoid closing outer comment ([9e582ed](https://github.com/mkuchak/termx/commit/9e582ed44f8917f7ad6e9716218f8164d36b915b))
* **ssh-native:** close unterminated KDoc + fix CompletableDeferred.cancel arg ([50e75b7](https://github.com/mkuchak/termx/commit/50e75b7634ffecf004684e28cc02b0dc2b35dd86))
* **terminal-view:** add androidx.annotation dep for @Nullable / @RequiresApi ([542f126](https://github.com/mkuchak/termx/commit/542f1262f3d0dd84f93537d3dd3aa6dc45036d11))
* **terminal:** add xmlns:android to manifest for attribute parsing ([b508384](https://github.com/mkuchak/termx/commit/b508384825635c713606bcb56e2dec29756b30f1))
* **terminal:** declare VIBRATE in feature manifest so lint sees it ([cb7a9ae](https://github.com/mkuchak/termx/commit/cb7a9aea57db203fdf72dc71431e569a5a0a3508))
* **terminal:** drop invalid weight import (extension on RowScope/ColumnScope) ([ef89036](https://github.com/mkuchak/termx/commit/ef8903675aa4e9fed18a4fbb514d452c353bb852))

## [0.2.7](https://github.com/mkuchak/termx/compare/v0.2.6...v0.2.7) (2026-04-23)

### Bug Fixes

* align Java target to 21 to match Kotlin 2.2 default ([47253f9](https://github.com/mkuchak/termx/commit/47253f96c3287e7ec01d45f8ad38be3c7e3549e6))

## [0.2.6](https://github.com/mkuchak/termx/compare/v0.2.5...v0.2.6) (2026-04-23)

### Bug Fixes

* pin Hilt to 2.58 for AGP 8.x compatibility ([13e9526](https://github.com/mkuchak/termx/commit/13e9526fde3c5d03f384335d48c3b419839bdb82))

## [0.2.5](https://github.com/mkuchak/termx/compare/v0.2.4...v0.2.5) (2026-04-23)

### Bug Fixes

* bump Hilt 2.53 -> 2.59.2 for Kotlin 2.2 compatibility ([ff9eacb](https://github.com/mkuchak/termx/commit/ff9eacb5f71585efbd503b74ee8bfd231b56ae37))

## [0.2.4](https://github.com/mkuchak/termx/compare/v0.2.3...v0.2.4) (2026-04-23)

### Bug Fixes

* bump compileSdk + targetSdk from 34 to 35 ([6030f53](https://github.com/mkuchak/termx/commit/6030f53639d856af97721bda542bae35664e65d1))

## [0.2.3](https://github.com/mkuchak/termx/compare/v0.2.2...v0.2.3) (2026-04-23)

### Bug Fixes

* Kotlin 2.2 removed kotlinOptions DSL; add Base64 import ([3cbf61b](https://github.com/mkuchak/termx/commit/3cbf61b66042eda3b51ea486814b46321ab6681f))

## [0.2.2](https://github.com/mkuchak/termx/compare/v0.2.1...v0.2.2) (2026-04-23)

### Bug Fixes

* bump Gradle wrapper to 8.14.4 for AGP 8.12 compatibility ([95b7a2f](https://github.com/mkuchak/termx/commit/95b7a2fb38c54786ef5750f9a9370f2a59fe3a52))

## [0.2.1](https://github.com/mkuchak/termx/compare/v0.2.0...v0.2.1) (2026-04-23)

### Bug Fixes

* correct KSP version to 2.2.10-2.0.2 ([2deb99f](https://github.com/mkuchak/termx/commit/2deb99f9118484ba6931f2e0e1d1f640675ef30f))

## 0.2.0 (2026-04-23)

### Features

* bootstrap termx Android Kotlin Compose skeleton with CI + release-it ([67709ce](https://github.com/mkuchak/termx/commit/67709ceaf33af2c190d99d25fff20805d259ec2a))
