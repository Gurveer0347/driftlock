package `in`.driftlock.ui

import `in`.driftlock.ui.data.*
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun missingValuesCannotBeMarkedReal() {
        assertThrows(IllegalArgumentException::class.java) { UiValue<String>(null, DataSource.REAL) }
        assertThrows(IllegalArgumentException::class.java) { UiValue("invented", DataSource.UNAVAILABLE) }
        assertThrows(IllegalArgumentException::class.java) { UiValue.real("   ") }
    }
    @Test fun suppliedFieldsDoNotMakeMissingFieldsReal() {
        val state = NavigationUiState(gnssStatus = UiValue.real(GnssStatus.LOST))
        assertEquals(DataSource.REAL, state.gnssStatus.source)
        assertNull(state.drStatus.value)
        assertNull(state.positionSource.value)
        assertNull(state.speed.value)
        assertEquals(DataSource.UNAVAILABLE, state.uncertainty.source)
    }
    @Test fun demoTransitionsNeverCalculateMeasurements() {
        var demo = DemoPresentation()
        val expected = listOf(GnssStatus.HEALTHY, GnssStatus.DEGRADING, GnssStatus.LOST, GnssStatus.RETURNING)
        DemoAction.entries.forEachIndexed { index, action ->
            demo = demo.select(action)
            assertEquals(expected[index], demo.gnss)
        }
        assertNotEquals(GnssStatus.RECOVERED, demo.gnss)
        assertEquals(GnssStatus.RECOVERED, demo.recoveredPreview().gnss)
        assertEquals(DemoStage.DR_ACTIVE, demo.preview(DemoStage.DR_ACTIVE).stage)
        assertNull(NavigationUiState().drStatus.value)
    }
    @Test fun noCallbacksByDefault() { assertFalse(UnavailableDemoController().available) }
}
