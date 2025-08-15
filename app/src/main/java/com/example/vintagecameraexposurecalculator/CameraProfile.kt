package com.example.vintageexposurecalculator // Make sure this package name matches yours

import java.util.UUID

/**
 * A data class to represent a custom camera profile.
 *
 * @property id A unique identifier for the profile, generated automatically.
 * @property name The user-given name for the camera (e.g., "Canon AE-1").
 * @property apertures A list of the f-stop values available on this camera.
 * @property shutterSpeeds A list of the shutter speed denominators (e.g., 1000 for 1/1000s).
 */
data class CameraProfile(
    val name: String,
    val apertures: List<Double>,
    val shutterSpeeds: List<Int>,
    val id: String = UUID.randomUUID().toString() // Automatically generate a unique ID
)
