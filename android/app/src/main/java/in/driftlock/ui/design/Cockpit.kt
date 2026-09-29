package `in`.driftlock.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.driftlock.ui.data.*

/** Instruments adapt to text scaling rather than shrinking type or clipping values. */
@Composable
fun InstrumentPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= (240 * scale).dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { first() }
            Column(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second() }
    }
}

@Composable
fun Instrument(label: String, field: UiValue<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().animateContentSize().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(field.value ?: "UNAVAILABLE", style = if (field.value == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
            color = if (label == "GNSS" && field.value in listOf("Healthy", "Recovered")) Mint else MaterialTheme.colorScheme.onSurface)
        if (field.value != null) DataSourceBadge(field.source)
        HorizontalDivider(color = Outline)
    }
}
@Composable
fun SectionLabel(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
fun ConnectionLine(state: NavigationUiState) {
    val text = when (state.feedStatus) {
        FeedStatus.READY -> "NavigationState connected · per-field sources below"
        FeedStatus.UNAVAILABLE -> "NavigationState: UNAVAILABLE"
        FeedStatus.LOADING -> "Loading NavigationState"
        FeedStatus.ERROR -> "Navigation connection error"
        FeedStatus.NO_DATA -> "No navigation data"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.feedStatus == FeedStatus.LOADING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(if (state.feedStatus == FeedStatus.ERROR) Icons.Outlined.WarningAmber else Icons.Outlined.Info, null, Modifier.size(16.dp), tint = if (state.feedStatus == FeedStatus.ERROR) MaterialTheme.colorScheme.error else Muted)
            Text(text, style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        if (state.feedStatus == FeedStatus.ERROR) Text(state.message?.takeIf { it.isNotBlank() } ?: "Waiting for the Android connection to recover.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SignalStrip(state: NavigationUiState) {
    GlassSurface(elevation = 0.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InstrumentPair(
                { Crossfade(targetState = state.gnssStatus, animationSpec = tween(160), label = "GNSS handover") { gnss ->
                    Instrument("GNSS", UiValue(gnss.value?.label, gnss.source))
                } },
                { Instrument("DR status", state.drStatus) }
            )
            when (state.gnssStatus.value) {
                GnssStatus.DEGRADING -> Text("Warning · GNSS is degrading.", color = Amber, style = MaterialTheme.typography.bodyMedium)
                GnssStatus.LOST -> Text("GNSS lost · DR operation awaits the supplied core state.", color = Amber, style = MaterialTheme.typography.bodyMedium)
                GnssStatus.RETURNING -> Text("GNSS returning · recovery is reported separately.", color = Amber, style = MaterialTheme.typography.bodyMedium)
                else -> Unit
            }
        }
    }
}

@Composable
fun NavigationViewport(state: NavigationUiState, mapContent: @Composable (NavigationUiState) -> Unit) {
    GlassSurface(shape = MaterialTheme.shapes.large, elevation = 2.dp) {
        Column {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Mint.copy(alpha = .1f)) {
                    Icon(Icons.Outlined.Navigation, null, Modifier.padding(10.dp).size(20.dp), tint = Mint)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Current road", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Text(state.selectedRoad.value ?: "UNAVAILABLE", style = MaterialTheme.typography.titleMedium)
                    if (state.selectedRoad.value != null) DataSourceBadge(state.selectedRoad.source)
                }
            }
            HorizontalDivider(color = Outline)
            mapContent(state)
            HorizontalDivider(color = Outline)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstrumentPair(
                    { Instrument("Forward speed", state.speed) },
                    { Instrument("Heading", state.heading) }
                )
            }
        }
    }
}

@Composable
fun NavigationSummary(state: NavigationUiState) {
    GlassSurface(elevation = 0.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InstrumentPair(
                { Instrument("Road lock", state.roadLockStatus) },
                { Instrument("Confidence", state.confidenceState) }
            )
            InstrumentPair(
                { Instrument("Recovery status", state.recoveryState) },
                { Instrument("Calibration", state.calibrationState) }
            )
        }
    }
}

@Composable
fun CalibrationCard(state: NavigationUiState) {
    Panel("Initialization & calibration") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, null, tint = Mint)
            Text(if (state.calibrationState.value == null) "WAITING FOR NAVIGATION CORE" else "Supplied initialization state", style = MaterialTheme.typography.labelMedium)
        }
        ValueField("Calibration state", state.calibrationState)
        Text("Initialization status will appear here when supplied by the navigation core.", style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

/** A reference sequence, not a percentage, confidence curve or active recovery assertion. */
@Composable
fun ReferenceFlow(labels: List<String>) {
    labels.forEachIndexed { index, label ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, border = BorderStroke(1.dp, Outline), color = MaterialTheme.colorScheme.background) {
                Text((index + 1).toString().padStart(2, '0'), Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        if (index != labels.lastIndex) Box(Modifier.padding(start = 18.dp).width(1.dp).height(12.dp)) {
            HorizontalDivider(color = Outline, modifier = Modifier.fillMaxHeight())
        }
    }
}

