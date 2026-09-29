package `in`.driftlock.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import `in`.driftlock.ui.data.*
import `in`.driftlock.ui.design.*
import `in`.driftlock.ui.screens.*

@Preview(name = "Small phone / unavailable", widthDp = 320, heightDp = 700, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f)
@Preview(name = "Landscape", widthDp = 850, heightDp = 400)
@Composable
fun DriverPreview() { DriftlockTheme { DriftlockShell(NavigationUiState()) } }

@Preview(name = "Judge", widthDp = 700, heightDp = 1000)
@Composable
fun JudgePreview() { DriftlockTheme { Surface { JudgeScreen(NavigationUiState()) } } }

@Preview(name = "Demo / simulated", widthDp = 360, heightDp = 900)
@Composable
fun DemoPreview() { DriftlockTheme { Surface { DemoScreen(DemoPresentation().select(DemoAction.CUT_GNSS), "UNAVAILABLE — ANDROID CALLBACK REQUIRED", false, {}, {}, {}, {}) } } }

@Preview(name = "Connection error", widthDp = 320)
@Composable
fun ErrorPreview() { DriftlockTheme { FeedBanner(NavigationUiState(feedStatus = FeedStatus.ERROR)) } }

@Preview(name = "System initializing / simulated", widthDp = 360)
@Composable
fun CalibrationInitializingPreview() { DriftlockTheme { Surface { CalibrationCard(NavigationUiState(calibrationState = UiValue.demo("System initializing"))) } } }

@Preview(name = "Ready / simulated", widthDp = 360)
@Composable
fun CalibrationReadyPreview() { DriftlockTheme { Surface { CalibrationCard(NavigationUiState(calibrationState = UiValue.demo("Ready"))) } } }

@Preview(name = "Driver loading", widthDp = 360, heightDp = 800)
@Composable
fun DriverLoadingPreview() { DriftlockTheme { DriftlockShell(NavigationUiState(feedStatus = FeedStatus.LOADING)) } }

@Preview(name = "Driver no data", widthDp = 360, heightDp = 800)
@Composable
fun DriverNoDataPreview() { DriftlockTheme { DriftlockShell(NavigationUiState(feedStatus = FeedStatus.NO_DATA)) } }
