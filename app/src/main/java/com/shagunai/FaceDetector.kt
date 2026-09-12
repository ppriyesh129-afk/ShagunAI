package com.shagunai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.exp

data class FaceDetection(
    val score: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val keypoints: Array<FloatArray> // 6 x (x,y): 0=right eye, 1=left eye, 2=nose, 3=mouth, 4/5=ears
)

class FaceDetector(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    companion object {
        const val INPUT_SIZE = 128
        const val SCORE_THRESHOLD = 0.5f
        const val IOU_THRESHOLD = 0.3f
    }

    private val anchors: Array<FloatArray> = generateAnchors()

    // MediaPipe SsdAnchorsCalculator config: strides [8,16,16,16] -> 896 anchors
    private fun generateAnchors(): Array<FloatArray> {
        val strides = intArrayOf(8, 16, 16, 16)
        val list = ArrayList<FloatArray>()
        var idx = 0
        while (idx < strides.size) {
            var last = idx
            while (last < strides.size && strides[last] == strides[idx]) last++
            val repeats = 2 * (last - idx)
            val cells = INPUT_SIZE / strides[idx]
            for (y in 0 until cells) {
                for (x in 0 until cells) {
                    val ax = (x + 0.5f) / cells
                    val ay = (y + 0.5f) / cells
                    for (r in 0 until repeats) list.add(floatArrayOf(ax, ay))
                }
            }
            idx = last
        }
        return list.toTypedArray()
    }

    fun detect(frame: Bitmap): FaceDetection? {
        val w = frame.width
        val h = frame.height
        val scale = INPUT_SIZE.toFloat() / maxOf(h, w)
        val padX = (INPUT_SIZE - w * scale) / 2f
        val padY = (INPUT_SIZE - h * scale) / 2f

        // Letterbox to 128x128 with black padding
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, scaledW, scaledH, true)
        val letterbox = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterbox)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, padX, padY, null)
        scaled.recycle()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        letterbox.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        letterbox.recycle()

        // Preprocess: RGB, [-1, 1], channel-first (CHW)
        val plane = INPUT_SIZE * INPUT_SIZE
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f            // R
            input[plane + i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f      // G
            input[2 * plane + i] = ((p and 0xFF) - 127.5f) / 127.5f          // B
        }

        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        val results = session.run(mapOf("input" to tensor))
        try {
            val reg = (results.get("regressors").get().value as Array<Array<FloatArray>>)[0]
            val logits = (results.get("scores").get().value as Array<Array<FloatArray>>)[0]

            val candidates = ArrayList<FloatArray>()
            for (i in reg.indices) {
                val score = (1.0 / (1.0 + exp(-logits[i][0].toDouble()))).toFloat() // sigmoid
                if (score < SCORE_THRESHOLD) continue
                val anchor = anchors[i]
                val row = FloatArray(17)
                row[0] = score
                row[1] = reg[i][0] / INPUT_SIZE + anchor[0] // cx
                row[2] = reg[i][1] / INPUT_SIZE + anchor[1] // cy
                row[3] = reg[i][2] / INPUT_SIZE             // w
                row[4] = reg[i][3] / INPUT_SIZE             // h
                for (k in 0 until 6) {
                    row[5 + 2 * k] = reg[i][4 + 2 * k] / INPUT_SIZE + anchor[0]
                    row[6 + 2 * k] = reg[i][5 + 2 * k] / INPUT_SIZE + anchor[1]
                }
                candidates.add(row)
            }
            if (candidates.isEmpty()) return null

            // Greedy NMS (keep strongest, drop overlaps)
            candidates.sortByDescending { it[0] }
            val kept = ArrayList<FloatArray>()
            for (c in candidates) {
                var suppress = false
                for (k in kept) if (iou(c, k) > IOU_THRESHOLD) { suppress = true; break }
                if (!suppress) kept.add(c)
            }

            val best = kept[0]
            fun unX(nx: Float) = (nx * INPUT_SIZE - padX) / scale
            fun unY(ny: Float) = (ny * INPUT_SIZE - padY) / scale

            val cx = unX(best[1]); val cy = unY(best[2])
            val bw = best[3] * INPUT_SIZE / scale
            val bh = best[4] * INPUT_SIZE / scale
            val kps = Array(6) { k -> floatArrayOf(unX(best[5 + 2 * k]), unY(best[6 + 2 * k])) }
            return FaceDetection(best[0], cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, kps)
        } finally {
            results.close()
            tensor.close()
        }
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ax1 = a[1] - a[3] / 2; val ay1 = a[2] - a[4] / 2
        val ax2 = a[1] + a[3] / 2; val ay2 = a[2] + a[4] / 2
        val bx1 = b[1] - b[3] / 2; val by1 = b[2] - b[4] / 2
        val bx2 = b[1] + b[3] / 2; val by2 = b[2] + b[4] / 2
        val iw = maxOf(0f, minOf(ax2, bx2) - maxOf(ax1, bx1))
        val ih = maxOf(0f, minOf(ay2, by2) - maxOf(ay1, by1))
        val inter = iw * ih
        val union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
