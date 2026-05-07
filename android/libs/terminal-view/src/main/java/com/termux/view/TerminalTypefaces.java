/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 *
 * This file lives under the com.termux.view package so it sits alongside
 * the Apache-2.0 [TerminalRenderer] / [TerminalView] it collaborates
 * with, but it is new code written for termx and does NOT carry Termux's
 * Apache-2.0 header. Termux's own files keep theirs.
 */
package com.termux.view;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;

/**
 * Bundle of the four typeface variants the renderer swaps between when
 * drawing runs with combinations of bold / italic attributes from ANSI
 * escapes (CSI 1m, CSI 3m).
 *
 * <p>Pre-existing Termux behaviour was to synthesise bold via
 * {@code Paint.setFakeBoldText(true)} (synthetic stroke widening) and
 * italic via {@code Paint.setTextSkewX(-0.35f)} (oblique transform). Both
 * produce visually inferior glyphs and — combined with Android's
 * variable {@code Typeface.MONOSPACE} fallback chain — the cause of
 * the "some letters appear thicker, others normal" complaint that
 * downstream forks routinely fix by bundling their own font.
 *
 * <p>This class is the bundle. It loads the four real JetBrains Mono NL
 * variants from the AAR's {@code assets/fonts/} directory and caches
 * them as a process-wide singleton (TTFs are mmap-friendly, but the
 * {@link Typeface#createFromAsset} call itself isn't free, and we don't
 * want every {@link TerminalRenderer} re-construction to repeat it).
 *
 * <p>The {@link #systemMonospace()} factory is preserved so existing
 * call sites that pass a custom {@link Typeface} via
 * {@link TerminalView#setTypeface(Typeface)} keep working — the legacy
 * path falls back to {@link Typeface#create(Typeface, int)} which uses
 * Android's synthetic style derivation. Strongly preferred is the
 * {@link #bundled(Context)} path, which gives the renderer real
 * weight/italic glyphs.
 */
public final class TerminalTypefaces {

    public final Typeface regular;
    public final Typeface italic;
    public final Typeface bold;
    public final Typeface boldItalic;

    public TerminalTypefaces(Typeface regular, Typeface italic, Typeface bold, Typeface boldItalic) {
        this.regular = regular;
        this.italic = italic;
        this.bold = bold;
        this.boldItalic = boldItalic;
    }

    private static volatile TerminalTypefaces sBundled;

    /**
     * Lazy singleton of the bundled JetBrains Mono NL family. The
     * {@link AssetManager} is read once via the supplied context's
     * application context so we don't pin an Activity reference.
     */
    public static TerminalTypefaces bundled(Context ctx) {
        TerminalTypefaces local = sBundled;
        if (local != null) return local;
        synchronized (TerminalTypefaces.class) {
            local = sBundled;
            if (local == null) {
                AssetManager am = ctx.getApplicationContext().getAssets();
                local = new TerminalTypefaces(
                    Typeface.createFromAsset(am, "fonts/JetBrainsMonoNL-Regular.ttf"),
                    Typeface.createFromAsset(am, "fonts/JetBrainsMonoNL-Italic.ttf"),
                    Typeface.createFromAsset(am, "fonts/JetBrainsMonoNL-Bold.ttf"),
                    Typeface.createFromAsset(am, "fonts/JetBrainsMonoNL-BoldItalic.ttf")
                );
                sBundled = local;
            }
            return local;
        }
    }

    /**
     * Backwards-compat factory for callers that still want
     * Android's system monospace alias. Bold / italic variants are
     * derived synthetically; this is the v1.1.22-and-earlier
     * behaviour and is kept available so a future user-supplied
     * font-family setting can opt back into it.
     */
    public static TerminalTypefaces systemMonospace() {
        return new TerminalTypefaces(
            Typeface.MONOSPACE,
            Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
        );
    }

    /**
     * Wrap a single {@link Typeface} as a four-variant bundle by
     * letting Android derive synthetic bold/italic styles. Used by
     * the legacy {@link TerminalView#setTypeface(Typeface)} path so
     * external callers passing a single {@link Typeface} continue to
     * work even though the new renderer expects four variants.
     */
    public static TerminalTypefaces fromSingle(Typeface base) {
        return new TerminalTypefaces(
            base,
            Typeface.create(base, Typeface.ITALIC),
            Typeface.create(base, Typeface.BOLD),
            Typeface.create(base, Typeface.BOLD_ITALIC)
        );
    }
}
