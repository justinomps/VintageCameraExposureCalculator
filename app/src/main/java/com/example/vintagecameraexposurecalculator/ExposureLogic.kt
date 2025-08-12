package com.example.vintageexposurecalculator // Make sure this package name matches yours
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

// Default camera values, similar to your JS constants
val DEFAULT_APERTURES: List<Double> = listOf(1.0, 1.4, 1.8, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)
val DEFAULT_SHUTTERS: List<Int> = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2, 1)

/**
 * Calculates the ideal shutter speed based on the other parameters.
 * @param lightingEv The Exposure Value from the lighting condition (e.g., 15 for Sunny 16).
 * @param iso The selected film ISO.
 * @param aperture The selected f-number.
 * @return A formatted string representing the ideal shutter speed (e.g., "1 / 125s" or "2.0s").
 */
fun calculateShutterSpeed(lightingEv: Int, iso: Double, aperture: Double): String {
    if (iso <= 0 || aperture <= 0) return "-"

    // This formula combines the target EV calculation and the exposure formula
    val targetEv = lightingEv + log2(iso / 100.0)
    val shutterTime = (aperture.pow(2)) / (2.0.pow(targetEv))

    return if (shutterTime >= 1) {
        String.format("%.1fs", shutterTime)
    } else {
        "1 / ${Math.round(1 / shutterTime)}s"
    }
}