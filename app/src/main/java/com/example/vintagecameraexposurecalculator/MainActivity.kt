package com.example.vintageexposurecalculator // Make sure this package name matches yours

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vintageexposurecalculator.ui.theme.VintageExposureCalculatorTheme

// A data class to hold our lighting condition options for the dropdown.
// This makes managing the label and its corresponding EV value easier.
data class LightingOption(val label: String, val ev: Int)

// A list of predefined lighting conditions the user can choose from.
val lightingOptions = listOf(
    LightingOption("Sunny / Snow (EV 16)", 16),
    LightingOption("Sunny (EV 15)", 15),
    LightingOption("Slight Overcast (EV 14)", 14),
    LightingOption("Overcast (EV 13)", 13),
    LightingOption("Heavy Overcast (EV 12)", 12),
    LightingOption("Open Shade/Sunset (EV 11)", 11),
    LightingOption("Dim Indoors (EV 8)", 8)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VintageExposureCalculatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Entry point for our main UI content.
                    ExposureCalculatorScreen()
                }
            }
        }
    }
}

@Composable
fun ExposureCalculatorScreen(exposureViewModel: ExposureViewModel = viewModel()) {
    // Collect the state values from the ViewModel as state.
    // The `by` keyword delegates the property access to the State object.
    val iso by exposureViewModel.iso
    val lightingEv by exposureViewModel.lightingEv
    val aperture by exposureViewModel.aperture
    val resultText by exposureViewModel.resultText

    // A column to arrange our UI elements vertically.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp) // Increased spacing
    ) {
        Text("Vintage Exposure", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // ISO Input Field
        OutlinedTextField(
            value = iso,
            onValueChange = { exposureViewModel.onIsoChanged(it) },
            label = { Text("Film ISO") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        // Aperture Input Field
        OutlinedTextField(
            value = aperture,
            onValueChange = { exposureViewModel.onApertureChanged(it) },
            label = { Text("Aperture (f-number)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        // Lighting Condition Dropdown
        LightingDropDown(
            selectedValue = lightingEv,
            onValueSelected = { exposureViewModel.onLightingChanged(it) }
        )

        Spacer(modifier = Modifier.weight(1f)) // Pushes the result card to the bottom

        // Result Display Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SUGGESTED SHUTTER SPEED", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(resultText, fontSize = 48.sp, style = MaterialTheme.typography.displayMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightingDropDown(selectedValue: Int, onValueSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Find the full LightingOption object that matches the current EV value.
    val selectedOption = lightingOptions.find { it.ev == selectedValue } ?: lightingOptions[0]

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor().fillMaxWidth(), // Important for anchoring the dropdown
            readOnly = true,
            value = selectedOption.label,
            onValueChange = {},
            label = { Text("Lighting Condition") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            lightingOptions.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption.label) },
                    onClick = {
                        onValueSelected(selectionOption.ev)
                        expanded = false // Close the menu after selection
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp)
                )
            }
        }
    }
}
