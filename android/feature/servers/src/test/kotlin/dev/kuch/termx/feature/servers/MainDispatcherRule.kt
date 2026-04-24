package dev.kuch.termx.feature.servers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit 4 rule that swaps [Dispatchers.Main] for a [CoroutineDispatcher]
 * so `viewModelScope`-spawned coroutines execute on the unit-test thread.
 *
 * Two dispatchers are useful here:
 *
 *  - [UnconfinedTestDispatcher] (default) pairs with `runTest { }`. Good
 *    for tests that want deterministic ordering and virtual-time delays.
 *  - [Dispatchers.Unconfined] (real) avoids virtual time entirely. Use
 *    when the production code wraps work in `withTimeoutOrNull` +
 *    `withContext(Dispatchers.IO)` — the test's virtual clock would
 *    otherwise race past the timeout while IO is on a real thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
