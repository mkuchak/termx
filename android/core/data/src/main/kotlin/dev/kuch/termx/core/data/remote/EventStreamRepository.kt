package dev.kuch.termx.core.data.remote

import dev.kuch.termx.libs.companion.EventStreamClient
import dev.kuch.termx.libs.companion.EventStreamClientFactory
import dev.kuch.termx.libs.sshnative.SshSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side entry point for termxd event streaming.
 *
 * Phase 4 (this task) keeps the surface minimal: hand the repository an
 * authenticated [SshSession] and it gives you an [EventStreamClient]
 * ready to tail `~/.termx/events.ndjson`.
 *
 * Phase 5/7 will extend this with per-server lifecycle management:
 *  - keep one client alive per connected server
 *  - merge their event streams into a single application-wide flow
 *  - route events to the permission broker and the notification service
 *
 * The deliberate split between `libs:companion` (pure Kotlin, sshj-aware
 * but Android-free) and `core:data` (Android + Hilt wiring) means tests
 * can exercise the client against a fake session without pulling the
 * repository, and the repository can grow Android-specific state (fg
 * service, notifications) without leaking back into the library.
 */
@Singleton
class EventStreamRepository @Inject constructor(
    private val factory: EventStreamClientFactory,
) {
    /**
     * Build a client bound to [session]. The returned client does NOT
     * own the session — the caller is responsible for keeping it alive
     * while collecting the stream.
     */
    fun clientFor(session: SshSession): EventStreamClient = factory.create(session)
}
