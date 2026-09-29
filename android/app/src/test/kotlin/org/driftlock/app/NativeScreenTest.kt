package org.driftlock.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import `in`.driftlock.ui.design.DriftlockTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.driftlock.core.routing.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h860dp")
class NativeScreenTest {
    @get:Rule val compose=createComposeRule()
    private val a=RoadNode(1,Point(0.0,0.0));private val b=RoadNode(2,Point(100.0,0.0))
    private val edge=RoadEdge("ab",1,2,"ab",1,"Garden Road",100.0,listOf(a.point,b.point))
    private val graph=RoadGraph("fixture",listOf(a,b),listOf(edge),emptySet(),Coverage(-10.0,-10.0,110.0,10.0),30.0,76.0,"test","test")
    private val route=Route("A",listOf(RouteLeg("ab")),100.0,null)
    @Test fun homeLeadsWithJourneyAndSampleMapIsExplicit() {
        var command=""
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(),{command=it})}}
        compose.onNodeWithText("Open saved Chandigarh region").performClick()
        assertEquals("sample",command)
        compose.onNodeWithText("SETUP").assertExists()
        compose.onNodeWithText("Chandigarh").assertExists()
        compose.onNodeWithText("Choose a route, then explore a guided drive.").assertExists()
    }
    @Test fun homeRegionRemainsDuringTheMapContainerTransitionThenRetires() {
        val screen=mutableStateOf(NativeState())
        compose.setContent{DriftlockTheme{NativeScreen(screen.value,{if(it=="sample")screen.value=NativeState(page="Prepare",graph=graph)})}}
        compose.mainClock.autoAdvance=false
        compose.onNodeWithText("Open saved Chandigarh region").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Chandigarh").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Chandigarh").assertDoesNotExist()
        compose.onNodeWithTag("offline-road-map").assertExists()
        compose.mainClock.autoAdvance=true
    }
    @Test fun uninitializedDriverDoesNotInventSpeedOrPosition() {
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Driver"),{})}}
        compose.onNodeWithText("GNSS UNKNOWN • UNRELIABLE").assertExists()
        compose.onNodeWithTag("native-content").performScrollToNode(hasText("Waiting for a clear road match"))
        compose.onNodeWithText("Waiting for a clear road match").assertExists()
    }
    @Test fun changingPageStartsAtTopInsteadOfHidingHeadingAndStatus() {
        val screen=mutableStateOf(NativeState(page="Driver"))
        compose.setContent{DriftlockTheme{NativeScreen(screen.value,{})}}
        compose.onNodeWithTag("native-content").performScrollToNode(hasText("Understand reliability"))
        compose.runOnIdle{screen.value=NativeState(page="Home")}
        compose.onNodeWithText("Choose your journey").assertExists()
        compose.onAllNodesWithText("Home").assertCountEquals(1) // bottom navigation
    }
    @Test fun shortestGuidedRouteTapStartsTheStoryRatherThanOnlySelectingARadioButton() {
        var command=""
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Prepare",graph=graph,routes=listOf(route),
            selectedRoute="A",guidedPrepared=true),{command=it})}}
        compose.onNodeWithTag("native-content").performScrollToNode(hasText("SHORTEST",substring=true))
        compose.onNodeWithText("START DRIVE",substring=true).assertExists()
        compose.onNodeWithText("SHORTEST",substring=true).performClick()
        assertEquals("guided-route:A",command)
    }
    @Test fun guidedRouteIsVisibleWithoutScrollingPastTheMap() {
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Prepare",graph=graph,routes=listOf(route),
            selectedRoute="A",guidedPrepared=true),{})}}
        compose.onNodeWithText("SHORTEST",substring=true).assertIsDisplayed()
    }
    @Test fun bothGuidedRouteChoicesFitInTheInitialView() {
        val second=route.copy(id="B",distanceM=120.0)
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Prepare",graph=graph,routes=listOf(route,second),
            selectedRoute="A",guidedPrepared=true),{})}}
        compose.onNodeWithText("SHORTEST",substring=true).assertIsDisplayed()
        compose.onNodeWithText("ROUTE 2",substring=true).assertIsDisplayed()
    }
    @Test fun mapTapAndDragUseTheNewCameraAfterRoutesArePrepared() {
        val far=RoadNode(3,Point(1000.0,0.0))
        val wider=RoadGraph("gesture",listOf(a,b,far),listOf(edge),emptySet(),
            Coverage(-10.0,-10.0,1010.0,10.0),30.0,76.0,"test","test")
        val screen=mutableStateOf(NativeState(graph=wider))
        var point:Point?=null
        val tap:(Point)->Unit={point=it}
        compose.setContent{DriftlockTheme{RoadMap(screen.value,tap)}}
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        assertEquals(500.0,point!!.eastM,1e-3)
        compose.runOnIdle{screen.value=screen.value.copy(routes=listOf(route),selectedRoute="A",guidedPrepared=true)}
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        assertEquals(50.0,point!!.eastM,1e-3)
        compose.onNodeWithTag("offline-road-map").performTouchInput{swipe(center,center+androidx.compose.ui.geometry.Offset(60f,0f),400)}
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        assertTrue("Drag must update the currently displayed camera",point!!.eastM<49.0)
    }
    @Test fun firstDragPreservesTheFollowedVehicleFocus() {
        val plan=GuidedDetour(Point(50.0,0.0),50.0,edge,edge,route,0.0)
        var point:Point?=null
        compose.setContent{DriftlockTheme{RoadMap(NativeState(graph=graph,routes=listOf(route),selectedRoute="A",
            guidedPlan=plan,demoElapsedS=8.5),{point=it},guided=true)}}
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        val before=point!!.eastM
        assertEquals(25.0,before,1e-3)
        compose.onNodeWithTag("offline-road-map").performTouchInput{swipe(center,center+androidx.compose.ui.geometry.Offset(60f,0f),400)}
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        assertTrue("Manual pan must continue from the followed scene",point!!.eastM<before-1.0)
    }
    @Test fun followingAgainMovesFromTheExploredViewWithoutAFirstFrameJump() {
        val plan=GuidedDetour(Point(50.0,0.0),50.0,edge,edge,route,0.0)
        val screen=mutableStateOf(NativeState(graph=graph,routes=listOf(route),selectedRoute="A",guidedPlan=plan,demoElapsedS=8.5))
        var point:Point?=null
        compose.setContent{DriftlockTheme{RoadMap(screen.value,{point=it},guided=true)}}
        compose.onNodeWithTag("offline-road-map").performTouchInput{swipe(center,center+androidx.compose.ui.geometry.Offset(60f,0f),400)}
        compose.runOnIdle{screen.value=screen.value.copy(demoElapsedS=16.0)}
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        val before=point!!.eastM
        compose.mainClock.autoAdvance=false
        compose.onNodeWithContentDescription("Follow scene vehicle").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        assertTrue("Recentring must interpolate from the displayed view",kotlin.math.abs(point!!.eastM-before)<5.0)
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("offline-road-map").performTouchInput{click(center)}
        assertEquals(50.0*16/17,point!!.eastM,1e-3)
        compose.mainClock.autoAdvance=true
    }
    @Test fun researchEvidenceKeepsItsOwnSourceLabelDuringASimulatedJourney() {
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Research",demo=true),{})}}
        compose.onNodeWithText("MODEL LAB").assertExists()
        compose.onNodeWithText("SIMULATED • controlled sensor replay").assertDoesNotExist()
    }
    @Test fun recordedTripLayoutKeepsItsPreviewIdentityVisible() {
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(),{},recordedTripPreview=true)}}
        compose.onNodeWithText("DESIGN PREVIEW").assertIsDisplayed()
        compose.onNodeWithText("Choose a route, then replay your recorded journey.").assertExists()
        compose.onNodeWithTag("native-content").performScrollToNode(hasText("Recorded-trip layout preview · generated sample data."))
        compose.onNodeWithText("Recorded-trip layout preview · generated sample data.").assertIsDisplayed()
    }
    @Test fun standardDemoNeverAdoptsRecordedTripClaims() {
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Demo",demo=true),{})}}
        compose.onNodeWithText("SIMULATED").assertIsDisplayed()
        compose.onNodeWithText("DESIGN PREVIEW").assertDoesNotExist()
        compose.onNodeWithText("RECORDED TRIP  ·  Chandigarh").assertDoesNotExist()
    }
    @Test fun recordedTripMapIsPairedWithItsGeneratedSourceDisclosure() {
        val plan=GuidedDetour(Point(50.0,0.0),50.0,edge,edge,route,0.0)
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Demo",graph=graph,routes=listOf(route),
            selectedRoute="A",demo=true,demoElapsedS=8.0,guidedPlan=plan),{},recordedTripPreview=true)}}
        compose.onNodeWithText("DESIGN PREVIEW").assertIsDisplayed()
        compose.onNodeWithText("RECORDED TRIP  ·  Chandigarh").assertExists()
        compose.onNodeWithText("Layout preview · generated sample data.").assertIsDisplayed()
        val mapBounds=compose.onNodeWithTag("offline-road-map").fetchSemanticsNode().boundsInRoot
        val sourceBounds=compose.onNodeWithText("Layout preview · generated sample data.").fetchSemanticsNode().boundsInRoot
        assertTrue("Source disclosure must stay in the map header, not below the fold",sourceBounds.top<mapBounds.top+mapBounds.height/3)
    }
    @Test fun closureStageOffersOneClearOfflineAlternativeAction() {
        var command=""
        val plan=GuidedDetour(Point(50.0,0.0),50.0,edge,edge,route,0.0)
        compose.setContent{DriftlockTheme{NativeScreen(NativeState(page="Demo",graph=graph,routes=listOf(route),
            selectedRoute="A",demo=true,demoElapsedS=17.0,guidedPlan=plan,guidedWaiting=true),{command=it})}}
        compose.onNodeWithText("Take offline alternative",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Take offline alternative",substring=true).performClick()
        assertEquals("guided-accept",command)
    }
}
