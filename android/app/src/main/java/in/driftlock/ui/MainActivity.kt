package `in`.driftlock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.driftlock.ui.data.*
import `in`.driftlock.ui.design.*
import `in`.driftlock.ui.screens.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val panel=if(android.os.Build.VERSION.SDK_INT>=30)display else windowManager.defaultDisplay
        val modes=panel?.supportedModes?.filter{it.physicalWidth==panel.mode.physicalWidth && it.physicalHeight==panel.mode.physicalHeight}.orEmpty()
        val rate=org.driftlock.app.preferredSmoothRefreshRate(modes.map{it.refreshRate}.toFloatArray())
        val legacyModeId=modes.firstOrNull{kotlin.math.abs(it.refreshRate-rate)<.01f}?.modeId ?: 0
        org.driftlock.app.requestSmoothRefreshRate(window,window.decorView,rate,legacyModeId)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent { DriftlockTheme {
            // A ViewGroup's vote does not propagate; vote on Compose's drawing host.
            val host=LocalView.current
            DisposableEffect(host,rate) {
                org.driftlock.app.requestSmoothRefreshRate(window,host,rate,legacyModeId)
                onDispose { if(android.os.Build.VERSION.SDK_INT>=35)host.requestedFrameRate=0f }
            }
            org.driftlock.app.NativeApp()
        } }
    }
}

/** Composition root: Android plugs its adapter/controller/map into these three arguments. */
@Composable
fun DriftlockApp(
    adapter: NavigationStateAdapter = remember { UnavailableNavigationAdapter() },
    demoController: DemoController = remember { UnavailableDemoController() },
    mapContent: @Composable (NavigationUiState) -> Unit = { MapPlaceholder() }
) {
    val model: NavigationViewModel = viewModel(factory = NavigationViewModel.Factory(adapter, demoController))
    val state by model.navigation.collectAsStateWithLifecycle()
    val demo by model.demo.collectAsStateWithLifecycle()
    val callback by model.callback.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    DriftlockShell(state, demo, callback, busy, model::perform, model::preview, model::previewRecovered, model::resetDemo, mapContent)
}

@Composable
fun DriftlockShell(
    state: NavigationUiState,
    demo: DemoPresentation = DemoPresentation(),
    callback: String = "UNAVAILABLE — ANDROID CALLBACK REQUIRED",
    busy: Boolean = false,
    onAction: (DemoAction) -> Unit = {},
    onStage: (DemoStage) -> Unit = {},
    onRecovered: () -> Unit = {},
    onReset: () -> Unit = {},
    mapContent: @Composable (NavigationUiState) -> Unit = { MapPlaceholder() }
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Driver", "Judge", "Demo")
    val icons = listOf(Icons.Outlined.DirectionsCar, Icons.Outlined.Tune, Icons.Outlined.PlayCircleOutline)
    Scaffold(
        modifier = Modifier.background(GlassTokens.backdrop),
        containerColor = Color.Transparent,
        topBar = {
            GlassSurface(shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)) {
                Column(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm)) {
                    Text("DRIFTLOCK", style = MaterialTheme.typography.titleLarge)
                    Text("SIH26168 · Navigation continuity", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
        },
        bottomBar = {
            GlassSurface(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), shape = MaterialTheme.shapes.large, elevation = 2.dp) {
                NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = mode == index, onClick = { mode = index },
                            icon = { Icon(icons[index], contentDescription = null) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mint, selectedTextColor = Mint,
                                indicatorColor = Mint.copy(alpha = .13f),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            ))
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
                when (mode) {
                    0 -> DriverScreen(state, mapContent)
                    1 -> JudgeScreen(state)
                    2 -> DemoScreen(demo, callback, busy, onAction, onStage, onRecovered, onReset)
                }
            }
        }
    }
}

