/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 *
 * Sibling of [RemoteTerminalSession] for the mosh transport. Lives in the
 * com.termux.terminal package so it can touch the package-private
 * mEmulator field inherited from TerminalSession.
 */
package com.termux.terminal

import android.os.Handler
import android.os.Looper

/**
 * A [TerminalSession] driven by a local mosh-client child process.
 *
 * Structurally identical to [RemoteTerminalSession] — same override
 * pattern for [initializeEmulator] / [updateSize] / [write], same
 * [feedRemoteBytes] / [onRemoteSessionClosed] hooks. The only reason
 * it exists as a second class is to keep the two transport adapters
 * readable side-by-side while we iterate on Phase 3; if the pair
 * grows a third sibling we'll lift the shared surface into a small
 * abstract base (see the Task #27 writeup). For now a near-duplicate
 * is cheaper to reason about than premature generalization.
 *
 * [onInputBytes] is fed straight into the mosh-client stdin; mosh-client
 * handles the local echo + server echo reconciliation on its end.
 * [onResize] fires on geometry changes and maps to a SIGWINCH on the
 * mosh-client PID, which propagates a window-change frame to the
 * remote PTY.
 */
class MoshRemoteTerminalSession(
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

    override fun initializeEmulator(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
    ) {
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

    /** Route all outbound bytes (keypresses + emulator responses) to mosh-client stdin. */
    override fun write(data: ByteArray?, offset: Int, count: Int) {
        if (data == null || count <= 0 || sessionFinished) return
        val slice = if (offset == 0 && count == data.size) data else data.copyOfRange(offset, offset + count)
        onInputBytes(slice)
    }

    /**
     * Push bytes received from mosh-client stdout into the emulator.
     * Safe to call from any thread — the append happens on the main
     * thread via [mainHandler].
     */
    fun feedRemoteBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || sessionFinished) return
        mainHandler.post {
            val emulator = mEmulator ?: return@post
            emulator.append(bytes, bytes.size)
            notifyScreenUpdate()
        }
    }

    /** Called when the mosh-client child process exits or we decide to tear the tab down. */
    fun onRemoteSessionClosed() {
        if (sessionFinished) return
        sessionFinished = true
        mainHandler.post {
            mClient.onSessionFinished(this)
        }
    }

    /** No local shell PID to kill — closing is just the remote-session tear-down. */
    override fun finishIfRunning() {
        onRemoteSessionClosed()
    }

    /** The session is "running" until mosh-client exits. */
    @Synchronized
    override fun isRunning(): Boolean = !sessionFinished

    /**
     * Mirror of [RemoteTerminalSession.sessionClient]: expose the
     * concrete [dev.kuch.termx.feature.terminal.SshSessionClient] so the
     * Compose host can wire the live [com.termux.view.TerminalView] for
     * [dev.kuch.termx.feature.terminal.SshSessionClient.onTextChanged]
     * to call `onScreenUpdated()` against.
     */
    fun sessionClient(): dev.kuch.termx.feature.terminal.SshSessionClient? =
        mClient as? dev.kuch.termx.feature.terminal.SshSessionClient
}
