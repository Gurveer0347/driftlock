package `in`.driftlock.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import `in`.driftlock.ui.data.*
import `in`.driftlock.ui.design.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h700dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun modesAreReachableOnSmallPhoneAndDemoCannotLeakIntoDriver() {
        compose.setContent {
            var demo by remember { mutableStateOf(DemoPresentation()) }
            DriftlockTheme { DriftlockShell(NavigationUiState(), demo,
                onAction = { demo = demo.select(it) }) }
        }
        compose.onNodeWithText("MAP DATA UNAVAILABLE").assertExists()
        capture("driver-small")
        compose.onNodeWithText("Judge", useUnmergedTree = true).performClick()
        compose.onNodeWithText("System observatory").assertExists()
        capture("judge-small")
        compose.onNodeWithText("Demo", useUnmergedTree = true).performClick()
        capture("demo-controls")
        compose.onNodeWithTag("demo-list").performScrollToNode(hasText("CUT GNSS"))
        compose.onNodeWithText("CUT GNSS").performClick()
        compose.onNodeWithTag("demo-list").performScrollToNode(hasText("GNSS state preview"))
        compose.onNodeWithText("Unavailable", substring = false).assertExists()
        capture("demo-cut-small")
        compose.onNodeWithText("Driver", useUnmergedTree = true).performClick()
        compose.onNodeWithText("NavigationState: UNAVAILABLE").assertExists()
        compose.onAllNodesWithText("DEMO / SIMULATED").assertCountEquals(0)
    }

    @Test fun loadingErrorAndEmptyStatesAreExplicit() {
        var state by mutableStateOf(NavigationUiState(feedStatus = FeedStatus.LOADING))
        compose.setContent { DriftlockTheme { DriftlockShell(state) } }
        compose.onNodeWithText("Loading NavigationState").assertExists()
        compose.runOnIdle { state = NavigationUiState(feedStatus = FeedStatus.ERROR) }
        compose.onNodeWithText("Navigation connection error").assertExists()
        capture("driver-error")
        compose.runOnIdle { state = NavigationUiState(feedStatus = FeedStatus.NO_DATA) }
        compose.onNodeWithText("No navigation data").assertExists()
    }

    @Test fun allGnssLabelsRenderWithoutImplyingDr() {
        var state by mutableStateOf(NavigationUiState())
        compose.setContent { DriftlockTheme { GNSSStatusCard(state) } }
        GnssStatus.entries.forEach { gnss ->
            compose.runOnIdle { state = NavigationUiState(gnssStatus = UiValue.demo(gnss)) }
            compose.onNodeWithText(gnss.label).assertExists()
            compose.onNodeWithText("DEMO / SIMULATED").assertExists()
            compose.onAllNodesWithText("UNAVAILABLE").assertCountEquals(2)
        }
    }

    @Test fun largeTextAndLongValuesRemainScrollable() {
        val longRoad = "DEMO / SIMULATED — A very long road segment name supplied for text wrapping verification only"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                DriftlockTheme { DriftlockShell(NavigationUiState(selectedRoad = UiValue.demo(longRoad))) }
            }
        }
        compose.onNodeWithTag("driver-list").performScrollToNode(hasText(longRoad))
        compose.onNodeWithText(longRoad).performScrollTo().assertIsDisplayed()
        capture("large-text-long-road")
        compose.onNodeWithText("Judge", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("System observatory").assertExists()
    }

    @Test fun driverDetailsAndMapInformationAreFunctional() {
        compose.setContent { DriftlockTheme { DriftlockShell(NavigationUiState()) } }
        compose.onNodeWithTag("driver-list").performScrollToNode(hasText("Map details"))
        compose.onNodeWithText("Map details").performClick()
        compose.onNodeWithText("Map connection").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("driver-list").performScrollToNode(hasText("Journey status"))
        capture("driver-status")
        compose.onNodeWithTag("driver-list").performScrollToNode(hasText("Initialization & calibration"))
        compose.onNodeWithText("WAITING FOR NAVIGATION CORE").assertExists()
        compose.onNodeWithTag("driver-list").performScrollToNode(hasText("Show system details"))
        compose.onNodeWithText("Show system details").performClick()
        compose.onNodeWithTag("driver-list").performScrollToNode(hasText("Recovery flow · reference only"))
        compose.onNodeWithText("PROPOSED").assertExists()
    }

    @Test fun judgeSectionsExposeEveryIntegrationSurface() {
        compose.setContent { DriftlockTheme { DriftlockShell(NavigationUiState()) } }
        compose.onNodeWithText("Judge", useUnmergedTree = true).performClick()
        compose.onNodeWithText("NavigationState overview").assertExists()
        listOf("Roads" to "Selected road", "Confidence" to "CONFIDENCE HORIZON", "Recovery" to "Recovery state", "Validation" to "Benchmark results").forEach { (tab, target) ->
            compose.onNodeWithTag("engineering-tabs").performScrollToNode(hasText(tab))
            compose.onNode(hasText(tab) and hasClickAction()).performClick()
            compose.onNodeWithTag("judge-list").performScrollToNode(hasText(target))
            compose.onAllNodesWithText("UNAVAILABLE").assertAny(hasText("UNAVAILABLE"))
            capture("judge-${tab.lowercase()}")
        }
    }

    @Test fun everyDemoStageRemainsSimulatedAndDoesNotGenerateMeasurements() {
        compose.setContent {
            var demo by remember { mutableStateOf(DemoPresentation()) }
            DriftlockTheme { DriftlockShell(NavigationUiState(), demo,
                onAction = { demo = demo.select(it) }, onStage = { demo = demo.preview(it) },
                onRecovered = { demo = demo.recoveredPreview() }, onReset = { demo = DemoPresentation() }) }
        }
        compose.onNodeWithText("Demo", useUnmergedTree = true).performClick()
        DemoStage.entries.forEach { stage ->
            compose.onNodeWithTag("demo-list").performScrollToNode(hasTestTag("demo-stages"))
            compose.onNodeWithTag("demo-stages").performScrollToNode(hasText("${stage.ordinal + 1} · ${stage.label}"))
            compose.onNodeWithText("${stage.ordinal + 1} · ${stage.label}").performClick()
            compose.onNodeWithTag("demo-list").performScrollToNode(hasText("GNSS state preview"))
            compose.onAllNodesWithText("DEMO / SIMULATED").assertAny(hasText("DEMO / SIMULATED"))
            compose.onAllNodesWithText("UNAVAILABLE").assertAny(hasText("UNAVAILABLE"))
        }
        capture("demo-recovery")
        compose.onNodeWithTag("demo-list").performScrollToNode(hasText("Reset local preview"))
        compose.onNodeWithText("Reset local preview").performClick()
        compose.onNodeWithTag("demo-list").performScrollToNode(hasText("Ready to present"))
        compose.onNodeWithText("Ready to present").assertIsDisplayed()
    }

    @Test fun suppliedEmptyListsAreDifferentFromMissingLists() {
        compose.setContent { DriftlockTheme { DriftlockShell(NavigationUiState(roadHypotheses = UiValue.demo(emptyList()))) } }
        compose.onNodeWithText("Judge", useUnmergedTree = true).performClick()
        compose.onNode(hasText("Roads") and hasClickAction()).performClick()
        compose.onNodeWithTag("judge-list").performScrollToNode(hasText("No alternative roads supplied."))
        compose.onNodeWithText("No alternative roads supplied.").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp-land")
    fun largeScreenEngineeringOverviewIsReadable() {
        compose.setContent { DriftlockTheme { DriftlockShell(NavigationUiState()) } }
        compose.onNodeWithText("Judge", useUnmergedTree = true).performClick()
        compose.onNodeWithText("NavigationState overview").assertIsDisplayed()
        capture("judge-large")
    }
    private fun capture(name: String) {
        val file = File("build/reports/ui-screenshots/$name.png")
        file.parentFile?.mkdirs()
        // Draw the native view directly: PixelCopy forceRedraw does not complete under this host's Robolectric.
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w850dp-h400dp-land")
    fun landscapeKeepsNavigationAccessible() {
        compose.setContent { DriftlockTheme { DriftlockShell(NavigationUiState()) } }
        compose.onNodeWithTag("driver-list").performScrollToNode(hasText("Forward speed"))
        compose.onNodeWithText("Forward speed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Demo", useUnmergedTree = true).assertIsDisplayed()
        capture("driver-landscape")
    }
}



