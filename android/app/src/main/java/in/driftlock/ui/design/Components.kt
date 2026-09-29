package `in`.driftlock.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.driftlock.ui.data.*

@Composable
fun DataSourceBadge(source: DataSource) {
    val color = when (source) {
        DataSource.REAL -> Mint
        DataSource.DEMO -> DemoBlue
        DataSource.PROPOSED -> Amber
        DataSource.UNAVAILABLE -> Muted
    }
    Surface(color = color.copy(alpha = .09f), contentColor = color, shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color.copy(alpha = .3f))) {
        Text(source.label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Panel(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface {
        Column(Modifier.fillMaxWidth().padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
            content()
            HorizontalDivider(color = Outline.copy(alpha = .65f))
        }
    }
}
@Composable
fun UnavailableState(message: String = "Waiting for Android NavigationState.") {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("UNAVAILABLE", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
fun ValueField(label: String, field: UiValue<String>, helper: String? = null, prominent: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(field.value ?: "UNAVAILABLE", style = if (prominent && field.value != null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium)
        // UNAVAILABLE is already spelled out above, avoiding duplicate badges for missing fields.
        if (field.value != null) DataSourceBadge(field.source)
        if (helper != null) Text(helper, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
fun FeedBanner(state: NavigationUiState) {
    if (state.feedStatus == FeedStatus.READY) return
    val title = when (state.feedStatus) {
        FeedStatus.LOADING -> "Loading NavigationState"
        FeedStatus.UNAVAILABLE -> "NavigationState: UNAVAILABLE"
        FeedStatus.ERROR -> "Navigation connection error"
        FeedStatus.NO_DATA -> "No navigation data"
        FeedStatus.READY -> return
    }
    GlassSurface(elevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(Space.md), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            if (state.feedStatus == FeedStatus.LOADING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(if (state.feedStatus == FeedStatus.ERROR) Icons.Outlined.WarningAmber else Icons.Outlined.Info, contentDescription = null, tint = Amber)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(state.message?.takeIf { it.isNotBlank() } ?: "Waiting for Android NavigationState. Missing inputs will be connected later.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
    }
}

@Composable
fun GNSSStatusCard(state: NavigationUiState) {
    Panel("GNSS") {
        val gnss = state.gnssStatus
        Text(gnss.value?.label ?: "UNAVAILABLE", style = MaterialTheme.typography.headlineSmall)
        if (gnss.value != null) DataSourceBadge(gnss.source)
        when (gnss.value) {
            GnssStatus.DEGRADING -> Text("Warning · GNSS is degrading.", color = Amber)
            GnssStatus.LOST -> Text("GNSS lost. DR operation depends on the navigation core.", color = Amber)
            GnssStatus.RETURNING -> Text("GNSS detected. Waiting for the supplied recovery state.", color = Amber)
            null -> Text("Waiting for Android GNSS status.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            else -> Unit
        }
        ValueField("Position source", state.positionSource)
        ValueField("DR status", state.drStatus)
    }
}

@Composable
fun MapPlaceholder(modifier: Modifier = Modifier) {
    var info by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .78f)) {
        Column(Modifier.heightIn(min = 190.dp).padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Map, null, Modifier.size(32.dp), tint = Muted)
            Text("MAP DATA UNAVAILABLE", style = MaterialTheme.typography.labelMedium)
            Text("Waiting for map and position data.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { info = true }) { Text("Map details") }
        }
    }
    if (info) AlertDialog(onDismissRequest = { info = false },
        title = { Text("Map connection") },
        text = { Text("Map tiles, position and road geometry are UNAVAILABLE. The map renderer will connect here when supplied. No roads or location markers are invented.") },
        confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } })
}
@Composable
fun ConfidenceHorizon(state: NavigationUiState) {
    Panel("CONFIDENCE HORIZON") {
        GlassSurface(elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("Position trust", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Text(state.confidenceHorizon.value ?: "UNAVAILABLE", style = MaterialTheme.typography.headlineSmall)
                if (state.confidenceHorizon.value != null) DataSourceBadge(state.confidenceHorizon.source)
                Text(if (state.confidenceHorizon.value == null) "Waiting for navigation uncertainty." else "Confidence horizon supplied by the navigation core.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        ValueField("Navigation uncertainty", state.uncertainty)
        ValueField("Confidence state", state.confidenceState)
        val samples = state.uncertaintyOverTime.value
        if (samples == null) ValueField("Uncertainty over time", UiValue())
        else {
            DataSourceBadge(state.uncertaintyOverTime.source)
            if (samples.isEmpty()) Text("No uncertainty samples supplied.", color = Muted)
            samples.forEach { Text("${it.timeLabel} · ${it.uncertaintyLabel}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun RoadHypothesisList(hypotheses: UiValue<List<RoadHypothesis>>) {
    Text("Alternative road hypotheses", style = MaterialTheme.typography.titleMedium)
    if (hypotheses.value == null) {
        (1..2).forEach { slot ->
            InstrumentPair(
                { ValueField("Alternative $slot", UiValue()) },
                { ValueField("Probability", UiValue()) }
            )
            HorizontalDivider(color = Outline)
        }
        Text("Waiting for road-lock output. These slots are not candidate roads.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    } else {
        DataSourceBadge(hypotheses.source)
        if (hypotheses.value.isEmpty()) Text("No alternative roads supplied.", color = Muted)
        hypotheses.value.forEachIndexed { index, road ->
            InstrumentPair(
                { ValueField("Alternative ${index + 1}", road.road) },
                { ValueField("Probability", road.probability) }
            )
            HorizontalDivider(color = Outline)
        }
    }
}

@Composable
fun RoadLockCard(state: NavigationUiState) {
    Panel("Road lock", "Selected segment and supplied probabilities") {
        GlassSurface(elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(Space.md)) { ValueField("Selected road", state.selectedRoad) }
        }
        InstrumentPair(
            { ValueField("Road-lock status", state.roadLockStatus) },
            { ValueField("Road probability", state.roadProbability) }
        )
        HorizontalDivider(color = Outline)
        RoadHypothesisList(state.roadHypotheses)
    }
}
@Composable
fun RecoveryCard(state: NavigationUiState) {
    Panel("Recovery") {
        ValueField("Recovery state", state.recoveryState)
        DataSourceBadge(DataSource.PROPOSED)
        Text("Recovery flow · reference only", color = Muted, style = MaterialTheme.typography.bodyMedium)
        ReferenceFlow(listOf("GNSS LOST", "DR ACTIVE", "GNSS DETECTED", "RECOVERING", "STABLE"))
        Text("Stages above do not indicate current progress. The navigation core supplies recovery state.", style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}
