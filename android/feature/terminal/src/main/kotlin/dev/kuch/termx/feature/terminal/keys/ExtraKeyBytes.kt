/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.keys

import android.view.KeyEvent
import com.termux.terminal.KeyHandler

/**
 * Pure encoder: `(key, ctrl, alt)` → bytes to write into the PTY.
 *
 * For keys with well-known escape sequences (arrows, F-keys, Home/End,
 * PgUp/PgDown) we delegate to [KeyHandler.getCode], Termux's
 * authoritative implementation — it already handles xterm-modifier
 * encoding (`ESC[1;5A` for Ctrl+Up, etc.) and the application/cursor
 * mode distinction.
 *
 * For literal characters and the three top-row keys (ESC/TAB/Enter) we
 * roll our own short forms; delegating those to KeyHandler would require
 * a keycode lookup table for every printable ASCII char.
 *
 * Cursor-application and keypad-application modes are left at `false`
 * here because the toolbar has no access to the live emulator state.
 * If the user is in `vim` or another cursor-app-mode program, the
 * emulator will re-translate on the server side anyway; for 99% of
 * interactive shells, the non-application sequences are what's expected.
 */
object ExtraKeyBytes {

    private const val ESC: Byte = 0x1B
    private const val TAB: Byte = 0x09
    private const val CR: Byte = 0x0D

    fun encode(key: ExtraKey, ctrl: Boolean, alt: Boolean): ByteArray {
        return when (key) {
            ExtraKey.Escape -> byteArrayOf(ESC)
            ExtraKey.Tab -> byteArrayOf(TAB)
            ExtraKey.Enter -> if (alt) byteArrayOf(ESC, CR) else byteArrayOf(CR)

            // CTRL / ALT don't emit anything on their own tap — they're
            // sticky modifiers consumed by the NEXT key press.
            ExtraKey.Ctrl, ExtraKey.Alt -> ByteArray(0)

            is ExtraKey.Arrow -> {
                val keyCode = when (key.dir) {
                    ArrowDir.Up -> KeyEvent.KEYCODE_DPAD_UP
                    ArrowDir.Down -> KeyEvent.KEYCODE_DPAD_DOWN
                    ArrowDir.Left -> KeyEvent.KEYCODE_DPAD_LEFT
                    ArrowDir.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
                }
                fromKeyHandler(keyCode, ctrl, alt)
            }

            ExtraKey.Home -> fromKeyHandler(KeyEvent.KEYCODE_MOVE_HOME, ctrl, alt)
            ExtraKey.End -> fromKeyHandler(KeyEvent.KEYCODE_MOVE_END, ctrl, alt)
            ExtraKey.PageUp -> fromKeyHandler(KeyEvent.KEYCODE_PAGE_UP, ctrl, alt)
            ExtraKey.PageDown -> fromKeyHandler(KeyEvent.KEYCODE_PAGE_DOWN, ctrl, alt)

            is ExtraKey.Fn -> {
                val keyCode = when (key.n) {
                    1 -> KeyEvent.KEYCODE_F1
                    2 -> KeyEvent.KEYCODE_F2
                    3 -> KeyEvent.KEYCODE_F3
                    4 -> KeyEvent.KEYCODE_F4
                    5 -> KeyEvent.KEYCODE_F5
                    6 -> KeyEvent.KEYCODE_F6
                    7 -> KeyEvent.KEYCODE_F7
                    8 -> KeyEvent.KEYCODE_F8
                    9 -> KeyEvent.KEYCODE_F9
                    10 -> KeyEvent.KEYCODE_F10
                    11 -> KeyEvent.KEYCODE_F11
                    12 -> KeyEvent.KEYCODE_F12
                    else -> return ByteArray(0)
                }
                fromKeyHandler(keyCode, ctrl, alt)
            }

            is ExtraKey.Char -> encodeChar(key.c, ctrl, alt)
        }
    }

    /**
     * Call through to Termux's [KeyHandler.getCode] with the right
     * modifier mask. Returns an empty array if the lookup fails (should
     * not happen for the handful of keycodes we send in here).
     */
    private fun fromKeyHandler(keyCode: Int, ctrl: Boolean, alt: Boolean): ByteArray {
        var mask = 0
        if (ctrl) mask = mask or KeyHandler.KEYMOD_CTRL
        if (alt) mask = mask or KeyHandler.KEYMOD_ALT
        val seq = KeyHandler.getCode(
            keyCode,
            mask,
            /* cursorApp = */ false,
            /* keypadApplication = */ false,
        ) ?: return ByteArray(0)
        return seq.toByteArray(Charsets.UTF_8)
    }

    /**
     * Encode a literal character, optionally with Ctrl and/or Alt.
     *
     *  - Ctrl+letter (a..z / A..Z) → POSIX control code 0x01..0x1A
     *  - Ctrl+special (like `[`, `\`, `]`, `^`, `_`) → standard mapping
     *  - Alt+anything → prefix with ESC (0x1B) per xterm convention
     *  - No modifiers → literal UTF-8 bytes
     */
    private fun encodeChar(c: Char, ctrl: Boolean, alt: Boolean): ByteArray {
        val controlByte: Byte? = if (ctrl) controlByteFor(c) else null
        val body: ByteArray = if (controlByte != null) {
            byteArrayOf(controlByte)
        } else {
            c.toString().toByteArray(Charsets.UTF_8)
        }
        return if (alt) byteArrayOf(ESC) + body else body
    }

    private fun controlByteFor(c: Char): Byte? {
        val lower = c.lowercaseChar()
        return when {
            lower in 'a'..'z' -> (lower.code - 'a'.code + 1).toByte()
            c == '@' -> 0x00.toByte()
            c == '[' -> 0x1B.toByte()
            c == '\\' -> 0x1C.toByte()
            c == ']' -> 0x1D.toByte()
            c == '^' -> 0x1E.toByte()
            c == '_' -> 0x1F.toByte()
            c == ' ' -> 0x00.toByte()
            c == '?' -> 0x7F.toByte()
            else -> null // Ctrl+digit / Ctrl+symbol without a canonical mapping: pass through raw.
        }
    }
}
