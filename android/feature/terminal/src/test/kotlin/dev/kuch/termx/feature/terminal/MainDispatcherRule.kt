package dev.kuch.termx.feature.terminal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps [Dispatchers.Main] for a test dispatcher so `viewModelScope`
 * coroutines run on the unit-test thread.
 *
 * Defaults to real [Dispatchers.Unconfined] rather than
 * [UnconfinedTestDispatcher] because the VM's best-effort paths hop onto
 * `Dispatchers.IO` (DataStore reads in `syncUnifiedPushEndpoint`, the mosh
 * output collector); a virtual-time test scheduler can't see those, so the
 * tests poll real state with [waitFor]-style helpers and want real time.
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
