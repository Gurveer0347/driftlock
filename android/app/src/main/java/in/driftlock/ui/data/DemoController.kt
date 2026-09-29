package `in`.driftlock.ui.data

enum class DemoAction(val label: String) {
    NORMAL("NORMAL"), DEGRADE("DEGRADE"), CUT_GNSS("CUT GNSS"), RESTORE_GNSS("RESTORE GNSS")
}
enum class DemoStage(val label: String) {
    NORMAL("NORMAL"), DEGRADE("DEGRADE"), GNSS_CUT("GNSS CUT"), DR_ACTIVE("DR ACTIVE"),
    ROAD_LOCK("ROAD LOCK"), CONFIDENCE("CONFIDENCE"), GNSS_RESTORE("GNSS RESTORE"), RECOVERY("RECOVERY")
}
interface DemoController {
    val available: Boolean
    suspend fun perform(action: DemoAction): String
}
class UnavailableDemoController : DemoController {
    override val available = false
    override suspend fun perform(action: DemoAction) = "UNAVAILABLE — ANDROID CALLBACK REQUIRED"
}

data class DemoPresentation(val stage: DemoStage? = null, val gnss: GnssStatus? = null) {
    fun select(action: DemoAction) = when (action) {
        DemoAction.NORMAL -> DemoPresentation(DemoStage.NORMAL, GnssStatus.HEALTHY)
        DemoAction.DEGRADE -> DemoPresentation(DemoStage.DEGRADE, GnssStatus.DEGRADING)
        DemoAction.CUT_GNSS -> DemoPresentation(DemoStage.GNSS_CUT, GnssStatus.LOST)
        DemoAction.RESTORE_GNSS -> DemoPresentation(DemoStage.GNSS_RESTORE, GnssStatus.RETURNING)
    }
    fun preview(stage: DemoStage) = DemoPresentation(stage, when (stage) {
        DemoStage.NORMAL -> GnssStatus.HEALTHY
        DemoStage.DEGRADE -> GnssStatus.DEGRADING
        DemoStage.GNSS_CUT, DemoStage.DR_ACTIVE, DemoStage.ROAD_LOCK, DemoStage.CONFIDENCE -> GnssStatus.LOST
        DemoStage.GNSS_RESTORE, DemoStage.RECOVERY -> GnssStatus.RETURNING
    })
    // Separate explicit preview; RESTORE never asserts that backend recovery completed.
    fun recoveredPreview() = DemoPresentation(DemoStage.RECOVERY, GnssStatus.RECOVERED)
}

