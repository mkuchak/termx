# Changelog

## [0.1.1](https://github.com/mkuchak/termx/compare/termxd-v0.1.0...termxd-v0.1.1) (2026-04-24)

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

## [0.3.4](https://github.com/mkuchak/termx/compare/termxd-v0.1.0...termxd-v0.1.1) (2026-04-24)

### Bug Fixes

* **wizard+tmux:** prevent uncaught exceptions from crashing the Activity ([f2623c0](https://github.com/mkuchak/termx/commit/f2623c00927d27651b90cb25f675ff82644ef8e8))

## [0.3.3](https://github.com/mkuchak/termx/compare/termxd-v0.1.0...termxd-v0.1.1) (2026-04-24)

### Bug Fixes

* **wizard:** finish openSession signature — earlier edit landed call sites only ([467d957](https://github.com/mkuchak/termx/commit/467d9570131549b6ef6b1a8637a2296dae5ddb1f))
