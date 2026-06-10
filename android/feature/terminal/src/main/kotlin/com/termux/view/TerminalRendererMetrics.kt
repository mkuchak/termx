/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 *
 * This file lives under the com.termux.view package intentionally so it can
 * read the package-private `mFontLineSpacingAndAscent` field on the vendored
 * [TerminalRenderer] — the same trick `RemoteTerminalSession` uses for
 * com.termux.terminal. It does NOT carry Termux's Apache-2.0 header because
 * it is new code written for termx; the Apache-2.0 files it sits next to
 * keep theirs.
 */
package com.termux.view

/**
 * The vertical extent in pixels that [TerminalRenderer.render] actually
 * paints for a full emulator grid is
 * `rows * fontLineSpacing + fontLineSpacingAndAscent` — the same formula
 * [TerminalView.updateSize] inverts to derive a row count from a view
 * height (`(viewHeight - mFontLineSpacingAndAscent) / mFontLineSpacing`).
 *
 * The renderer only exposes public getters for the font width and line
 * spacing; this accessor surfaces the missing ascent-adjusted term so the
 * offscreen thumbnail renderer can size its natural canvas exactly instead
 * of approximating. (The ascent is most of a line height, so rounding up
 * to a whole extra line would skew the thumbnail's fit-scale and leave a
 * visible dead band of background along one edge.)
 */
internal val TerminalRenderer.fontLineSpacingAndAscent: Int
    get() = mFontLineSpacingAndAscent
