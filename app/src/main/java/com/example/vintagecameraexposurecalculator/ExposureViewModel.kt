package com.example.vintageexposurecalculator

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ExposureViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("CameraProfilesPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // --- State ---
    private val _iso = mutableStateOf("100")
    // This now represents the EV from the manual dropdown
    private val _manualLightingEv = mutableStateOf(15)
    private val _cameraProfiles = mutableStateOf<List<CameraProfile>>(emptyList())
    private val _selectedProfileId = mutableStateOf<String?>(null)
    private val _selectedAperture = mutableStateOf<Double?>(null)
    private val _selectedShutter = mutableStateOf<Int?>(null)
    private val _result = mutableStateOf<CalculationResult?>(null)
    private val _allCombinations = mutableStateOf<List<ExposureCombination>>(emptyList())
    // NEW: This holds the EV value used for calculations, from either manual or live source.
    private val _currentEv = mutableStateOf(15)


    // --- Public Immutable States ---
    val iso: State<String> = _iso
    val lightingEv: State<Int> = _manualLightingEv // UI binds to the manual selection
    val cameraProfiles: State<List<CameraProfile>> = _cameraProfiles
    val selectedProfileId: State<String?> = _selectedProfileId
    val selectedAperture: State<Double?> = _selectedAperture
    val selectedShutter: State<Int?> = _selectedShutter
    val result: State<CalculationResult?> = _result
    val allCombinations: State<List<ExposureCombination>> = _allCombinations
    val currentEv: State<Int> = _currentEv // For display in Live Meter mode

    val selectedProfile: CameraProfile?
        get() = _cameraProfiles.value.find { it.id == _selectedProfileId.value }

    init {
        loadProfiles()
        recalculate()
    }

    // --- Event Handlers ---
    fun onIsoChanged(newIso: String) {
        _iso.value = newIso
        recalculate()
    }

    // Updated to set the manual EV and then call the general EV update function
    fun onLightingChanged(newEv: Int) {
        _manualLightingEv.value = newEv
        onEvChanged(newEv)
    }

    // NEW: General function to update the EV used for calculations
    fun onEvChanged(newEv: Int) {
        _currentEv.value = newEv
        recalculate()
    }

    fun onApertureSelected(aperture: Double) {
        _selectedAperture.value = aperture
        _selectedShutter.value = null
        recalculate()
    }

    fun onShutterSelected(shutter: Int) {
        _selectedShutter.value = shutter
        _selectedAperture.value = null
        recalculate()
    }

    fun clearApertureSelection() {
        _selectedAperture.value = null
        recalculate()
    }

    fun clearShutterSelection() {
        _selectedShutter.value = null
        recalculate()
    }

    fun onProfileSelected(profileId: String) {
        _selectedProfileId.value = profileId
        sharedPreferences.edit().putString("SELECTED_PROFILE_ID", profileId).apply()
        _selectedAperture.value = null
        _selectedShutter.value = null
        recalculate()
    }

    fun addCameraProfile(name: String, aperturesStr: String, shuttersStr: String) {
        try {
            val apertures = aperturesStr.split(',').mapNotNull { it.trim().toDoubleOrNull() }.sorted()
            val shutterSpeeds = shuttersStr.split(',').mapNotNull { it.trim().toIntOrNull() }.sortedDescending()

            if (name.isNotBlank() && apertures.isNotEmpty() && shutterSpeeds.isNotEmpty()) {
                val newProfile = CameraProfile(name, apertures, shutterSpeeds)
                _cameraProfiles.value += newProfile
                onProfileSelected(newProfile.id)
            }
        } catch (e: Exception) {
            Log.e("ExposureViewModel", "Failed to create new profile", e)
        }
    }

    // --- Persistence ---
    private fun saveProfiles() {
        val json = gson.toJson(_cameraProfiles.value)
        sharedPreferences.edit().putString("CAMERA_PROFILES_LIST", json).apply()
    }

    private fun loadProfiles() {
        val json = sharedPreferences.getString("CAMERA_PROFILES_LIST", null)
        val type = object : TypeToken<List<CameraProfile>>() {}.type
        val profiles: List<CameraProfile>? = gson.fromJson(json, type)

        if (profiles.isNullOrEmpty()) {
            val defaultProfile = CameraProfile(
                name = "Default Camera",
                apertures = listOf(1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0),
                shutterSpeeds = listOf(1000, 500, 250, 125, 60, 30, 15, 8, 4, 2, 1)
            )
            _cameraProfiles.value = listOf(defaultProfile)
            _selectedProfileId.value = sharedPreferences.getString("SELECTED_PROFILE_ID", defaultProfile.id)
            saveProfiles()
        } else {
            _cameraProfiles.value = profiles
            _selectedProfileId.value = sharedPreferences.getString("SELECTED_PROFILE_ID", profiles.first().id)
        }
        // Initialize the current EV with the manual one
        _currentEv.value = _manualLightingEv.value
    }

    // --- Main Calculation ---
    private fun recalculate() {
        val isoNum = _iso.value.toDoubleOrNull()
        val profile = selectedProfile

        if (isoNum == null || profile == null || (_selectedAperture.value == null && _selectedShutter.value == null)) {
            _result.value = null
        } else {
            _result.value = calculateBestSetting(
                lightingEv = _currentEv.value, // Use the current EV for all calcs
                iso = isoNum,
                profile = profile,
                fixedAperture = _selectedAperture.value,
                fixedShutter = _selectedShutter.value
            )
        }

        if (isoNum != null && profile != null) {
            _allCombinations.value = calculateAllCombinations(
                lightingEv = _currentEv.value, // Use the current EV for all calcs
                iso = isoNum,
                profile = profile
            )
        } else {
            _allCombinations.value = emptyList()
        }
    }
}
