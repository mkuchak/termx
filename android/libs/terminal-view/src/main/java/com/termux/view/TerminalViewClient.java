/*
 * Copyright (C) Termux contributors and the Android Terminal Emulator project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.termux.terminal.TerminalSession;

/**
 * The interface for communication between {@link TerminalView} and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * {@link View#setOnKeyListener(View.OnKeyListener)}, etc. It must be set for the
 * {@link TerminalView} through {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 */
public interface TerminalViewClient {

    /**
     * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
     */
    float onScale(float scale);



    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    void onSingleTapUp(MotionEvent e);

    boolean shouldBackButtonBeMappedToEscape();

    boolean shouldEnforceCharBasedInput();

    boolean shouldUseCtrlSpaceWorkaround();

    boolean isTerminalViewSelected();



    void copyModeChanged(boolean copyMode);



    boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session);

    boolean onKeyUp(int keyCode, KeyEvent e);

    boolean onLongPress(MotionEvent event);



    boolean readControlKey();

    boolean readAltKey();

    boolean readShiftKey();

    boolean readFnKey();

    /**
     * termx extension (v1.1.14): the extra-keys bar's sticky CTRL state.
     * Read from the IME commit path so a single tap of CTRL on the bar
     * applies Ctrl-byte translation to the next letter typed on the
     * Android keyboard, even though that letter arrives via
     * InputConnection.commitText() rather than KeyEvent dispatch.
     * Default false keeps non-termx hosts of this fork unaffected.
     */
    default boolean readStickyCtrl() { return false; }

    /**
     * termx extension (v1.1.14): the extra-keys bar's sticky ALT state.
     * Causes commitText to ESC-prefix the bytes of the next codepoint
     * (xterm Meta encoding) when set.
     */
    default boolean readStickyAlt() { return false; }

    /**
     * termx extension (v1.1.14): clear any OneShot sticky modifiers.
     * Called by commitText after applying a sticky CTRL/ALT to the
     * first codepoint of a commit, mirroring the existing OneShot
     * semantics from the bar's Modifier.clickable path. Locked
     * modifiers are unaffected.
     */
    default void consumeStickyModifiers() { }



    boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session);


    void onEmulatorSet();


    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
