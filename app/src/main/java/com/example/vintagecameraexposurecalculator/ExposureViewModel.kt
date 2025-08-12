package com.example.vintageexposurecalculator // Make sure this package name matches yours

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class ExposureViewModel : ViewModel() {
    // --- Private Mutable States ---
    // These hold the actual data and can be changed from within the ViewModel.
    // Using mutableStateOf ensures that Compose will automatically observe changes.
    private val _iso = mutableStateOf("100")
    private val _lightingEv = mutableStateOf(15) // Default to Sunny (EV 15)
    private val _aperture = mutableStateOf("16.0")
    // CORRECTED: Changed "mutableState of" to "mutableStateOf"
    private val _resultText = mutableStateOf("-")

    // --- Public Immutable States ---
    // The UI will observe these. They are of type State<T> so they can't be
    // changed directly from the UI, enforcing a unidirectional data flow.
    val iso: State<String> = _iso
    val lightingEv: State<Int> = _lightingEv
    val aperture: State<String> = _aperture
    val resultText: State<String> = _resultText

    // The init block runs when the ViewModel is first created.
    // We call recalculate() here to set the initial result text.
    init {
        recalculate()
    }

    // --- Event Handlers ---
    // These public functions are called by the UI to signal user actions.

    fun onIsoChanged(newIso: String) {
        _iso.value = newIso
        recalculate()
    }

    fun onLightingChanged(newEv: Int) {
        _lightingEv.value = newEv
        recalculate()
    }

    fun onApertureChanged(newAperture: String) {
        _aperture.value = newAperture
        recalculate()
    }

    // --- The Main Calculation Logic ---
    // This private function is called whenever a state changes.
    private fun recalculate() {
        // Safely convert text inputs to numbers. Use default values if conversion fails.
        val isoNum = _iso.value.toDoubleOrNull() ?: 100.0
        val apertureNum = _aperture.value.toDoubleOrNull() ?: 0.0

        // Call the pure function from ExposureLogic.kt to get the new result.
        _resultText.value = calculateShutterSpeed(
            lightingEv = _lightingEv.value,
            iso = isoNum,
            aperture = apertureNum
        )
    }
}
