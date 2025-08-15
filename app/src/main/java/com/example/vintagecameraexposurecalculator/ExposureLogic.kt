package com.example.vintageexposurecalculator

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A data class to hold the results of a single, best-guess exposure calculation.
 */
data class CalculationResult(
    val suggestedAperture: Double,
    val suggestedShutter: Int,
    val resultingEv: Double,
    val fStopDifference: Double
)

/**
 * A data class to hold a single aperture/shutter combination from the full list.
 */
data class ExposureCombination(
    val aperture: Double,
    val closestShutter: Int,
    val fStopDifference: Double
)

/**
 * Finds the value in a list that is numerically closest to a target value.
 */
fun findClosest(target: Double, options: List<Double>): Double {
    return options.minByOrNull { abs(target - it) } ?: 0.0
}
fun findClosest(target: Int, options: List<Int>): Int {
    return options.minByOrNull { abs(target - it) } ?: 0
}

/**
 * Calculates the best available camera settings based on a fixed aperture or shutter speed.
 */
fun calculateBestSetting(
    lightingEv: Int,
    iso: Double,
    profile: CameraProfile,
    fixedAperture: Double? = null,
    fixedShutter: Int? = null
): CalculationResult? {
    if (iso <= 0) return null

    val idealEv = lightingEv + log2(iso / 100.0)
    var suggestedAperture: Double = fixedAperture ?: 0.0
    var suggestedShutter: Int = fixedShutter ?: 0

    if (fixedAperture != null) {
        if (profile.shutterSpeeds.isEmpty()) return null
        val idealShutterTime = fixedAperture.pow(2) / (2.0.pow(idealEv))
        val idealShutterDenominator = (1 / idealShutterTime).roundToInt()
        suggestedShutter = findClosest(idealShutterDenominator, profile.shutterSpeeds)
    } else if (fixedShutter != null) {
        if (profile.apertures.isEmpty()) return null
        val idealAperture = kotlin.math.sqrt(fixedShutter * (2.0.pow(idealEv)))
        suggestedAperture = findClosest(idealAperture, profile.apertures)
    } else {
        return null
    }

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
 * Calculates all possible exposure combinations for a given camera profile.
 *
 * @return A list of ExposureCombination objects, one for each aperture in the profile.
 */
fun calculateAllCombinations(
    lightingEv: Int,
    iso: Double,
    profile: CameraProfile
): List<ExposureCombination> {
    if (iso <= 0 || profile.apertures.isEmpty() || profile.shutterSpeeds.isEmpty()) {
        return emptyList()
    }

    // Calculate the ideal EV for the scene
    val idealEv = lightingEv + log2(iso / 100.0)

    // For each aperture in the profile, find the best corresponding shutter speed
    return profile.apertures.map { aperture ->
        val idealShutterTime = aperture.pow(2) / (2.0.pow(idealEv))
        val idealShutterDenominator = (1 / idealShutterTime).roundToInt()
        val closestShutter = findClosest(idealShutterDenominator, profile.shutterSpeeds)

        // Calculate the resulting EV and the difference in f-stops
        val resultingEv = log2(aperture.pow(2) * closestShutter)
        val fStopDifference = resultingEv - idealEv

        ExposureCombination(
            aperture = aperture,
            closestShutter = closestShutter,
            fStopDifference = fStopDifference
        )
    }
}
