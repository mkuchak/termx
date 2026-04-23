package dev.kuch.termx.feature.servers.setup

/**
 * Route constants for the Setup Wizard.
 *
 * The wizard is a full-screen destination — distinct from the bare
 * [dev.kuch.termx.feature.servers.AddEditServerSheet] modal — and lives under
 * the `setup-wizard` route in `TermxNavHost`. The server list's FAB opens a
 * small picker that routes users either at the wizard (guided onboarding) or
 * at the bottom sheet (quick add for power users).
 */
object SetupWizardRoute {
    const val ROUTE: String = "setup-wizard"
}
