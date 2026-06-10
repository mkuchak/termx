package dev.kuch.termx.feature.terminal.fakes

import android.os.Handler
import android.os.HandlerThread
import dev.kuch.termx.feature.terminal.connection.ConnectionManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * THE shared Main-dispatcher rule for every test class that constructs a
 * [ConnectionManager] (directly or under a `TerminalViewModel`). It
 * unifies two pieces of test plumbing that used to be duplicated — and
 * subtly divergent — across `ConnectionManagerTest`,
 * `TerminalViewModelMoshTier1Test` and `TerminalViewModelMoshFallbackTest`:
 *
 * 1. A LOOPER-BACKED Main. The manager's connect pipeline runs on
 *    `Dispatchers.Main.immediate` because the vendored Termux
 *    `TerminalSession` constructor creates a no-arg `Handler()` bound to
 *    the constructing thread's looper (TerminalSession.java:96). A
 *    dedicated [HandlerThread] gives every dispatch a real looper, and —
 *    unlike `Dispatchers.Unconfined` — re-dispatches continuations that
 *    were resumed on non-looper threads (e.g. the coroutines
 *    DefaultExecutor timer thread after the mosh liveness
 *    `withTimeoutOrNull` fires), exactly like production's
 *    `Dispatchers.Main` does.
 *
 *    The dispatcher is a hand-rolled post-to-Handler one ON PURPOSE —
 *    NOT `Handler.asCoroutineDispatcher()`. The stock HandlerDispatcher
 *    implements [kotlinx.coroutines.Delay] via `postDelayed`, and under
 *    Robolectric delayed messages are scheduled against the MOCKED
 *    SystemClock, so `withTimeoutOrNull(3s)` would never fire and the
 *    liveness gate would hang forever. A plain [CoroutineDispatcher]
 *    doesn't implement Delay, so timeouts fall back to the real-time
 *    DefaultDelay and only the post-timeout RESUMPTION hops through the
 *    Handler (non-delayed posts run fine on Robolectric background
 *    loopers).
 *
 * 2. QUIESCENCE BEFORE `resetMain()` — the fix for the cross-class
 *    `TestMainDispatcher.kt:67` "Dispatchers.Main is used concurrently
 *    with setting it" flake. The manager [scope][ConnectionManager.scope]
 *    is process-lifetime by design, and its coroutines (init collectors,
 *    connect pipelines, byte pumps, best-effort side jobs) dispatch onto
 *    `Dispatchers.Main`. The old per-class `@After { scope.cancel() }`
 *    mitigation only REQUESTED cancellation: a coroutine parked on a
 *    Main-dispatched suspension point is resumed-with-cancellation VIA
 *    its dispatcher, so the final dispatch could land while the NEXT
 *    test class was mid-`setMain` — kotlinx's `TestMainDispatcher`
 *    detects that read-during-write and throws. Worse, the fallback
 *    class's two unordered `@After`s could quit the HandlerThread before
 *    cancelling the scopes, stranding those resumptions entirely. This
 *    rule [track]s every manager and, in [finished], cancels AND JOINS
 *    each scope — while the handler thread is still alive to process the
 *    cancellation dispatches — and only then calls [Dispatchers.resetMain]
 *    and quits the thread. Nothing can touch Main past the rule boundary.
 *
 * A join that times out fails the test HERE with a named culprit instead
 * of surfacing as an unattributable IllegalStateException in whichever
 * class runs next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuiescentMainDispatcherRule : TestWatcher() {

    private lateinit var mainThread: HandlerThread
    private val managers = CopyOnWriteArrayList<ConnectionManager>()

    /**
     * Register [manager] for teardown quiescence. Call it from the test's
     * manager factory: `ConnectionManager(...).also { mainRule.track(it) }`.
     */
    fun track(manager: ConnectionManager): ConnectionManager {
        managers += manager
        return manager
    }

    override fun starting(description: Description) {
        mainThread = HandlerThread("termx-test-main").apply { start() }
        val handler = Handler(mainThread.looper)
        Dispatchers.setMain(
            object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    handler.post(block)
                }
            },
        )
    }

    override fun finished(description: Description) {
        // Cancel AND JOIN every tracked manager scope BEFORE resetMain:
        // see the class KDoc. The handler thread must still be alive at
        // this point so cancellation resumptions have a Main to land on.
        runBlocking {
            managers.forEach { manager ->
                val job = manager.scope.coroutineContext[Job] ?: return@forEach
                job.cancel()
                val joined = withTimeoutOrNull(JOIN_TIMEOUT_MS) { job.join() }
                check(joined != null) {
                    "ConnectionManager scope did not quiesce within ${JOIN_TIMEOUT_MS}ms " +
                        "after ${description.displayName} — a leaked pump would race the " +
                        "next class's Dispatchers.setMain (TestMainDispatcher.kt:67)"
                }
            }
        }
        Dispatchers.resetMain()
        mainThread.quitSafely()
    }

    private companion object {
        /**
         * Generous: teardown joins are normally instant; the only slow
         * path is a connect attempt mid-`withTimeoutOrNull` (8s mosh
         * handshake cap) that has to observe cancellation first.
         */
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
