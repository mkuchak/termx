package dev.kuch.termx.feature.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Where this APK was installed FROM. The in-app updater self-disables
 * for [FDroid] installs because F-Droid has its own update mechanism;
 * shipping a second prompt would be confusing. [Sideload] is the
 * direct-from-GitHub path the updater is built for.
 */
enum class UpdateInstallerSource {
    /** Installed via `adb install`, file manager, browser, or our own ApkInstaller. */
    Sideload,

    /** Installed via the F-Droid client (org.fdroid.fdroid) or the Aurora-Droid fork. */
    FDroid,

    /**
     * Installer reported a value we don't recognise (vendor-specific
     * stores, foreign sideloaders). Treat as Sideload — a missed
     * suppression is better than a missed update.
     */
    Unknown,
    ;

    companion object {

        /** Package names that mean "F-Droid is updating us; stand down." */
        private val FDROID_INSTALLERS = setOf(
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.aurora.adroid",
        )

        /**
         * Public Android-Context entry point. Reads [PackageManager.getInstallSourceInfo]
         * (API 30+) or the deprecated [PackageManager.getInstallerPackageName]
         * (older devices), then delegates to [classify].
         */
        fun detect(context: Context): UpdateInstallerSource {
            val installer: String? = runCatching {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
            return classify(installer)
        }

        /**
         * Pure-logic classifier broken out for unit tests so we don't
         * have to wrestle with [PackageManager]-shadow setup quirks
         * across Robolectric versions. A null/empty installer typically
         * means manual sideload (`adb install`, file-manager APK tap,
         * browser download), which is exactly the case we want to
         * update.
         */
        internal fun classify(installerPackage: String?): UpdateInstallerSource = when {
            installerPackage.isNullOrEmpty() -> Sideload
            installerPackage in FDROID_INSTALLERS -> FDroid
            installerPackage == "com.android.vending" -> Unknown // never expected
            else -> Unknown
        }
    }
}
