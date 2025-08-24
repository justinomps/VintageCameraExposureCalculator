package com.example.vintageexposurecalculator

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.log2
import kotlin.math.roundToInt

enum class UIMode {
    MANUAL, LIVE
}

class ExposureViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sharedPreferences = application.getSharedPreferences("CameraProfilesPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    // --- State ---
    private val _iso = mutableStateOf("100")
    private val _manualLightingEv = mutableStateOf(15.0)
    private val _cameraProfiles = mutableStateOf<List<CameraProfile>>(emptyList())
    private val _selectedProfileId = mutableStateOf<String?>(null)
    private val _selectedAperture = mutableStateOf<Double?>(null)
    private val _selectedShutter = mutableStateOf<Int?>(null)
    private val _result = mutableStateOf<CalculationResult?>(null)
    private val _allCombinations = mutableStateOf<List<ExposureCombination>>(emptyList())
    private val _currentEv = mutableStateOf(15.0)
    private val _bestOverallResult = mutableStateOf<CalculationResult?>(null)
    private val _meteringMode = mutableStateOf(MeteringMode.AVERAGE)
    private val _spotMeteringPoint = mutableStateOf(Pair(0.5f, 0.5f))
    private val _incidentLightingEv = mutableStateOf(15.0)
    // MODIFIED: Create separate adjustments for camera and incident sensor
    private val _cameraEvAdjustment = mutableStateOf(0)
    private val _incidentEvAdjustment = mutableStateOf(0)
    private val _uiMode = mutableStateOf(UIMode.MANUAL)
    private val _isLightSensorAvailable = mutableStateOf(false)
    private val _areInputsValid = mutableStateOf(false)


    // --- Public Immutable States ---
    val iso: State<String> = _iso
    val lightingEv: State<Double> = _manualLightingEv
    val cameraProfiles: State<List<CameraProfile>> = _cameraProfiles
    val selectedProfileId: State<String?> = _selectedProfileId
    val selectedAperture: State<Double?> = _selectedAperture
    val selectedShutter: State<Int?> = _selectedShutter
    val result: State<CalculationResult?> = _result
    val allCombinations: State<List<ExposureCombination>> = _allCombinations
    val currentEv: State<Double> = _currentEv
    val bestOverallResult: State<CalculationResult?> = _bestOverallResult
    val meteringMode: State<MeteringMode> = _meteringMode
    val spotMeteringPoint: State<Pair<Float, Float>> = _spotMeteringPoint
    val incidentLightingEv: State<Double> = _incidentLightingEv
    // MODIFIED: Expose both adjustment values
    val cameraEvAdjustment: State<Int> = _cameraEvAdjustment
    val incidentEvAdjustment: State<Int> = _incidentEvAdjustment
    val uiMode: State<UIMode> = _uiMode
    val isLightSensorAvailable: State<Boolean> = _isLightSensorAvailable
    val areInputsValid: State<Boolean> = _areInputsValid

    val selectedProfile: CameraProfile?
        get() = _cameraProfiles.value.find { it.id == _selectedProfileId.value }

    init {
        _iso.value = sharedPreferences.getString("USER_ISO", "100") ?: "100"
        _isLightSensorAvailable.value = lightSensor != null

        loadProfiles()
        // MODIFIED: Load both adjustments
        loadEvAdjustments()
        _currentEv.value = sharedPreferences.getFloat("MANUAL_EV", 15.0f).toDouble()
        _manualLightingEv.value = _currentEv.value
        recalculate()
    }

    // --- Event Handlers ---
    fun onIsoChanged(newIso: String) {
        _iso.value = newIso
        sharedPreferences.edit().putString("USER_ISO", newIso).apply()
        recalculate()
    }

    fun onLightingChanged(newEv: Double) {
        _manualLightingEv.value = newEv
        _currentEv.value = newEv
        sharedPreferences.edit().putFloat("MANUAL_EV", newEv.toFloat()).apply()
        recalculate()
    }

    // MODIFIED: Apply the correct adjustment based on the current metering mode.
    fun onEvChanged(newEv: Double) {
        val adjustment = when (_meteringMode.value) {
            MeteringMode.INCIDENT -> _incidentEvAdjustment.value
            else -> _cameraEvAdjustment.value
        }
        _currentEv.value = newEv + adjustment
        recalculate()
    }

    fun onUIModeChanged(newMode: UIMode) {
        _uiMode.value = newMode
    }

    fun onApertureSelected(aperture: Double) { _selectedAperture.value = aperture; _selectedShutter.value = null; recalculate() }
    fun onShutterSelected(shutter: Int) { _selectedShutter.value = shutter; _selectedAperture.value = null; recalculate() }
    fun clearApertureSelection() { _selectedAperture.value = null; recalculate() }
    fun clearShutterSelection() { _selectedShutter.value = null; recalculate() }
    fun onTapToMeter(point: Pair<Float, Float>) { _spotMeteringPoint.value = point }

    // MODIFIED: Update and save both adjustment values.
    fun onEvAdjustmentsChanged(cameraAdjustment: Int, incidentAdjustment: Int) {
        _cameraEvAdjustment.value = cameraAdjustment
        _incidentEvAdjustment.value = incidentAdjustment
        saveEvAdjustments()
        recalculate() // Recalculate with the new adjustment
    }


    fun onMeteringModeChanged(mode: MeteringMode) {
        _meteringMode.value = mode
        if (mode == MeteringMode.INCIDENT) {
            if (_isLightSensorAvailable.value) {
                startIncidentMetering()
            } else {
                _currentEv.value = 0.0
                recalculate()
            }
        } else {
            stopIncidentMetering()
        }
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
    }

    // MODIFIED: Save both adjustment values.
    private fun saveEvAdjustments() {
        sharedPreferences.edit()
            .putInt("CAMERA_EV_ADJUSTMENT", _cameraEvAdjustment.value)
            .putInt("INCIDENT_EV_ADJUSTMENT", _incidentEvAdjustment.value)
            .apply()
    }

    // MODIFIED: Load both adjustment values.
    private fun loadEvAdjustments() {
        _cameraEvAdjustment.value = sharedPreferences.getInt("CAMERA_EV_ADJUSTMENT", 0)
        _incidentEvAdjustment.value = sharedPreferences.getInt("INCIDENT_EV_ADJUSTMENT", 0)
    }

    private fun recalculate() {
        val isoNum = _iso.value.toDoubleOrNull()
        val profile = selectedProfile
        val evForCalc = _currentEv.value.roundToInt()

        _areInputsValid.value = isoNum != null && profile != null

        if (!_areInputsValid.value) {
            _result.value = null
            _allCombinations.value = emptyList()
            _bestOverallResult.value = null
            return
        }

        _allCombinations.value = calculateAllCombinations(evForCalc, isoNum!!, profile!!)
        _bestOverallResult.value = calculateBestOverallSetting(evForCalc, isoNum, profile)

        if (_selectedAperture.value != null || _selectedShutter.value != null) {
            _result.value = calculateBestSetting(evForCalc, isoNum, profile, _selectedAperture.value, _selectedShutter.value)
        } else {
            _result.value = null
        }
    }

    fun startIncidentMetering() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopIncidentMetering() {
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* Not used */ }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!_isLightSensorAvailable.value) {
            return
        }

        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            val calculatedEv = convertLuxToEv(lux, iso.value.toDoubleOrNull() ?: 100.0)
            _incidentLightingEv.value = calculatedEv
            if (meteringMode.value == MeteringMode.INCIDENT) {
                // MODIFIED: This will now use the incident-specific adjustment
                onEvChanged(calculatedEv)
            }
        }
    }

    private fun convertLuxToEv(lux: Float, iso: Double): Double {
        if (lux <= 0) return 0.0
        val c = 250.0 // Constant for light meters
        val ev = log2(lux / c) + 9.66 // Approximate conversion formula
        return ev
    }

    override fun onCleared() {
        super.onCleared()
        stopIncidentMetering()
    }
}