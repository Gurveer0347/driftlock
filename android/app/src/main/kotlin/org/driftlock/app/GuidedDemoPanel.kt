package org.driftlock.app

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import `in`.driftlock.ui.design.GlassSurface
import `in`.driftlock.ui.design.GlassTokens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Presenter-facing journey. The animated scene marker is separate from estimator output. */
@Composable internal fun GuidedDemoPanel(state:NativeState,action:(String)->Unit,onManualClosure:()->Unit) {
    val preview=LocalRecordedTripPreview.current
    var details by remember { mutableStateOf(false) }
    val guided=state.guidedPlan!=null
    val stage=if(guided)GuidedTimeline.stage(state.demoElapsedS,state.guidedDetourChosen,state.demoFinished) else null
    val title=when(stage) {
        GuidedStage.DEPARTING->"The journey begins"
        GuidedStage.CRUISE->"Following the shortest route"
        GuidedStage.OFFLINE->"Signal lost. Journey continues."
        GuidedStage.ROADBLOCK->"A roadblock changes the plan"
        GuidedStage.TURNBACK->"Turning back on mapped streets"
        GuidedStage.DETOUR->"The offline alternative"
        GuidedStage.RECOVERY->"Signal returns near arrival"
        GuidedStage.ARRIVED->"Destination reached"
        null->if(state.demo)"Session replay in progress" else if(preview)"Ready to replay your journey" else "Ready for a guided drive"
    }
    val body=when(stage) {
        GuidedStage.OFFLINE->if(preview)"The session continues with phone motion and saved roads." else "GNSS is withheld from the native estimator. The saved road map remains available."
        GuidedStage.ROADBLOCK->"Choose a connected route around ${state.demoBlockedRoad ?: "the closed road"}."
        GuidedStage.TURNBACK->if(preview)"Replay the turn-back and continue along the saved alternative." else "The scene vehicle turns back through connected mapped roads."
        GuidedStage.DETOUR->"The alternative follows the locally stored road graph."
        GuidedStage.RECOVERY->if(preview)"The signal returns for the final stretch of the journey." else "GNSS fixes return to the native estimator."
        GuidedStage.ARRIVED->if(preview)"Journey playback is complete. Explore the session details below." else "The simulated journey is complete. Explore the sensor details below."
        else->if(preview)"Replay your journey on the saved offline map." else "Watch the route unfold on the offline map."
    }
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(if(state.demoGnss=="CUT")"GNSS OFFLINE" else "GNSS ONLINE",style=MaterialTheme.typography.labelLarge,
                color=if(state.demoGnss=="CUT")Color(0xFFB86C2E) else Color(0xFF126A48))
            Text(if(state.packId!=null)"ROADS SAVED LOCALLY" else "LOCAL ROAD MAP",style=MaterialTheme.typography.labelMedium,
                color=Color(0xFF567067))
        }
        Box(Modifier.fillMaxWidth()) {
            RoadMap(state,guided=guided,modifier=Modifier.journeyBounds())
            GlassSurface(shape=RoundedCornerShape(24.dp),
                tint=animateColorAsState(if(state.guidedWaiting)Color(0xDAFFF2E3) else Color(0xDAFFFEFB),tween(380),label="journey-glass").value,
                borderColor=if(state.guidedWaiting)Color(0xFFE9CBAE) else GlassTokens.edge,
                elevation=1.dp,
                modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    if(state.guidedWaiting)Text("ROAD AHEAD CLOSED",style=MaterialTheme.typography.labelLarge,color=Color(0xFF9A552B))
                    AnimatedContent(targetState=title to body,transitionSpec={fadeIn(tween(350))+slideInVertically(tween(400)){it/12} togetherWith fadeOut(tween(200))},label="journey-story") { copy ->
                        Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            Text(copy.first,style=MaterialTheme.typography.headlineSmall,color=Color(0xFF163F32))
                            Text(copy.second,style=MaterialTheme.typography.bodyMedium,color=Color(0xFF586C60))
                        }
                    }
                    if(state.guidedWaiting && state.guidedPlan!=null) {
                        Text("${state.demoBlockedRoad ?: "Mapped road"}  ·  ${"%.2f".format(state.guidedPlan.alternate.distanceM/1000)} km via offline roads",
                            style=MaterialTheme.typography.bodyMedium)
                        GlassButton(onClick={action("guided-accept")},modifier=Modifier.fillMaxWidth().height(50.dp)) {
                            Text("Take offline alternative  →")
                        }
                    } else if(state.demo) {
                        Text(if(preview)"REPLAY  ·  ${"%.0f".format(state.demoElapsedS)} s" else "SIMULATED DRIVE  ·  ${"%.0f".format(state.demoElapsedS)} s",style=MaterialTheme.typography.labelMedium,
                            color=Color(0xFF5A7466))
                    }
                }
            }
        }
        val progress=animateFloatAsState(GuidedTimeline.detourProgress(state.demoElapsedS,state.guidedDetourChosen).toFloat()*.62f+GuidedTimeline.primaryProgress(state.demoElapsedS).toFloat()*.38f,tween(220),label="journey-progress")
        if(guided) LinearProgressIndicator(progress={progress.value},modifier=Modifier.fillMaxWidth(),color=Color(0xFF178F61),
            trackColor=Color(0xFFE1E9DE))
        if(!state.demo) GlassButton(onClick={action("demo-start")},enabled=!state.sensor.active,
            modifier=Modifier.fillMaxWidth().height(54.dp)){Text(if(preview)"Replay journey" else "Start simulated drive")}
        if(state.demo && !guided) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                GlassOutlinedButton(onClick={action("demo-gnss:CUT")},enabled=!state.demoFinished){Text("GNSS off")}
                GlassOutlinedButton(onClick={action("demo-gnss:NORMAL")},enabled=!state.demoFinished){Text("Restore")}
            }
            GlassOutlinedButton(onClick=onManualClosure,enabled=state.journey?.route!=null){Text("Explore road closure")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={details=!details}){Text(if(details && preview)"Hide session details" else if(details)"Hide sensor details" else if(preview)"Session details" else "Sensor details")}
            if(state.demo)TextButton(onClick={action("demo-start")}){Text("Replay")}
        }
        AnimatedVisibility(visible=details) {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("The green scene vehicle follows a scripted route on mapped roads. The blue marker is the separate sensor estimator. Both use generated replay inputs and a virtual clock that pauses for your route choice; the scene path is not measured vehicle position.",
                    style=MaterialTheme.typography.bodyMedium)
                Text("Native estimator: ${state.nav?.gnss ?: "WAITING"} GNSS · position σ ${state.nav?.positionSigmaM?.let{"%.1f m".format(it)} ?: "unknown"}. Sensor speed-model calls: ${state.sensor.model.invocations}.",
                    style=MaterialTheme.typography.bodyMedium)
                Text("Map geometry and route choices are from local OpenStreetMap data. The motion is a time-compressed illustration; real-drive accuracy needs separate field evaluation.",
                    style=MaterialTheme.typography.bodyMedium)
                if(state.demo)GlassOutlinedButton(onClick={action("demo-stop")}){Text("Exit simulation")}
            }
        }
    }
}
