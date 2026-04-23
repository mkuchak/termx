package dev.kuch.termx.feature.keys.unlock

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

/**
 * Unwraps [ContextWrapper] chains returned by Compose's `LocalContext` to
 * find the enclosing [FragmentActivity]. Returns null if none is present,
 * which should not happen given `MainActivity` extends `FragmentActivity`,
 * but the caller still guards against it.
 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
