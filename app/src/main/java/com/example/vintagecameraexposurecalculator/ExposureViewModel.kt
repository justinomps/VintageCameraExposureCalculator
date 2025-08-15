package com.example.vintageexposurecalculator

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Offset
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
    private val _manualLightingEv = mutableStateOf(15)
    private val _cameraProfiles = mutableStateOf<List<CameraProfile>>(emptyList())
    private val _selectedProfileId = mutableStateOf<String?>(null)
    private val _selectedAperture = mutableStateOf<Double?>(null)
    private val _selectedShutter = mutableStateOf<Int?>(null)
    private val _result = mutableStateOf<CalculationResult?>(null)
    private val _allCombinations = mutableStateOf<List<ExposureCombination>>(emptyList())
    private val _currentEv = mutableStateOf(15)
    private val _bestOverallResult = mutableStateOf<CalculationResult?>(null)
    // NEW: State for metering mode and touch point
    private val _meteringMode = mutableStateOf(MeteringMode.AVERAGE)
    private val _spotMeteringPoint = mutableStateOf(Pair(0.5f, 0.5f)) // Default to center

    // --- Public Immutable States ---
    val iso: State<String> = _iso
    val lightingEv: State<Int> = _manualLightingEv
    val cameraProfiles: State<List<CameraProfile>> = _cameraProfiles
    val selectedProfileId: State<String?> = _selectedProfileId
    val selectedAperture: State<Double?> = _selectedAperture
    val selectedShutter: State<Int?> = _selectedShutter
    val result: State<CalculationResult?> = _result
    val allCombinations: State<List<ExposureCombination>> = _allCombinations
    val currentEv: State<Int> = _currentEv
    val bestOverallResult: State<CalculationResult?> = _bestOverallResult
    val meteringMode: State<MeteringMode> = _meteringMode
    val spotMeteringPoint: State<Pair<Float, Float>> = _spotMeteringPoint

    val selectedProfile: CameraProfile?
        get() = _cameraProfiles.value.find { it.id == _selectedProfileId.value }

    init {
        loadProfiles()
        recalculate()
    }

    // --- Event Handlers ---
    fun onIsoChanged(newIso: String) { _iso.value = newIso; recalculate() }
    fun onLightingChanged(newEv: Int) { _manualLightingEv.value = newEv; onEvChanged(newEv) }
    fun onEvChanged(newEv: Int) { _currentEv.value = newEv; recalculate() }
    fun onApertureSelected(aperture: Double) { _selectedAperture.value = aperture; _selectedShutter.value = null; recalculate() }
    fun onShutterSelected(shutter: Int) { _selectedShutter.value = shutter; _selectedAperture.value = null; recalculate() }
    fun clearApertureSelection() { _selectedAperture.value = null; recalculate() }
    fun clearShutterSelection() { _selectedShutter.value = null; recalculate() }
    fun onMeteringModeChanged(mode: MeteringMode) { _meteringMode.value = mode }
    fun onTapToMeter(point: Pair<Float, Float>) { _spotMeteringPoint.value = point }

    fun onProfileSelected(profileId: String) {
        _selectedProfileId.value = profileId
        sharedPreferences.edit().putString("SELECTED_PROFILE_ID", profileId).apply()
        _selectedAperture.value = null
        _selectedShutter.value = null
        recalculate()
    }

    // --- Profile Management ---
    fun addCameraProfile(name: String, aperturesStr: String, shuttersStr: String) {
        try {
            val apertures = aperturesStr.split(',').mapNotNull { it.trim().toDoubleOrNull() }.sorted()
            val shutterSpeeds = shuttersStr.split(',').mapNotNull { it.trim().toIntOrNull() }.sortedDescending()
            if (name.isNotBlank() && apertures.isNotEmpty() && shutterSpeeds.isNotEmpty()) {
                val newProfile = CameraProfile(name, apertures, shutterSpeeds)
                _cameraProfiles.value += newProfile
                saveProfiles()
                onProfileSelected(newProfile.id)
            }
        } catch (e: Exception) { Log.e("ViewModel", "Failed to create profile", e) }
    }

    fun updateCameraProfile(profileId: String, name: String, aperturesStr: String, shuttersStr: String) {
        try {
            val apertures = aperturesStr.split(',').mapNotNull { it.trim().toDoubleOrNull() }.sorted()
            val shutterSpeeds = shuttersStr.split(',').mapNotNull { it.trim().toIntOrNull() }.sortedDescending()
            if (name.isNotBlank() && apertures.isNotEmpty() && shutterSpeeds.isNotEmpty()) {
                val updatedProfiles = _cameraProfiles.value.map {
                    if (it.id == profileId) it.copy(name = name, apertures = apertures, shutterSpeeds = shutterSpeeds) else it
                }
                _cameraProfiles.value = updatedProfiles
                saveProfiles()
            }
        } catch (e: Exception) { Log.e("ViewModel", "Failed to update profile", e) }
    }

    fun deleteCameraProfile(profileId: String) {
        _cameraProfiles.value = _cameraProfiles.value.filterNot { it.id == profileId }
        if (_selectedProfileId.value == profileId) {
            onProfileSelected(_cameraProfiles.value.firstOrNull()?.id ?: "")
        }
        saveProfiles()
    }

    // --- Persistence & Calculation ---
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
            _selectedProfileId.value = sharedPreferences.getString("SELECTED_PROFILE_ID", profiles.firstOrNull()?.id)
        }
        _currentEv.value = _manualLightingEv.value
    }

    private fun recalculate() {
        val isoNum = _iso.value.toDoubleOrNull()
        val profile = selectedProfile
        if (isoNum != null && profile != null && (_selectedAperture.value != null || _selectedShutter.value != null)) {
            _result.value = calculateBestSetting(_currentEv.value, isoNum, profile, _selectedAperture.value, _selectedShutter.value)
        } else {
            _result.value = null
        }
        if (isoNum != null && profile != null) {
            _allCombinations.value = calculateAllCombinations(_currentEv.value, isoNum, profile)
            _bestOverallResult.value = calculateBestOverallSetting(_currentEv.value, isoNum, profile)
        } else {
            _allCombinations.value = emptyList()
            _bestOverallResult.value = null
        }
    }
}
