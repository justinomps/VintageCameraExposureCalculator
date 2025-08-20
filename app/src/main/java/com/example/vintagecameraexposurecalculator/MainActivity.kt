package com.example.vintageexposurecalculator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.vintageexposurecalculator.ui.theme.VintageExposureCalculatorTheme
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.log2

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

    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCameraPermission = isGranted }
    )
    val modes = listOf("Manual EV", "Live Meter")
    var selectedMode by remember { mutableStateOf(modes.first()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Text("VINTAGE EXPOSURE", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
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
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        onClick = {
                            if (label == "Live Meter" && !hasCameraPermission) {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            selectedMode = label
                            if (label == "Manual EV") {
                                exposureViewModel.onLightingChanged(lightingEv)
                                exposureViewModel.stopIncidentMetering() // Stop sensor when switching away
                            }
                        },
                        selected = label == selectedMode,
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
            if (selectedMode == "Manual EV") {
                LightingDropDown(
                    selectedValue = lightingEv,
                    onValueSelected = { exposureViewModel.onLightingChanged(it) }
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
                            .clipToBounds(), // <-- CORRECTED LINE
                        contentAlignment = Alignment.Center
                    ) {
                        if (meteringMode == MeteringMode.AVERAGE || meteringMode == MeteringMode.SPOT) {
                            if (hasCameraPermission) {
                                CameraView(
                                    onEvCalculated = { ev -> exposureViewModel.onEvChanged(ev) },
                                    iso = iso.toDoubleOrNull() ?: 100.0,
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
                                                x = (spotMeteringPoint.first * 200 - 100).dp, // Approximation for UI
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
                                        text = "LIVE EV: $currentEv",
                                        color = MaterialTheme.colorScheme.onBackground,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            } else {
                                Text("Camera permission needed for Live Meter.")
                            }
                        } else { // Incident Mode
                            Column(
                                modifier = Modifier.fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "Point front sensor towards light source",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "EV ${exposureViewModel.incidentLightingEv.value}",
                                    style = MaterialTheme.typography.displayLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
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

// --- Manage Profiles Screen ---
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

// --- Profile Edit Screen ---
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


// --- Reusable Composables ---
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
                    Text("1/${combo.closestShutter}s", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    val stopDiff = combo.fStopDifference
                    val stopText = when {
                        abs(stopDiff) < 0.1 -> "Perfect"
                        stopDiff > 0 -> "+%.1f".format(Locale.US, abs(stopDiff))
                        else -> "-%.1f".format(Locale.US, abs(stopDiff))
                    }
                    Text(text = stopText, modifier = Modifier.weight(1f), textAlign = TextAlign.End, color = if (abs(stopDiff) < 0.1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge)
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
    val selectedOption = lightingOptions.find { it.ev == selectedValue } ?: lightingOptions[0]
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


// --- CameraView Composable ---
@Composable
fun CameraView(
    onEvCalculated: (Int) -> Unit,
    iso: Double,
    meteringMode: MeteringMode,
    spotMeteringPoint: Pair<Float, Float>,
    onTapToMeter: (Offset, IntSize) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var viewSize by remember { mutableStateOf(IntSize(0, 0)) }

    val previewView = remember { PreviewView(context) }

    LaunchedEffect(iso, meteringMode, spotMeteringPoint) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer(meteringMode, spotMeteringPoint) { luma ->
                    val ev = luminosityToEv(luma, iso)
                    onEvCalculated(ev)
                })
            }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        } catch (exc: Exception) {
            Log.e("CameraView", "Use case binding failed", exc)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    viewSize = size
                    onTapToMeter(offset, size)
                }
            }
    )
}

// --- LuminosityAnalyzer Class ---
class LuminosityAnalyzer(
    private val meteringMode: MeteringMode,
    private val spotMeteringPoint: Pair<Float, Float>,
    private val onLuminosityCalculated: (Double) -> Unit
) : ImageAnalysis.Analyzer {

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val data = ByteArray(remaining())
        get(data)
        return data
    }

    override fun analyze(image: ImageProxy) {
        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray()
        val pixels = data.map { it.toInt() and 0xFF }

        val luma = when (meteringMode) {
            MeteringMode.AVERAGE -> pixels.average()
            MeteringMode.SPOT -> {
                val spotWidth = image.width * 0.1
                val spotHeight = image.height * 0.1
                val spotX = (spotMeteringPoint.first * image.width) - (spotWidth / 2)
                val spotY = (spotMeteringPoint.second * image.height) - (spotHeight / 2)
                var totalLuma = 0.0
                var pixelCount = 0
                for (y in spotY.toInt().. (spotY + spotHeight).toInt()) {
                    for (x in spotX.toInt().. (spotX + spotWidth).toInt()) {
                        if (x >= 0 && x < image.width && y >= 0 && y < image.height) {
                            totalLuma += pixels[y * image.width + x]
                            pixelCount++
                        }
                    }
                }
                if (pixelCount > 0) totalLuma / pixelCount else 0.0
            }
            // Incident is handled by the sensor, not here
            MeteringMode.INCIDENT -> 0.0
        }
        onLuminosityCalculated(luma)
        image.close()
    }
}

fun luminosityToEv(luminosity: Double, iso: Double): Int {
    if (luminosity <= 0) return 0
    // The constant K for reflected light meters is typically 12.5.
    // The formula is EV = log₂(luminosity * 100 / K)
    val k = 12.5
    val ev100 = log2((luminosity * 100) / k)
    // Pass the EV₁₀₀ directly to the ViewModel.
    // The exposure calculation later will handle the user's selected ISO.
    return ev100.toInt()
}
