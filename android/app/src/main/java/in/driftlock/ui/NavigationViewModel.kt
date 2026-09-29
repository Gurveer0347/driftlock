package `in`.driftlock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.driftlock.ui.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class NavigationViewModel(
    adapter: NavigationStateAdapter,
    private val demoController: DemoController
) : ViewModel() {
    val navigation = adapter.states.catch { error ->
        if (error is CancellationException) throw error
        emit(NavigationUiState(feedStatus = FeedStatus.ERROR, message = "NavigationState connection failed. Ask Android integration to reconnect."))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationUiState(feedStatus = FeedStatus.LOADING))
    private val _demo = MutableStateFlow(DemoPresentation())
    val demo = _demo.asStateFlow()
    private val _callback = MutableStateFlow(if (demoController.available) "Android callback connected. Awaiting command." else "UNAVAILABLE — ANDROID CALLBACK REQUIRED")
    val callback = _callback.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    fun perform(action: DemoAction) {
        if (_busy.value) return
        _demo.value = _demo.value.select(action)
        if (!demoController.available) return
        _busy.value = true
        viewModelScope.launch {
            try { _callback.value = demoController.perform(action) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _callback.value = "ERROR — Android demo callback failed. Navigation values remain supplied by the core." }
            finally { _busy.value = false }
        }
    }
    fun preview(stage: DemoStage) { _demo.value = _demo.value.preview(stage) }
    fun previewRecovered() { _demo.value = _demo.value.recoveredPreview() }
    fun resetDemo() { _demo.value = DemoPresentation() }
    class Factory(private val adapter: NavigationStateAdapter, private val controller: DemoController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NavigationViewModel::class.java))
            return NavigationViewModel(adapter, controller) as T
        }
    }
}
