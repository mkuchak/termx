# Changelog

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
