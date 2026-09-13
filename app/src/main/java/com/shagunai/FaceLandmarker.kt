package com.shagunai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.max

class FaceLandmarker(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    companion object {
        const val INPUT_SIZE = 256
    }

    fun detect(frame: Bitmap): Array<FloatArray>? {
        val w = frame.width
        val h = frame.height
        
        // 1. Letterbox to 256x256
        val scale = INPUT_SIZE.toFloat() / max(h, w)
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - scaledW) / 2f
        val padY = (INPUT_SIZE - scaledH) / 2f

        val scaled = Bitmap.createScaledBitmap(frame, scaledW, scaledH, true)
        val letterbox = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterbox)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, padX, padY, null)
        scaled.recycle()

        // 2. Preprocess: RGB, [0, 1] range (Crucial for Landmarker model!)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        letterbox.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        letterbox.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = ((p shr 16) and 0xFF) / 255.0f
            input[plane + i] = ((p shr 8) and 0xFF) / 255.0f
            input[2 * plane + i] = (p and 0xFF) / 255.0f
        }

        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        val results = session.run(mapOf("input" to tensor))
        try {
            // Output: landmarks [1, 478, 3]
            val rawLandmarks = (results.get("landmarks").get().value as Array<Array<FloatArray>>)[0]
            val score = (results.get("score").get().value as Array<FloatArray>)[0][0]

            if (score < 0.5f) return null

            // 3. Map back to original image coordinates
            return Array(478) { i ->
                val nx = rawLandmarks[i][0]
                val ny = rawLandmarks[i][1]
                val nz = rawLandmarks[i][2]
                
                val origX = (nx * INPUT_SIZE - padX) / scale
                val origY = (ny * INPUT_SIZE - padY) / scale
                
                floatArrayOf(origX, origY, nz)
            }
        } finally {
            results.close()
            tensor.close()
        }
    }
}
