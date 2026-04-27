package dev.kuch.termx.feature.updater

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the Android system installer hand-off for a downloaded APK.
 *
 * On API 26+ the user must have granted "Install unknown apps" for
 * the calling package; if they haven't, [install] returns
 * [Result.GrantNeeded] so the UI can route them through
 * [Intent.ACTION_MANAGE_UNKNOWN_APP_SOURCES] before retrying.
 *
 * The actual install then uses [Intent.ACTION_VIEW] +
 * `application/vnd.android.package-archive` because
 * `ACTION_INSTALL_PACKAGE` was deprecated in API 29 and the modern
 * path is to fall back to the system viewer for that MIME type.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Result {
        /** Intent fired; system installer is now in front of the user. */
        data object Launched : Result

        /**
         * REQUEST_INSTALL_PACKAGES is declared in the manifest but the
         * user hasn't granted "Install unknown apps" for our package.
         * The composable should launch [grantPermissionIntent] to take
         * them to the right Settings page, then re-attempt install
         * after they return.
         */
        data object GrantNeeded : Result

        /** Anything unexpected (file gone, FileProvider failure, etc.). */
        data class Error(val reason: String) : Result
    }

    /**
     * Attempt to install [apkFile]. Returns [Result.GrantNeeded] when
     * the user needs to enable "Install unknown apps" first.
     */
    fun install(apkFile: File): Result {
        if (!apkFile.exists()) {
            return Result.Error("APK file missing: ${apkFile.absolutePath}")
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Result.GrantNeeded
        }
        return runCatching {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            Result.Launched
        }.getOrElse { t -> Result.Error(t.message ?: "Failed to launch installer") }
    }

    /**
     * Intent that takes the user to Settings → "Install unknown apps"
     * → termx, where they can flip the toggle on. Compose call site
     * fires this after seeing [Result.GrantNeeded].
     */
    fun grantPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    companion object {
        private const val MIME_APK = "application/vnd.android.package-archive"
    }
}
