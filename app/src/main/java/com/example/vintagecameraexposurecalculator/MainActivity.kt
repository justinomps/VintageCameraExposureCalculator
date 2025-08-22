package com.example.vintageexposurecalculator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.vintageexposurecalculator.ui.theme.VintageExposureCalculatorTheme
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

// --- Data Classes and Constants ---
data class LightingOption(val label: String, val ev: Int)
val lightingOptions = listOf(
    LightingOption("Sunny / Snow (EV 16)", 16),
    LightingOption("Sunny (EV 15)", 15),
    LightingOption("Slight Overcast (EV 14)", 14),
    LightingOption("Overcast (EV 13)", 13),
    LightingOption("Heavy Overcast (EV 12)", 12),
    LightingOption("Open Shade/Sunset (EV 11)", 11),
    LightingOption("Dim Indoors (EV 8)", 8)
)
// MeteringMode is now in its own file

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VintageExposureCalculatorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    val exposureViewModel: ExposureViewModel = viewModel()

                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            ExposureCalculatorScreen(
                                exposureViewModel = exposureViewModel,
                                onManageProfilesClicked = { navController.navigate("manage_profiles") }
                            )
                        }
                        composable("manage_profiles") {
                            ManageProfilesScreen(
                                exposureViewModel = exposureViewModel,
                                onBackPressed = { navController.popBackStack() },
                                onEditProfile = { profileId -> navController.navigate("edit_profile/$profileId") },
                                onAddProfile = { navController.navigate("edit_profile/new") }
                            )
                        }
                        composable("edit_profile/{profileId}") { backStackEntry ->
                            val profileId = backStackEntry.arguments?.getString("profileId")
                            ProfileEditScreen(
                                profileId = if (profileId == "new") null else profileId,
                                exposureViewModel = exposureViewModel,
                                onSave = { navController.popBackStack() },
                                onCancel = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Main Calculator Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposureCalculatorScreen(
    exposureViewModel: ExposureViewModel,
    onManageProfilesClicked: () -> Unit
) {
    val iso by exposureViewModel.iso
    val lightingEv by exposureViewModel.lightingEv
    val cameraProfiles by exposureViewModel.cameraProfiles
    val selectedProfileId by exposureViewModel.selectedProfileId
    val selectedAperture by exposureViewModel.selectedAperture
    val selectedShutter by exposureViewModel.selectedShutter
    val result by exposureViewModel.result
    val allCombinations by exposureViewModel.allCombinations
    val currentEv by exposureViewModel.currentEv
    val bestOverallResult by exposureViewModel.bestOverallResult
    val meteringMode by exposureViewModel.meteringMode
    val spotMeteringPoint by exposureViewModel.spotMeteringPoint
    val uiMode by exposureViewModel.uiMode
    val isLightSensorAvailable by exposureViewModel.isLightSensorAvailable

    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCameraPermission = isGranted }
    )

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    if (showSettingsDialog) {
        SettingsDialog(
            currentAdjustment = exposureViewModel.evAdjustment.value,
            onDismiss = { showSettingsDialog = false },
            onSave = { newAdjustment ->
                exposureViewModel.onEvAdjustmentChanged(newAdjustment)
                showSettingsDialog = false
            }
        )
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    val formattedLiveEv = if (currentEv == 0.0 && meteringMode == MeteringMode.INCIDENT && !isLightSensorAvailable) {
        "--"
    } else {
        "%.1f".format(Locale.US, currentEv)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("DECOLUX", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = { showHelpDialog = true }) {
                    Icon(Icons.Filled.Help, contentDescription = "Help", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    CameraProfileDropDown(
                        profiles = cameraProfiles,
                        selectedProfileId = selectedProfileId,
                        onProfileSelected = { exposureViewModel.onProfileSelected(it) }
                    )
                }
                IconButton(onClick = onManageProfilesClicked) {
                    Icon(Icons.Default.Edit, contentDescription = "Manage Camera Profiles", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            ArtDecoOutlinedTextField(
                value = iso,
                onValueChange = { exposureViewModel.onIsoChanged(it) },
                label = { Text("FILM ISO") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            val modes = listOf(
                "Manual EV" to UIMode.MANUAL,
                "Live Meter" to UIMode.LIVE
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (label, mode) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        onClick = {
                            if (mode == UIMode.LIVE && !hasCameraPermission) {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            exposureViewModel.onUIModeChanged(mode)
                            if (mode == UIMode.MANUAL) {
                                exposureViewModel.onLightingChanged(lightingEv)
                                exposureViewModel.stopIncidentMetering()
                            }
                        },
                        selected = uiMode == mode,
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onBackground,
                            inactiveBorderColor = MaterialTheme.colorScheme.outline
                        )
                    ) { Text(label.uppercase()) }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            if (uiMode == UIMode.MANUAL) {
                LightingDropDown(
                    selectedValue = lightingEv.roundToInt(),
                    onValueSelected = {
                        exposureViewModel.onLightingChanged(it.toDouble())
                    }
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val meteringModes = listOf(MeteringMode.AVERAGE, MeteringMode.SPOT, MeteringMode.INCIDENT)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        meteringModes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = meteringModes.size),
                                onClick = { exposureViewModel.onMeteringModeChanged(mode) },
                                selected = mode == meteringMode,
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = MaterialTheme.colorScheme.onBackground,
                                    inactiveBorderColor = MaterialTheme.colorScheme.outline
                                )
                            ) { Text(mode.name) }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (meteringMode == MeteringMode.AVERAGE || meteringMode == MeteringMode.SPOT) {
                            if (hasCameraPermission) {
                                CameraView(
                                    onEvCalculated = { ev -> exposureViewModel.onEvChanged(ev) },
                                    meteringMode = meteringMode,
                                    spotMeteringPoint = spotMeteringPoint,
                                    onTapToMeter = { offset, size ->
                                        exposureViewModel.onTapToMeter(Pair(offset.x / size.width, offset.y / size.height))
                                    }
                                )
                                if (meteringMode == MeteringMode.SPOT) {
                                    Box(
                                        modifier = Modifier
                                            .offset(
                                                x = (spotMeteringPoint.first * 200 - 100).dp,
                                                y = (spotMeteringPoint.second * 200 - 100).dp
                                            )
                                            .size(40.dp)
                                            .border(2.dp, Color.White, CircleShape)
                                    )
                                }
                                Box(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "LIVE EV: $formattedLiveEv",
                                        color = MaterialTheme.colorScheme.onBackground,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            } else {
                                Text("Camera permission needed for Live Meter.")
                            }
                        } else { // Incident Mode
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (isLightSensorAvailable) {
                                    Text(
                                        "Point front sensor towards light source",
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "EV $formattedLiveEv",
                                        style = MaterialTheme.typography.displayLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        "Incident light sensor not available on this device.",
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "EV --",
                                        style = MaterialTheme.typography.displayLarge,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SettingDropDown(label = "Aperture", options = exposureViewModel.selectedProfile?.apertures?.map { "f/$it" } ?: emptyList(), selectedValue = selectedAperture?.let { "f/$it" }, onValueSelected = { value -> value.substringAfter('/').toDoubleOrNull()?.let { exposureViewModel.onApertureSelected(it) } }, enabled = selectedShutter == null, onClear = { exposureViewModel.clearApertureSelection() })
                }
                Box(modifier = Modifier.weight(1f)) {
                    SettingDropDown(label = "Shutter", options = exposureViewModel.selectedProfile?.shutterSpeeds?.map { "1/${it}s" } ?: emptyList(), selectedValue = selectedShutter?.let { "1/${it}s" }, onValueSelected = { value -> value.substringAfter('/').substringBefore('s').toIntOrNull()?.let { exposureViewModel.onShutterSelected(it) } }, enabled = selectedAperture == null, onClear = { exposureViewModel.clearShutterSelection() })
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            ResultCard(result = result ?: bestOverallResult)
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (allCombinations.isNotEmpty()) {
            item { AllCombinationsCard(combinations = allCombinations) }
        }
    }
}

// --- ALL COMPOSABLES AND FUNCTIONS BELOW ARE CORRECT ---

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to Use DecoLux") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HelpSection(
                    title = "1. Basic Setup",
                    content = "• Select Camera Profile: Choose the camera that matches your lens and shutter speeds.\n" +
                            "• Enter Film ISO: Input the ISO of your film. This value is saved for next time. (Note: historical glass plates typically have an ISO between 1-5)."
                )
                HelpSection(
                    title = "2. Choose a Metering Method",
                    content = "• Manual EV: Select a lighting condition from the dropdown.\n" +
                            "• Live Meter: Uses your phone's camera to measure light in real-time.\n" +
                            "  - Average: Measures the whole scene.\n" +
                            "  - Spot: Tap the preview to measure a specific point.\n" +
                            "  - Incident: Point the phone's screen toward the light source."
                )
                HelpSection(
                    title = "3. Find Your Perfect Exposure",
                    content = "• Best Exposure: Shows the most balanced setting.\n" +
                            "• Exposure Options: Shows all usable combinations, including 'Bulb' for long exposures.\n" +
                            "• Lock a Setting: Select an Aperture or Shutter to see the corresponding value."
                )
                HelpSection(
                    title = "4. How to Calibrate the Live Meter",
                    content = "For best results, calibrate the meter against a trusted external light meter.\n" +
                            "1. Meter an evenly lit surface (like a gray card) with your external meter and note the EV.\n" +
                            "2. In DecoLux, use the Live Meter to read the same surface.\n" +
                            "3. Tap the Settings icon (⚙️).\n" +
                            "4. Use the slider to adjust the app's EV to match your external meter's reading.\n" +
                            "5. Tap 'Save'. Your calibration is now stored."
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun HelpSection(title: String, content: String) {
    Column {
        Text(text = title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageProfilesScreen(
    exposureViewModel: ExposureViewModel,
    onBackPressed: () -> Unit,
    onEditProfile: (String) -> Unit,
    onAddProfile: () -> Unit
) {
    val profiles by exposureViewModel.cameraProfiles

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("MANAGE PROFILES", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddProfile,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Profile")
            }
        }
    ) { paddingValues ->
        LazyColumn(contentPadding = paddingValues) {
            items(profiles) { profile ->
                ListItem(
                    headlineContent = { Text(profile.name, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("${profile.apertures.size} apertures, ${profile.shutterSpeeds.size} shutter speeds", style = MaterialTheme.typography.labelMedium) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onEditProfile(profile.id) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { exposureViewModel.deleteCameraProfile(profile.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = MaterialTheme.colorScheme.onBackground,
                        supportingColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    profileId: String?,
    exposureViewModel: ExposureViewModel,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val profiles by exposureViewModel.cameraProfiles
    val profileToEdit = profiles.find { it.id == profileId }

    var name by remember { mutableStateOf(profileToEdit?.name ?: "") }
    var apertures by remember { mutableStateOf(profileToEdit?.apertures?.joinToString(", ") ?: "") }
    var shutterSpeeds by remember { mutableStateOf(profileToEdit?.shutterSpeeds?.joinToString(", ") ?: "") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (profileToEdit == null) "ADD PROFILE" else "EDIT PROFILE", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ArtDecoOutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("CAMERA NAME") }, modifier = Modifier.fillMaxWidth())
            ArtDecoOutlinedTextField(value = apertures, onValueChange = { apertures = it }, label = { Text("APERTURES (COMMA-SEPARATED)") }, modifier = Modifier.fillMaxWidth())
            ArtDecoOutlinedTextField(value = shutterSpeeds, onValueChange = { shutterSpeeds = it }, label = { Text("SHUTTER SPEEDS (COMMA-SEPARATED)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    if (profileId == null) {
                        exposureViewModel.addCameraProfile(name, apertures, shutterSpeeds)
                    } else {
                        exposureViewModel.updateCameraProfile(profileId, name, apertures, shutterSpeeds)
                    }
                    onSave()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("SAVE", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}


@Composable
fun ResultCard(result: CalculationResult?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null) {
                Text("ENTER ISO AND SELECT A PROFILE.", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.labelMedium)
            } else {
                Text("BEST EXPOSURE", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aperture", style = MaterialTheme.typography.labelMedium)
                        Text("f/${result.suggestedAperture}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Shutter", style = MaterialTheme.typography.labelMedium)
                        Text("1/${result.suggestedShutter}s", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    }
                }
                val stopDiff = result.fStopDifference
                val resultingEvFormatted = "%.1f".format(Locale.US, result.resultingEv)
                val stopText = when {
                    abs(stopDiff) < 0.1 -> "Perfect Exposure (EV $resultingEvFormatted)"
                    stopDiff > 0 -> "+%.1f stops (EV $resultingEvFormatted)".format(Locale.US, abs(stopDiff))
                    else -> "-%.1f stops (EV $resultingEvFormatted)".format(Locale.US, abs(stopDiff))
                }
                Text(text = stopText, style = MaterialTheme.typography.bodyLarge, color = if (abs(stopDiff) < 0.1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}
@Composable
fun AllCombinationsCard(combinations: List<ExposureCombination>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("EXPOSURE OPTIONS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Aperture", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("Shutter", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Correction", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))

            combinations.forEach { combo ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("f/${combo.aperture}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)

                    if (combo.isBulb) {
                        val bulbTime = combo.bulbTimeInSeconds ?: 0.0
                        val bulbText = if (bulbTime < 10) "%.1fs".format(Locale.US, bulbTime) else "${bulbTime.roundToInt()}s"
                        Text(
                            text = bulbText,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "1/${combo.closestShutter}s",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    val stopText = when {
                        combo.isBulb -> "Bulb"
                        abs(combo.fStopDifference) < 0.1 -> "Perfect"
                        combo.fStopDifference > 0 -> "+%.1f".format(Locale.US, abs(combo.fStopDifference))
                        else -> "-%.1f".format(Locale.US, abs(combo.fStopDifference))
                    }
                    val textColor = when {
                        combo.isBulb || abs(combo.fStopDifference) < 0.1 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    }

                    Text(
                        text = stopText,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingDropDown(label: String, options: List<String>, selectedValue: String?, onValueSelected: (String) -> Unit, enabled: Boolean, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = !expanded }) {
        ArtDecoOutlinedTextField(
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            value = selectedValue ?: "",
            onValueChange = {},
            label = { Text(label.uppercase()) },
            trailingIcon = {
                if (selectedValue != null) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "Clear selection", tint = MaterialTheme.colorScheme.primary) }
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            enabled = enabled
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        onValueSelected(option)
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraProfileDropDown(profiles: List<CameraProfile>, selectedProfileId: String?, onProfileSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = profiles.find { it.id == selectedProfileId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        ArtDecoOutlinedTextField(
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            value = selectedProfile?.name ?: "NO PROFILE SELECTED",
            onValueChange = {},
            label = { Text("CAMERA PROFILE") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        onProfileSelected(profile.id)
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightingDropDown(selectedValue: Int, onValueSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = lightingOptions.find { it.ev == selectedValue } ?: lightingOptions.first()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        ArtDecoOutlinedTextField(
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            value = selectedOption.label.uppercase(),
            onValueChange = {},
            label = { Text("LIGHTING CONDITION") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            lightingOptions.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption.label, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        onValueSelected(selectionOption.ev)
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
fun ArtDecoOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    readOnly: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        trailingIcon = trailingIcon,
        readOnly = readOnly,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onBackground,
            unfocusedTextColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
fun SettingsDialog(
    currentAdjustment: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var sliderValue by remember { mutableStateOf(currentAdjustment.toFloat()) }
    val roundedValue = sliderValue.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Live Meter Adjustment") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Adjust the calculated EV value by ${roundedValue} stops.")
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = -6f..6f,
                    steps = 11
                )
                Text(
                    text = if (roundedValue > 0) "+$roundedValue EV" else "$roundedValue EV",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(roundedValue) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CameraView(
    onEvCalculated: (Double) -> Unit,
    meteringMode: MeteringMode,
    spotMeteringPoint: Pair<Float, Float>,
    onTapToMeter: (Offset, IntSize) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val previewView = remember { PreviewView(context) }
    var camera: Camera? by remember { mutableStateOf(null) }

    LaunchedEffect(cameraProviderFuture, meteringMode, spotMeteringPoint) {
        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        Camera2Interop.Extender(imageAnalysisBuilder)
            .setSessionCaptureCallback(object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    val sensorIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    val exposureTimeNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)

                    if (sensorIso != null && exposureTimeNs != null) {
                        val calculatedEv = calculateEvFromCaptureResult(sensorIso, exposureTimeNs)
                        onEvCalculated(calculatedEv)
                    }
                }
            })

        val imageAnalysis = imageAnalysisBuilder.build().also {
            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy -> imageProxy.close() }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)

            if (meteringMode == MeteringMode.SPOT) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(spotMeteringPoint.first * previewView.width, spotMeteringPoint.second * previewView.height)
                val action = androidx.camera.core.FocusMeteringAction.Builder(point, androidx.camera.core.FocusMeteringAction.FLAG_AE).build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }

        } catch (exc: Exception) {
            Log.e("CameraView", "Use case binding failed", exc)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset, ->
                    val size = IntSize(this.size.width, this.size.height)
                    onTapToMeter(offset, size)
                }
            }
    )
}

fun calculateEvFromCaptureResult(sensorIso: Int, exposureTimeNs: Long): Double {
    if (exposureTimeNs <= 0 || sensorIso <= 0) return 0.0
    val exposureTimeSec = exposureTimeNs / 1_000_000_000.0

    val ev100 = log2(100.0 / (exposureTimeSec * sensorIso))
    return ev100
}