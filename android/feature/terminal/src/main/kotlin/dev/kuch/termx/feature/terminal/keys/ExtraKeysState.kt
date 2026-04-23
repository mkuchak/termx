/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.keys

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Sticky-modifier tri-state per ROADMAP §1.5.
 *
 *  - [Off]      — modifier does not apply
 *  - [OneShot]  — single tap; applies to the next key and auto-clears
 *  - [Locked]   — double-tap; applies to every subsequent key until tapped off
 */
enum class ModifierState { Off, OneShot, Locked }

/**
 * Compose-friendly state holder for the extra-keys toolbar.
 *
 * The composable drives the state transitions; the view-model does not
 * need to know about sticky modifiers at all. See [ExtraKeysBar] for the
 * double-tap debouncer that promotes OneShot → Locked.
 */
@Stable
class ExtraKeysState(
    initialCtrl: ModifierState = ModifierState.Off,
    initialAlt: ModifierState = ModifierState.Off,
) {
    var ctrl: ModifierState by mutableStateOf(initialCtrl)
    var alt: ModifierState by mutableStateOf(initialAlt)

    /**
     * Single tap — the composable's debouncer promotes to Locked if a
     * second tap lands within the double-tap window.
     */
    fun tapCtrlOnce() {
        ctrl = when (ctrl) {
            ModifierState.Off -> ModifierState.OneShot
            ModifierState.OneShot -> ModifierState.OneShot // held for the window
            ModifierState.Locked -> ModifierState.Off
        }
    }

    fun tapAltOnce() {
        alt = when (alt) {
            ModifierState.Off -> ModifierState.OneShot
            ModifierState.OneShot -> ModifierState.OneShot
            ModifierState.Locked -> ModifierState.Off
        }
    }

    /** Called by the composable's debouncer when a second tap lands fast. */
    fun lockCtrl() { ctrl = ModifierState.Locked }
    fun lockAlt() { alt = ModifierState.Locked }

    /**
     * Clear any OneShot modifiers after a key has been emitted. Locked
     * modifiers persist until explicitly tapped off.
     */
    fun resetOneShots() {
        if (ctrl == ModifierState.OneShot) ctrl = ModifierState.Off
        if (alt == ModifierState.OneShot) alt = ModifierState.Off
    }

    /** `true` if the modifier should apply to the next key press. */
    val ctrlActive: Boolean get() = ctrl != ModifierState.Off
    val altActive: Boolean get() = alt != ModifierState.Off
}

@Composable
fun rememberExtraKeysState(): ExtraKeysState = remember { ExtraKeysState() }
