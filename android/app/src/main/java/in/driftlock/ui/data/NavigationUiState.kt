package `in`.driftlock.ui.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class DataSource(val label: String) {
    REAL("REAL"), UNAVAILABLE("UNAVAILABLE"), DEMO("DEMO / SIMULATED"), PROPOSED("PROPOSED")
}

/** Provenance belongs to each field, so a partial snapshot cannot imply all values are real. */
data class UiValue<T>(val value: T? = null, val source: DataSource = DataSource.UNAVAILABLE) {
    init {
        require(value !is String || value.isNotBlank()) { "Blank text must be represented as UNAVAILABLE." }
        require((value == null) == (source == DataSource.UNAVAILABLE)) {
            "Missing values must be UNAVAILABLE; present values must have provenance."
        }
    }
    companion object {
        fun <T> real(value: T) = UiValue(value, DataSource.REAL)
        fun <T> demo(value: T) = UiValue(value, DataSource.DEMO)
    }
}

enum class GnssStatus(val label: String) {
    HEALTHY("Healthy"), DEGRADING("Degrading"), LOST("Unavailable"),
    RETURNING("Returning"), RECOVERED("Recovered")
}
enum class FeedStatus { LOADING, UNAVAILABLE, READY, NO_DATA, ERROR }

/** Display strings retain upstream units/semantics; this UI performs no navigation calculations. */
data class RoadHypothesis(
    val id: String,
    val road: UiValue<String> = UiValue(),
    val probability: UiValue<String> = UiValue()
)
data class UncertaintySample(val timeLabel: String, val uncertaintyLabel: String)
data class VerifiedMetric(val id: String, val label: String, val value: UiValue<String>)

data class NavigationUiState(
    val feedStatus: FeedStatus = FeedStatus.UNAVAILABLE,
    val message: String? = null,
    val position: UiValue<String> = UiValue(),
    val speed: UiValue<String> = UiValue(),
    val speedUncertainty: UiValue<String> = UiValue(),
    val heading: UiValue<String> = UiValue(),
    val gnssStatus: UiValue<GnssStatus> = UiValue(),
    val drStatus: UiValue<String> = UiValue(),
    val positionSource: UiValue<String> = UiValue(),
    val shadowDr: UiValue<String> = UiValue(),
    val roadLockStatus: UiValue<String> = UiValue(),
    val selectedRoad: UiValue<String> = UiValue(),
    val roadProbability: UiValue<String> = UiValue(),
    val roadHypotheses: UiValue<List<RoadHypothesis>> = UiValue(),
    val uncertainty: UiValue<String> = UiValue(),
    val uncertaintyOverTime: UiValue<List<UncertaintySample>> = UiValue(),
    val confidenceState: UiValue<String> = UiValue(),
    val confidenceHorizon: UiValue<String> = UiValue(),
    val recoveryState: UiValue<String> = UiValue(),
    val calibrationState: UiValue<String> = UiValue(),
    val benchmarkResults: UiValue<String> = UiValue(),
    val benchmarkConditions: UiValue<String> = UiValue(),
    val measuredDrift: UiValue<String> = UiValue(),
    val verifiedKpis: UiValue<List<VerifiedMetric>> = UiValue()
)

/** Implement around the Android team's existing StateFlow once their class is available. */
interface NavigationStateAdapter { val states: Flow<NavigationUiState> }
class UnavailableNavigationAdapter : NavigationStateAdapter {
    override val states = flowOf(NavigationUiState())
}

/** Translate only. Implement in the integration layer using the actual Android state type. */
fun interface NavigationUiMapper<T> { fun map(state: T): NavigationUiState }
