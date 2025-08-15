package com.example.vintageexposurecalculator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add // <-- THIS IMPORT WAS MISSING
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.vintageexposurecalculator.ui.theme.VintageExposureCalculatorTheme
import java.util.Locale
import kotlin.math.abs
import androidx.activity.compose.rememberLauncherForActivityResult

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

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VintageExposureCalculatorTheme {
                // NEW: Set up navigation between screens
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
            Text("Vintage Exposure", style = MaterialTheme.typography.headlineLarge)
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
                // UPDATED: Button now navigates to the manage screen
                IconButton(onClick = onManageProfilesClicked) {
                    Icon(Icons.Default.Edit, contentDescription = "Manage Camera Profiles")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        // ... rest of the UI items (ISO, Mode Toggle, etc.) ...
        item {
            OutlinedTextField(
                value = iso,
                onValueChange = { exposureViewModel.onIsoChanged(it) },
                label = { Text("Film ISO") },
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
                            }
                        },
                        selected = label == selectedMode
                    ) { Text(label) }
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
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasCameraPermission) {
                        CameraPreview(
                            onEvCalculated = { ev -> exposureViewModel.onEvChanged(ev) },
                            iso = iso.toDoubleOrNull() ?: 100.0
                        )
                        Box(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(text = "Live EV: $currentEv", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("Camera permission needed for Live Meter.")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SettingDropDown(
                        label = "Aperture",
                        options = exposureViewModel.selectedProfile?.apertures?.map { "f/$it" } ?: emptyList(),
                        selectedValue = selectedAperture?.let { "f/$it" },
                        onValueSelected = { value -> value.substringAfter('/').toDoubleOrNull()?.let { exposureViewModel.onApertureSelected(it) } },
                        enabled = selectedShutter == null,
                        onClear = { exposureViewModel.clearApertureSelection() }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    SettingDropDown(
                        label = "Shutter Speed",
                        options = exposureViewModel.selectedProfile?.shutterSpeeds?.map { "1/${it}s" } ?: emptyList(),
                        selectedValue = selectedShutter?.let { "1/${it}s" },
                        onValueSelected = { value -> value.substringAfter('/').substringBefore('s').toIntOrNull()?.let { exposureViewModel.onShutterSelected(it) } },
                        enabled = selectedAperture == null,
                        onClear = { exposureViewModel.clearShutterSelection() }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            ResultCard(result = result ?: bestOverallResult)
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (allCombinations.isNotEmpty()) {
            item {
                AllCombinationsCard(combinations = allCombinations)
            }
        }
    }
}

// --- NEW: Manage Profiles Screen ---
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
        topBar = {
            TopAppBar(
                title = { Text("Manage Profiles") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProfile) {
                Icon(Icons.Default.Add, contentDescription = "Add Profile")
            }
        }
    ) { paddingValues ->
        LazyColumn(contentPadding = paddingValues) {
            items(profiles) { profile ->
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = { Text("${profile.apertures.size} apertures, ${profile.shutterSpeeds.size} shutter speeds") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onEditProfile(profile.id) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { exposureViewModel.deleteCameraProfile(profile.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                )
                Divider()
            }
        }
    }
}

// --- NEW: Profile Edit Screen ---
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
        topBar = {
            TopAppBar(
                title = { Text(if (profileToEdit == null) "Add Profile" else "Edit Profile") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Camera Name") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apertures,
                onValueChange = { apertures = it },
                label = { Text("Apertures (comma-separated)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = shutterSpeeds,
                onValueChange = { shutterSpeeds = it },
                label = { Text("Shutter Speeds (comma-separated)") },
                modifier = Modifier.fillMaxWidth()
            )
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}


// ... (ResultCard, AllCombinationsCard, and other composables remain the same) ...
@Composable
fun ResultCard(result: CalculationResult?) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (result == null) {
                Text("Enter ISO and select a profile.", modifier = Modifier.padding(24.dp))
            } else {
                Text("BEST EXPOSURE", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                Text(
                    text = stopText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (abs(stopDiff) < 0.1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
@Composable
fun AllCombinationsCard(combinations: List<ExposureCombination>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Exposure Options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Aperture", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Shutter", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Correction", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            combinations.forEach { combo ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("f/${combo.aperture}", modifier = Modifier.weight(1f))
                    Text("1/${combo.closestShutter}s", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    val stopDiff = combo.fStopDifference
                    val stopText = when {
                        abs(stopDiff) < 0.1 -> "Perfect"
                        stopDiff > 0 -> "+%.1f".format(Locale.US, abs(stopDiff))
                        else -> "-%.1f".format(Locale.US, abs(stopDiff))
                    }
                    Text(text = stopText, modifier = Modifier.weight(1f), textAlign = TextAlign.End, color = if (abs(stopDiff) < 0.1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
        OutlinedTextField(
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            value = selectedValue ?: "",
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = {
                if (selectedValue != null) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "Clear selection") }
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            enabled = enabled
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onValueSelected(option)
                    expanded = false
                })
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
        OutlinedTextField(modifier = Modifier.menuAnchor().fillMaxWidth(), readOnly = true, value = selectedProfile?.name ?: "No Profile Selected", onValueChange = {}, label = { Text("Camera Profile") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) })
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name) }, onClick = {
                    onProfileSelected(profile.id)
                    expanded = false
                }, contentPadding = PaddingValues(horizontal = 16.dp))
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
        OutlinedTextField(modifier = Modifier.menuAnchor().fillMaxWidth(), readOnly = true, value = selectedOption.label, onValueChange = {}, label = { Text("Lighting Condition") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) })
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            lightingOptions.forEach { selectionOption ->
                DropdownMenuItem(text = { Text(selectionOption.label) }, onClick = {
                    onValueSelected(selectionOption.ev)
                    expanded = false
                }, contentPadding = PaddingValues(horizontal = 16.dp))
            }
        }
    }
}
