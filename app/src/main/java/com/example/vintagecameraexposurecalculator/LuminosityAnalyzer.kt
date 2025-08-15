package com.example.vintageexposurecalculator

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.log2

/**
 * An analyzer that calculates the average luminosity of an image frame.
 * @param onLuminosityCalculated A callback function to pass the calculated luma value.
 */
class LuminosityAnalyzer(private val onLuminosityCalculated: (Double) -> Unit) : ImageAnalysis.Analyzer {

    // Helper function to convert a ByteBuffer to a ByteArray
    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val data = ByteArray(remaining())
        get(data)
        return data
    }

    override fun analyze(image: ImageProxy) {
        // The image data is in the first "plane"
        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray()
        // Convert the byte data to a list of pixel brightness values (0-255)
        val pixels = data.map { it.toInt() and 0xFF }
        // Calculate the average brightness
        val luma = pixels.average()

        // Pass the result to the callback and close the image
        onLuminosityCalculated(luma)
        image.close()
    }
}

/**
 * A simplified formula to convert a luminosity value (0-255) and an ISO
 * into an Exposure Value (EV).
 * @param luminosity The average brightness from the analyzer.
 * @param iso The current film ISO.
 * @return The calculated integer EV.
 */
fun luminosityToEv(luminosity: Double, iso: Double): Int {
    if (luminosity <= 0) return 0
    // K is a standard calibration constant for reflected light meters. 12.5 is a common value.
    val k = 12.5
    // This formula relates luminance (cd/m^2) to EV at ISO 100.
    val ev100 = log2((luminosity * 100) / k)
    // Adjust the EV based on the current ISO.
    val ev = ev100 - log2(iso / 100)
    return ev.toInt()
}