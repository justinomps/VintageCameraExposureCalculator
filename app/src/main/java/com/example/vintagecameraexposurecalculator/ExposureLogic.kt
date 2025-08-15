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

data class ExposureCombination(
    val aperture: Double,
    val closestShutter: Int,
    val fStopDifference: Double
)

fun findClosest(target: Double, options: List<Double>): Double {
    return options.minByOrNull { abs(target - it) } ?: 0.0
}
fun findClosest(target: Int, options: List<Int>): Int {
    return options.minByOrNull { abs(target - it) } ?: 0
}

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

fun calculateAllCombinations(
    lightingEv: Int,
    iso: Double,
    profile: CameraProfile
): List<ExposureCombination> {
    if (iso <= 0 || profile.apertures.isEmpty() || profile.shutterSpeeds.isEmpty()) {
        return emptyList()
    }
    val idealEv = lightingEv + log2(iso / 100.0)
    return profile.apertures.map { aperture ->
        val idealShutterTime = aperture.pow(2) / (2.0.pow(idealEv))
        val idealShutterDenominator = (1 / idealShutterTime).roundToInt()
        val closestShutter = findClosest(idealShutterDenominator, profile.shutterSpeeds)
        val resultingEv = log2(aperture.pow(2) * closestShutter)
        val fStopDifference = resultingEv - idealEv
        ExposureCombination(
            aperture = aperture,
            closestShutter = closestShutter,
            fStopDifference = fStopDifference
        )
    }
}

/**
 * NEW: Calculates the single best overall exposure setting from a profile.
 * It finds the combination with the smallest f-stop difference.
 */
fun calculateBestOverallSetting(
    lightingEv: Int,
    iso: Double,
    profile: CameraProfile
): CalculationResult? {
    // First, get all possible combinations
    val allCombinations = calculateAllCombinations(lightingEv, iso, profile)
    if (allCombinations.isEmpty()) return null

    // Find the combination with the minimum absolute f-stop difference
    val bestCombination = allCombinations.minByOrNull { abs(it.fStopDifference) } ?: return null

    // Convert the best combination into a CalculationResult
    val resultingEv = log2(bestCombination.aperture.pow(2) * bestCombination.closestShutter)

    return CalculationResult(
        suggestedAperture = bestCombination.aperture,
        suggestedShutter = bestCombination.closestShutter,
        resultingEv = resultingEv,
        fStopDifference = bestCombination.fStopDifference
    )
}
