/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 *
 * This file lives under the com.termux.terminal package intentionally so it
 * can touch the package-private mEmulator field on TerminalSession. It does
 * NOT carry Termux's Apache-2.0 header because it is new code written for
 * termx; the Apache-2.0 files it sits next to keep theirs.
 */
package com.termux.terminal

import android.os.Handler
import android.os.Looper

/**
 * A [TerminalSession] whose I/O is proxied through an sshj PTY channel
 * instead of a local JNI-spawned subprocess.
 *
 * Termux's stock [TerminalSession.initializeEmulator] creates a local PTY via
 * `JNI.createSubprocess(...)` and spins up three threads to shuttle bytes
 * between the shell process and the emulator. We want none of that — the
 * shell lives on the VPS, sshj owns the transport, and we only need the
 * [TerminalEmulator] half of the stack on-device.
 *
 * This subclass therefore:
 *  1. Overrides [initializeEmulator] to just construct the [TerminalEmulator]
 *     (no JNI, no worker threads, `mShellPid` stays 0 so [isRunning] returns
 *     `true` for as long as we haven't finished). Window resize is forwarded
 *     to the caller-supplied [onResize] hook so the ssh session can send
 *     SIGWINCH to the remote pty.
 *  2. Overrides [write] (inherited from TerminalOutput) so keypresses /
 *     paste / `writeCodePoint` output get routed to [onInputBytes] instead
 *     of enqueued for a local writer thread that would never drain.
 *  3. Exposes [feedRemoteBytes] so the collector of the sshj output Flow can
 *     push received bytes into the emulator on the main thread.
 *
 * Thread model: like stock TerminalSession, all emulator mutation happens on
 * the Android main thread. [feedRemoteBytes] may be called from any thread
 * (it posts to the main-thread handler internally). [write] may be called
 * from the main thread (keyboard, view clients) — we hand the bytes back to
 * [onInputBytes], which callers typically bounce onto a coroutine.
 */
class RemoteTerminalSession(
    client: TerminalSessionClient,
    transcriptRows: Int? = null,
    private val onInputBytes: (ByteArray) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit,
) : TerminalSession(
    /* shellPath      = */ "",
    /* cwd            = */ "",
    /* args           = */ emptyArray<String>(),
    /* env            = */ emptyArray<String>(),
    /* transcriptRows = */ transcriptRows,
    /* client         = */ client,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sessionFinished = false

    /**
     * Bytes received via [feedRemoteBytes] BEFORE [initializeEmulator]
     * has run. SSH doesn't currently trip this race (the sshj connect
     * handshake takes 1–3 s, AndroidView lays out long before any
     * remote byte arrives), but the symmetric mosh transport DID hit
     * it in v1.1.21 — see [MoshRemoteTerminalSession.pendingBytes].
     * Mirrored here defensively so a future faster transport (or an
     * unusually quick `motd` print) doesn't silently lose data.
     */
    private val pendingBytes = ArrayDeque<ByteArray>()

    override fun initializeEmulator(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
    ) {
        // Construct the emulator; writes from the emulator back to "the
        // shell" (for cursor reports, OSC replies, etc.) flow through
        // TerminalSession.write → our override → onInputBytes.
        mEmulator = TerminalEmulator(
            this,
            columns,
            rows,
            cellWidthPixels,
            cellHeightPixels,
            /* transcriptRows = */ null,
            mClient,
        )
        onResize(columns, rows)
        if (pendingBytes.isNotEmpty()) {
            val emulator = mEmulator
            for (chunk in pendingBytes) {
                emulator.append(chunk, chunk.size)
            }
            pendingBytes.clear()
            notifyScreenUpdate()
        }
    }

    override fun updateSize(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
    ) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
            onResize(columns, rows)
        }
    }

    /** Route all outbound bytes (keypresses + emulator responses) to the remote PTY. */
    override fun write(data: ByteArray?, offset: Int, count: Int) {
        if (data == null || count <= 0 || sessionFinished) return
        val slice = if (offset == 0 && count == data.size) data else data.copyOfRange(offset, offset + count)
        onInputBytes(slice)
    }

    /**
     * Push bytes received from the remote shell into the emulator. Safe to
     * call from any thread — the append happens on the main thread.
     */
    fun feedRemoteBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || sessionFinished) return
        mainHandler.post {
            val emulator = mEmulator
            if (emulator == null) {
                pendingBytes.addLast(bytes)
                return@post
            }
            emulator.append(bytes, bytes.size)
            notifyScreenUpdate()
        }
    }

    /** Called by the ViewModel when the sshj channel closes or errors out. */
    fun onRemoteSessionClosed() {
        if (sessionFinished) return
        sessionFinished = true
        mainHandler.post {
            mClient.onSessionFinished(this)
        }
    }

    /** No local PID, so killing it is a no-op. */
    override fun finishIfRunning() {
        onRemoteSessionClosed()
    }

    /** The session is "running" until the remote channel closes. */
    @Synchronized
    override fun isRunning(): Boolean = !sessionFinished

    /**
     * Expose [mClient] cast to its concrete type so the Compose host can
     * wire / unwire the live [com.termux.view.TerminalView] reference that
     * [dev.kuch.termx.feature.terminal.SshSessionClient.onTextChanged]
     * uses to trigger a repaint on every emulator append.
     *
     * Returns null if, for some reason, a non-SshSessionClient is wired
     * — tests may use a bare TerminalSessionClient and we don't want to
     * crash on cast.
     */
    fun sessionClient(): dev.kuch.termx.feature.terminal.SshSessionClient? =
        mClient as? dev.kuch.termx.feature.terminal.SshSessionClient
}
