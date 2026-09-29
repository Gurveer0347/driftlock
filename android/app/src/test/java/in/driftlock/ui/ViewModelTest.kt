package `in`.driftlock.ui

import `in`.driftlock.ui.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import androidx.lifecycle.viewModelScope

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<NavigationViewModel>()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun model(adapter: NavigationStateAdapter, controller: DemoController) =
        NavigationViewModel(adapter, controller).also { models.add(it) }

    @Test fun callbackAcknowledgementNeverReplacesNavigationState() = runTest {
            val commands = mutableListOf<DemoAction>()
            val controller = object : DemoController {
                override val available = true
                override suspend fun perform(action: DemoAction): String {
                    commands.add(action)
                    return "Command acknowledged"
                }
            }
            val model = model(UnavailableNavigationAdapter(), controller)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.navigation.collect() }
            model.perform(DemoAction.CUT_GNSS)
            advanceUntilIdle()
            assertEquals(listOf(DemoAction.CUT_GNSS), commands)
            assertEquals(GnssStatus.LOST, model.demo.value.gnss)
            assertNull(model.navigation.value.gnssStatus.value)
            assertNull(model.navigation.value.drStatus.value)
            assertEquals("Command acknowledged", model.callback.value)
            assertFalse(model.busy.value)
    }

    @Test fun failedStreamClearsMeasurementsAndReportsError() = runTest {
            val adapter = object : NavigationStateAdapter {
                override val states = flow {
                    emit(NavigationUiState(feedStatus = FeedStatus.READY, gnssStatus = UiValue.demo(GnssStatus.HEALTHY)))
                    throw IllegalStateException("Private transport detail")
                }
            }
            val model = model(adapter, UnavailableDemoController())
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.navigation.collect() }
            advanceUntilIdle()
            assertEquals(FeedStatus.ERROR, model.navigation.value.feedStatus)
            assertNull(model.navigation.value.gnssStatus.value)
            assertFalse(model.navigation.value.message.orEmpty().contains("Private"))
    }

    @Test fun failedCallbackIsVisibleAndAllowsNextCommand() = runTest {
            val controller = object : DemoController {
                override val available = true
                override suspend fun perform(action: DemoAction): String = error("Transport failed")
            }
            val model = model(UnavailableNavigationAdapter(), controller)
            model.perform(DemoAction.RESTORE_GNSS)
            advanceUntilIdle()
            assertTrue(model.callback.value.startsWith("ERROR"))
            assertFalse(model.busy.value)
            assertEquals(GnssStatus.RETURNING, model.demo.value.gnss)
    }
}
