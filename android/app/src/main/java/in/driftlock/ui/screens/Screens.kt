package `in`.driftlock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import `in`.driftlock.ui.data.*
import `in`.driftlock.ui.design.*

@Composable
fun DriverScreen(state: NavigationUiState, mapContent: @Composable (NavigationUiState) -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.testTag("driver-list"), contentPadding = PaddingValues(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { ConnectionLine(state) }
        item { SignalStrip(state) }
        item { NavigationViewport(state, mapContent) }
        item { SectionLabel("Journey status") }
        item { NavigationSummary(state) }
        item { Panel("Position") {
            ValueField("Position", state.position)
            ValueField("Position source", state.positionSource)
        } }
        item { CalibrationCard(state) }
        item { OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { details = !details }, modifier = Modifier.fillMaxWidth()) {
            Text(if (details) "Hide system details" else "Show system details")
        } }
        if (details) {
            item { GNSSStatusCard(state) }
            item { RecoveryCard(state) }
            item { ConfidenceHorizon(state) }
        }
        item { Text("Missing inputs connect through NavigationState.", color = Muted, style = MaterialTheme.typography.bodyMedium) }
    }
}

enum class EngineeringSection(val label: String) {
    OVERVIEW("Overview"), ROADS("Roads"), CONFIDENCE("Confidence"), RECOVERY("Recovery"), VALIDATION("Validation")
}

