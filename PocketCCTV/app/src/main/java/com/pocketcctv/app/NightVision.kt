package com.pocketcctv.app

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoOutput

object NightVision {

    /** Turns any picture into a green, "night vision goggles" look. */
    fun greenFilter(): ColorMatrixColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0.36f, 0.71f, 0.13f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    fun greenPaint(): Paint = Paint().apply { colorFilter = greenFilter() }

    /** Records in black-and-white (cleaner in the dark) when the camera supports it. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun <T : VideoOutput> applyMono(
        builder: VideoCapture.Builder<T>,
        provider: ProcessCameraProvider,
        selector: CameraSelector
    ) {
        try {
            val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return
            val effects = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) ?: return
            if (effects.contains(CameraMetadata.CONTROL_EFFECT_MODE_MONO)) {
                Camera2Interop.Extender(builder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_EFFECT_MODE,
                    CameraMetadata.CONTROL_EFFECT_MODE_MONO
                )
            }
        } catch (_: Exception) {
            // Not supported on this phone: just record normally.
        }
    }
}
