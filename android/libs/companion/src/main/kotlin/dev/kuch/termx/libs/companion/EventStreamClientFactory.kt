package dev.kuch.termx.libs.companion

import dev.kuch.termx.libs.sshnative.SshSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds one [EventStreamClient] per [SshSession].
 *
 * Why a factory rather than a `@Provides` of the client directly: an
 * [EventStreamClient] needs a specific [SshSession], and sessions are
 * short-lived, per-server, and owned by [dev.kuch.termx.core.data.remote.TmuxSessionRepositoryImpl]'s
 * connection cache. Hilt doesn't offer a session-scope that lines up
 * with that without a custom component, so the factory pattern keeps
 * the injection story simple: any consumer takes the factory as a
 * [Singleton] and calls [create] whenever it has a live session in hand.
 *
 * The returned client holds no state of its own beyond a lazy `$HOME`
 * cache and an error SharedFlow — cheap to discard and recreate.
 */
@Singleton
class EventStreamClientFactory @Inject constructor() {
    fun create(session: SshSession): EventStreamClient = EventStreamClient(session)
}
