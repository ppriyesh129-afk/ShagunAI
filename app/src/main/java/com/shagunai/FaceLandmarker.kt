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

        if (w <= 0 || h <= 0) {
            return null
        }

        // Letterbox to 256 x 256
        val scale =
            INPUT_SIZE.toFloat() / max(h, w)

        val scaledW =
            (w * scale)
                .toInt()
                .coerceAtLeast(1)

        val scaledH =
            (h * scale)
                .toInt()
                .coerceAtLeast(1)

        val padX =
            (INPUT_SIZE - scaledW) / 2f

        val padY =
            (INPUT_SIZE - scaledH) / 2f

        val scaled =
            Bitmap.createScaledBitmap(
                frame,
                scaledW,
                scaledH,
                true
            )

        val letterbox =
            Bitmap.createBitmap(
                INPUT_SIZE,
                INPUT_SIZE,
                Bitmap.Config.ARGB_8888
            )

        try {

            val canvas = Canvas(letterbox)

            canvas.drawColor(Color.BLACK)

            canvas.drawBitmap(
                scaled,
                padX,
                padY,
                null
            )

        } finally {

            if (!scaled.isRecycled) {
                scaled.recycle()
            }
        }

        // RGB [0,1]
        val pixels =
            IntArray(
                INPUT_SIZE * INPUT_SIZE
            )

        letterbox.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        letterbox.recycle()

        val plane =
            INPUT_SIZE * INPUT_SIZE

        val input =
            FloatArray(
                plane * 3
            )

        for (i in 0 until plane) {

            val p = pixels[i]

            input[i] =
                ((p shr 16) and 0xFF) / 255.0f

            input[plane + i] =
                ((p shr 8) and 0xFF) / 255.0f

            input[(plane * 2) + i] =
                (p and 0xFF) / 255.0f
        }

        val tensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(
                    1,
                    3,
                    INPUT_SIZE.toLong(),
                    INPUT_SIZE.toLong()
                )
            )

        try {

            val results =
                session.run(
                    mapOf(
                        "input" to tensor
                    )
                )

            try {

                val landmarksValue =
                    results
                        .get("landmarks")
                        .get()
                        .value

                val scoreValue =
                    results
                        .get("score")
                        .get()
                        .value

                @Suppress("UNCHECKED_CAST")
                val rawLandmarks =
                    landmarksValue as Array<Array<FloatArray>>

                @Suppress("UNCHECKED_CAST")
                val scores =
                    scoreValue as Array<FloatArray>

                val landmarks =
                    rawLandmarks[0]

                val score =
                    scores[0][0]

                if (score < 0.5f) {
                    return null
                }

                if (landmarks.size < 478) {
                    return null
                }

                return Array(478) { i ->

                    val nx =
                        landmarks[i][0]

                    val ny =
                        landmarks[i][1]

                    val nz =
                        landmarks[i][2]

                    val origX =
                        (nx * INPUT_SIZE - padX) /
                            scale

                    val origY =
                        (ny * INPUT_SIZE - padY) /
                            scale

                    floatArrayOf(
                        origX.coerceIn(
                            0f,
                            w.toFloat()
                        ),
                        origY.coerceIn(
                            0f,
                            h.toFloat()
                        ),
                        nz
                    )
                }

            } finally {

                results.close()
            }

        } finally {

            tensor.close()
        }
    }
}