@Composable
fun JudgeScreen(state: NavigationUiState) {
    var selected by rememberSaveable { mutableStateOf(EngineeringSection.OVERVIEW) }
    Column {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            SectionLabel("System observatory", "NavigationState / engineering view")
            ConnectionLine(state)
        }
        LazyRow(Modifier.testTag("engineering-tabs"), contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            items(EngineeringSection.entries) { section ->
                FilterChip(selected = selected == section, onClick = { selected = section }, label = { Text(section.label) })
            }
        }
        key(selected) {
            LazyColumn(Modifier.weight(1f).testTag("judge-list"), contentPadding = PaddingValues(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                when (selected) {
                    EngineeringSection.OVERVIEW -> {
                        item { SectionLabel("NavigationState overview", "Every field retains its own source.") }
                        item { SignalStrip(state) }
                        item { InstrumentPair(
                            { Instrument("Forward speed", state.speed) },
                            { Instrument("Heading", state.heading) }
                        ) }
                        item { Panel("Position & motion") {
                            ValueField("Position", state.position)
                            ValueField("Position source", state.positionSource)
                            ValueField("Speed uncertainty", state.speedUncertainty)
                            ValueField("Navigation uncertainty", state.uncertainty)
                        } }
                        item { NavigationSummary(state) }
                        item { Panel("ShadowDR", "Independent status from the navigation core.") {
                            ValueField("ShadowDR status", state.shadowDr)
                        } }
                        item { ProvenanceLegend() }
                    }
                    EngineeringSection.ROADS -> {
                        item { SectionLabel("Road intelligence", "Selected segment and alternative hypotheses") }
                        item { RoadLockCard(state) }
                    }
                    EngineeringSection.CONFIDENCE -> {
                        item { SectionLabel("Confidence & uncertainty", "Supplied estimates, with no UI calculations") }
                        item { ConfidenceHorizon(state) }
                    }
                    EngineeringSection.RECOVERY -> {
                        item { SectionLabel("Continuity & recovery") }
                        item { RecoveryCard(state) }
                        item { CalibrationCard(state) }
                    }
                    EngineeringSection.VALIDATION -> {
                        item { SectionLabel("Verified evidence", "Benchmark results supplied by Avi") }
                        item { Panel("Benchmark results") {
                            ValueField("Benchmark results", state.benchmarkResults)
                            ValueField("Conditions / evidence", state.benchmarkConditions)
                            ValueField("Measured drift", state.measuredDrift)
                            val metrics = state.verifiedKpis.value
                            if (metrics == null) ValueField("Verified KPI", UiValue())
                            else {
                                DataSourceBadge(state.verifiedKpis.source)
                                if (metrics.isEmpty()) Text("No verified KPIs supplied.", color = Muted)
                                metrics.forEach { ValueField(it.label, it.value) }
                            }
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvenanceLegend() {
    Panel("Data provenance") {
        listOf(
            DataSource.REAL to "Received from a connected source.",
            DataSource.UNAVAILABLE to "Input has not been supplied.",
            DataSource.DEMO to "Presentation only; not measured.",
            DataSource.PROPOSED to "Reference concept awaiting integration."
        ).forEach { (source, description) ->
            DataSourceBadge(source)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoScreen(
    demo: DemoPresentation, callback: String, busy: Boolean,
    onAction: (DemoAction) -> Unit, onStage: (DemoStage) -> Unit,
    onRecovered: () -> Unit, onReset: () -> Unit
) {
    val preview = NavigationUiState(gnssStatus = demo.gnss?.let { UiValue.demo(it) } ?: UiValue())
    LazyColumn(Modifier.testTag("demo-list"), contentPadding = PaddingValues(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            SectionLabel("Handover studio", "A guided navigation demonstration")
            Spacer(Modifier.height(Space.sm))
            DataSourceBadge(DataSource.DEMO)
        }
        item { DemoControlPanel(callback, busy, onAction, onReset) }
        item { SectionLabel("Presentation sequence", "Swipe through eight selectable stages.") }
        item {
            LazyRow(Modifier.testTag("demo-stages"), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(DemoStage.entries) { stage ->
                    FilterChip(selected = demo.stage == stage, onClick = { onStage(stage) }, enabled = !busy,
                        label = { Text("${stage.ordinal + 1} · ${stage.label}") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onStage(DemoStage.entries[(demo.stage?.ordinal ?: 0) - 1]) }, enabled = !busy && (demo.stage?.ordinal ?: 0) > 0, modifier = Modifier.weight(1f)) { Text("Previous stage") }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onStage(DemoStage.entries[(demo.stage?.ordinal ?: -1) + 1]) }, enabled = !busy && (demo.stage?.ordinal ?: -1) < DemoStage.entries.lastIndex, modifier = Modifier.weight(1f)) { Text("Next stage") }
            }
        }
        item { Panel("GNSS state preview") {
            DataSourceBadge(DataSource.DEMO)
            Text(demo.stage?.label ?: "Ready to present", style = MaterialTheme.typography.headlineSmall)
            Text(demo.stage?.let(::stageExplanation) ?: "Choose NORMAL or a sequence stage to begin. Driver and Judge remain connected only to the adapter.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            SignalStrip(preview)
            Text("GNSS labels are simulated. DR and all measurements remain UNAVAILABLE until supplied by the core.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        } }
        when (demo.stage) {
            DemoStage.ROAD_LOCK -> item { RoadLockCard(preview) }
            DemoStage.CONFIDENCE -> item { ConfidenceHorizon(preview) }
            DemoStage.RECOVERY, DemoStage.GNSS_RESTORE -> item { RecoveryCard(preview) }
            else -> item { NavigationViewport(preview) { MapPlaceholder() } }
        }
        item { NavigationSummary(preview) }
        item { Panel("Recovery preview") {
            ValueField("Recovery state", preview.recoveryState)
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onRecovered, enabled = !busy) { Text("Preview RECOVERED · simulated") }
            Text("This previews a label only. It does not report successful recovery.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoControlPanel(callback: String, busy: Boolean, onAction: (DemoAction) -> Unit, onReset: () -> Unit) {
    Panel("Demo controls") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            DemoAction.entries.forEach { action ->
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onAction(action) }, enabled = !busy) { Text(action.label) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(callback, color = if (callback.startsWith("ERROR")) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.labelMedium)
        Text("Local previews now. Connected Android callbacks receive these four commands later.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onReset) { Text("Reset local preview") }
    }
}

private fun stageExplanation(stage: DemoStage): String = when (stage) {
    DemoStage.NORMAL -> "Healthy GNSS presentation. Actual DR standby and position source remain separate core inputs."
    DemoStage.DEGRADE -> "A degrading GNSS warning. No signal strength is invented."
    DemoStage.GNSS_CUT -> "GNSS loss presentation. This local stage does not cut a sensor or activate DR."
    DemoStage.DR_ACTIVE -> "The intended DR handover. Actual DR status remains UNAVAILABLE."
    DemoStage.ROAD_LOCK -> "Road-lock output will identify the selected segment and alternative hypotheses."
    DemoStage.CONFIDENCE -> "Navigation uncertainty will populate the confidence horizon. No estimate is calculated here."
    DemoStage.GNSS_RESTORE -> "GNSS returning presentation. Recovery is reported independently by the core."
    DemoStage.RECOVERY -> "Recovery sequence reference. No stable state or recovery success is inferred."
}



