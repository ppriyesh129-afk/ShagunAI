package com.shagunai

import android.graphics.*
import kotlin.math.atan2
import kotlin.math.hypot

object BindiRenderer {

    private var lastX = 0f
    private var lastY = 0f
    private var lastSize = 0f
    private var lastAngle = 0f

    private const val BASE_SMOOTH = 0.55f
    private const val FAST_SMOOTH = 0.82f

    fun render(frame: Bitmap, points: Array<FloatArray>?): Bitmap? {
        if (points == null || points.size < 478) return null

        val leftEye = points[133]
        val rightEye = points[362]
        val glabella = points[10]
        val upperForehead = points[151]

        val targetX = glabella[0] + (upperForehead[0] - glabella[0]) * 0.35f
        val targetY = glabella[1] + (upperForehead[1] - glabella[1]) * 0.35f

        val eyeDist = hypot(
            (leftEye[0] - rightEye[0]).toDouble(),
            (leftEye[1] - rightEye[1]).toDouble()
        ).toFloat()

        val targetSize = (eyeDist * 0.22f).coerceIn(10f, frame.width * 0.055f)

        val angle = Math.toDegrees(
            atan2(
                (rightEye[1] - leftEye[1]).toDouble(),
                (rightEye[0] - leftEye[0]).toDouble()
            )
        ).toFloat()

        val movement = hypot(
            (targetX - lastX).toDouble(),
            (targetY - lastY).toDouble()
        ).toFloat()

        val smooth = if (movement > eyeDist * 0.05f) FAST_SMOOTH else BASE_SMOOTH

        val x = if (lastX == 0f) targetX else lastX * (1 - smooth) + targetX * smooth
        val y = if (lastY == 0f) targetY else lastY * (1 - smooth) + targetY * smooth
        val size = if (lastSize == 0f) targetSize else lastSize * (1 - smooth) + targetSize * smooth
        val rot = if (lastAngle == 0f) angle else lastAngle * 0.7f + angle * 0.3f

        lastX = x
        lastY = y
        lastSize = size
        lastAngle = rot

        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        canvas.save()
        canvas.rotate(rot, x, y)

        val compression = (1f - (kotlin.math.abs(rot) / 90f) * 0.18f)
            .coerceIn(0.82f, 1f)

        canvas.scale(compression, 1f, x, y)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(55, 0, 0, 0)
        }
        canvas.drawOval(
            RectF(
                x - size * 0.46f + 2f,
                y - size * 0.46f + 3f,
                x + size * 0.46f + 2f,
                y + size * 0.46f + 3f
            ),
            shadow
        )

        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                x, y, size * 0.5f,
                intArrayOf(
                    Color.rgb(235, 40, 40),
                    Color.rgb(170, 0, 0),
                    Color.rgb(95, 0, 0)
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawOval(
            RectF(
                x - size * 0.45f,
                y - size * 0.45f,
                x + size * 0.45f,
                y + size * 0.45f
            ),
            base
        )

        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                x - size * 0.15f,
                y - size * 0.18f,
                size * 0.22f,
                intArrayOf(
                    Color.argb(220,255,255,255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f,1f),
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawCircle(
            x - size * 0.13f,
            y - size * 0.14f,
            size * 0.18f,
            highlight
        )

        canvas.restore()
        return out
    }

    fun reset() {
        lastX = 0f
        lastY = 0f
        lastSize = 0f
        lastAngle = 0f
    }
}
