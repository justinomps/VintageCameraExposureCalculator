package com.example.vintageexposurecalculator

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

data class CalculationResult(
    val suggestedAperture: Double,
    val suggestedShutter: Int,
    val resultingEv: Double,
    val fStopDifference: Double
)

/**
 * UPDATED: Added isBulb and bulbTimeInSeconds to handle long exposures.
 */
data class ExposureCombination(
    val aperture: Double,
    val closestShutter: Int,
    val fStopDifference: Double,
    val isBulb: Boolean = false,
    val bulbTimeInSeconds: Double? = null
)

fun findClosest(target: Double, options: List<Double>): Double {
    return options.minByOrNull { abs(target - it) } ?: 0.0
}
fun findClosest(target: Int, options: List<Int>): Int {
    return options.minByOrNull { abs(target - it) } ?: 0
}

// Unchanged function
fun calculateBestSetting(
    lightingEv: Int,
    iso: Double,
    profile: CameraProfile,
    fixedAperture: Double? = null,
    fixedShutter: Int? = null
): CalculationResult? {
    if (iso <= 0) return null

    val idealEv = lightingEv
    var suggestedAperture: Double = fixedAperture ?: 0.0
    var suggestedShutter: Int = fixedShutter ?: 0

    if (fixedAperture != null) {
        if (profile.shutterSpeeds.isEmpty()) return null
        val idealShutterTime = fixedAperture.pow(2) / (2.0.pow(idealEv))
        val idealShutterDenominator = (1 / idealShutterTime).roundToInt()
        suggestedShutter = findClosest(idealShutterDenominator, profile.shutterSpeeds)
    } else if (fixedShutter != null) {
        if (profile.apertures.isEmpty() || fixedShutter <= 0) return null
        val idealAperture = kotlin.math.sqrt((2.0.pow(idealEv)) / fixedShutter)
        suggestedAperture = findClosest(idealAperture, profile.apertures)
    } else {
        return null
    }

    if (suggestedShutter <= 0) return null

    val resultingEv = log2(suggestedAperture.pow(2) * suggestedShutter)
    val fStopDifference = resultingEv - idealEv

    return CalculationResult(
        suggestedAperture = suggestedAperture,
        suggestedShutter = suggestedShutter,
        resultingEv = resultingEv,
        fStopDifference = fStopDifference
    )
}

/**
 * UPDATED: This function now identifies when a calculated exposure time
 * is longer than the camera's slowest shutter speed and flags it as a "Bulb" exposure.
 */
fun calculateAllCombinations(
    lightingEv: Int,
    iso: Double,
    profile: CameraProfile
): List<ExposureCombination> {
    if (iso <= 0 || profile.apertures.isEmpty()) {
        return emptyList()
    }
    val idealEv = lightingEv
    // The smallest denominator is the slowest shutter speed (e.g., 1 for 1s)
    val slowestShutterSpeedDenominator = profile.shutterSpeeds.minOrNull()

    return profile.apertures.map { aperture ->
        // This is the ideal exposure time in seconds (e.g., 0.5s, 4.0s)
        val idealShutterTime = aperture.pow(2) / (2.0.pow(idealEv))

        // Determine if this should be a bulb exposure
        val isBulbExposure = slowestShutterSpeedDenominator == null || idealShutterTime > (1.0 / slowestShutterSpeedDenominator)

        if (isBulbExposure) {
            // It's a Bulb exposure.
            ExposureCombination(
                aperture = aperture,
                closestShutter = 0, // Not applicable
                fStopDifference = 0.0, // It's the "perfect" time
                isBulb = true,
                bulbTimeInSeconds = idealShutterTime
            )
        } else {
            // It's a standard shutter speed exposure.
            val idealShutterDenominator = (1 / idealShutterTime).roundToInt()
            val closestShutter = findClosest(idealShutterDenominator, profile.shutterSpeeds)
            val resultingEv = log2(aperture.pow(2) * closestShutter)
            val fStopDifference = resultingEv - idealEv
            ExposureCombination(
                aperture = aperture,
                closestShutter = closestShutter,
                fStopDifference = fStopDifference,
                isBulb = false,
                bulbTimeInSeconds = null
            )
        }
    }
}

// Unchanged function
fun calculateBestOverallSetting(
    lightingEv: Int,
    iso: Double,
    profile: CameraProfile
): CalculationResult? {
    val allCombinations = calculateAllCombinations(lightingEv, iso, profile)
    if (allCombinations.isEmpty()) return null

    // Find the non-bulb combination with the minimum f-stop difference
    val bestCombination = allCombinations.filter { !it.isBulb }.minByOrNull { abs(it.fStopDifference) } ?: return null

    val resultingEv = log2(bestCombination.aperture.pow(2) * bestCombination.closestShutter)

    return CalculationResult(
        suggestedAperture = bestCombination.aperture,
        suggestedShutter = bestCombination.closestShutter,
        resultingEv = resultingEv,
        fStopDifference = bestCombination.fStopDifference
    )
}