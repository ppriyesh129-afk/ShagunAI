package com.shagunai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

object BindiRenderer {

    // Previous stabilized values
    private var lastX = 0f
    private var lastY = 0f
    private var lastSize = 0f
    private var lastAngle = 0f

    // Smoothing
    private const val BASE_SMOOTH = 0.55f
    private const val FAST_SMOOTH = 0.82f
    private const val ANGLE_SMOOTH = 0.30f

    fun render(
        frame: Bitmap,
        points: Array<FloatArray>?
    ): Bitmap? {

        // MediaPipe Face Mesh requires 478 landmarks
        if (points == null || points.size < 478) {
            return null
        }

        // ---------------------------------------------------------
        // FACE LANDMARKS
        // ---------------------------------------------------------

        val leftEye = points[133]
        val rightEye = points[362]

        // Forehead / glabella landmarks
        val glabella = points[10]
        val upperForehead = points[151]

        // Nose / face-center landmarks
        val nose = points[1]
        val faceCenter = points[168]

        // ---------------------------------------------------------
        // 1. BIN​DI POSITION
        // ---------------------------------------------------------
        //
        // Move from glabella toward upper forehead.
        // This gives a more natural bindi position.
        //

        val targetX =
            glabella[0] +
            (upperForehead[0] - glabella[0]) * 0.35f

        val targetY =
            glabella[1] +
            (upperForehead[1] - glabella[1]) * 0.35f

        // ---------------------------------------------------------
        // 2. FACE SCALE
        // ---------------------------------------------------------

        val eyeDist = hypot(
            (rightEye[0] - leftEye[0]).toDouble(),
            (rightEye[1] - leftEye[1]).toDouble()
        ).toFloat()

        if (eyeDist <= 1f) {
            return null
        }

        // Bindi size relative to face size
        val targetSize =
            (eyeDist * 0.22f)
                .coerceIn(
                    10f,
                    frame.width * 0.055f
                )

        // ---------------------------------------------------------
        // 3. HEAD ROLL
        // ---------------------------------------------------------

        val targetAngle =
            Math.toDegrees(
                atan2(
                    (rightEye[1] - leftEye[1]).toDouble(),
                    (rightEye[0] - leftEye[0]).toDouble()
                )
            ).toFloat()

        // ---------------------------------------------------------
        // 4. APPROXIMATE FACE YAW
        // ---------------------------------------------------------
        //
        // This is used only to slightly compress the bindi
        // when the face turns sideways.
        //

        val yawOffset =
            abs(
                (nose[0] - faceCenter[0]) / eyeDist
            )

        val compression =
            (1f - yawOffset * 0.22f)
                .coerceIn(0.82f, 1f)

        // ---------------------------------------------------------
        // 5. ADAPTIVE MOTION SMOOTHING
        // ---------------------------------------------------------

        val movement =
            if (lastX == 0f && lastY == 0f) {
                0f
            } else {
                hypot(
                    (targetX - lastX).toDouble(),
                    (targetY - lastY).toDouble()
                ).toFloat()
            }

        // Faster movement = more responsive tracking
        val smooth =
            if (movement > eyeDist * 0.05f) {
                FAST_SMOOTH
            } else {
                BASE_SMOOTH
            }

        // First frame
        val smoothX =
            if (lastX == 0f) {
                targetX
            } else {
                lastX * (1f - smooth) +
                    targetX * smooth
            }

        val smoothY =
            if (lastY == 0f) {
                targetY
            } else {
                lastY * (1f - smooth) +
                    targetY * smooth
            }

        val smoothSize =
            if (lastSize == 0f) {
                targetSize
            } else {
                lastSize * (1f - smooth) +
                    targetSize * smooth
            }

        // Smooth rotation separately
        val smoothAngle =
            if (lastAngle == 0f) {
                targetAngle
            } else {
                lastAngle * (1f - ANGLE_SMOOTH) +
                    targetAngle * ANGLE_SMOOTH
            }

        // Save state
        lastX = smoothX
        lastY = smoothY
        lastSize = smoothSize
        lastAngle = smoothAngle

        // ---------------------------------------------------------
        // 6. OUTPUT BITMAP
        // ---------------------------------------------------------
        //
        // Don't unnecessarily copy an already mutable frame.
        //

        val out =
            if (frame.isMutable) {
                frame
            } else {
                frame.copy(
                    Bitmap.Config.ARGB_8888,
                    true
                )
            }

        val canvas = Canvas(out)

        // ---------------------------------------------------------
        // 7. ROTATE TO FOLLOW HEAD
        // ---------------------------------------------------------

        canvas.save()

        canvas.rotate(
            smoothAngle,
            smoothX,
            smoothY
        )

        // ---------------------------------------------------------
        // 8. SIDE-VIEW COMPRESSION
        // ---------------------------------------------------------

        canvas.scale(
            compression,
            1f,
            smoothX,
            smoothY
        )

        // ---------------------------------------------------------
        // 9. SOFT DROP SHADOW
        // ---------------------------------------------------------

        val shadowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(
                    50,
                    0,
                    0,
                    0
                )
            }

        val shadowRect =
            RectF(
                smoothX - smoothSize * 0.46f + 2f,
                smoothY - smoothSize * 0.46f + 3f,
                smoothX + smoothSize * 0.46f + 2f,
                smoothY + smoothSize * 0.46f + 3f
            )

        canvas.drawOval(
            shadowRect,
            shadowPaint
        )

        // ---------------------------------------------------------
        // 10. REALISTIC RED BASE
        // ---------------------------------------------------------

        val basePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                shader =
                    RadialGradient(
                        smoothX,
                        smoothY,
                        smoothSize * 0.50f,

                        intArrayOf(
                            Color.rgb(
                                235,
                                40,
                                40
                            ),
                            Color.rgb(
                                175,
                                5,
                                5
                            ),
                            Color.rgb(
                                100,
                                0,
                                0
                            )
                        ),

                        floatArrayOf(
                            0f,
                            0.60f,
                            1f
                        ),

                        Shader.TileMode.CLAMP
                    )
            }

        val bindiRect =
            RectF(
                smoothX - smoothSize * 0.45f,
                smoothY - smoothSize * 0.45f,
                smoothX + smoothSize * 0.45f,
                smoothY + smoothSize * 0.45f
            )

        canvas.drawOval(
            bindiRect,
            basePaint
        )

        // ---------------------------------------------------------
        // 11. SUBTLE EDGE DARKENING
        // ---------------------------------------------------------

        val edgePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                shader =
                    RadialGradient(
                        smoothX,
                        smoothY,
                        smoothSize * 0.47f,

                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            Color.argb(
                                70,
                                40,
                                0,
                                0
                            )
                        ),

                        floatArrayOf(
                            0f,
                            0.72f,
                            1f
                        ),

                        Shader.TileMode.CLAMP
                    )
            }

        canvas.drawOval(
            bindiRect,
            edgePaint
        )

        // ---------------------------------------------------------
        // 12. SOFT SPECULAR HIGHLIGHT
        // ---------------------------------------------------------

        val highlightPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                shader =
                    RadialGradient(
                        smoothX -
                            smoothSize * 0.15f,

                        smoothY -
                            smoothSize * 0.17f,

                        smoothSize * 0.24f,

                        intArrayOf(
                            Color.argb(
                                180,
                                255,
                                255,
                                255
                            ),
                            Color.argb(
                                40,
                                255,
                                255,
                                255
                            ),
                            Color.TRANSPARENT
                        ),

                        floatArrayOf(
                            0f,
                            0.45f,
                            1f
                        ),

                        Shader.TileMode.CLAMP
                    )
            }

        canvas.drawCircle(
            smoothX -
                smoothSize * 0.13f,

            smoothY -
                smoothSize * 0.14f,

            smoothSize * 0.17f,

            highlightPaint
        )

        // ---------------------------------------------------------
        // 13. FINISH
        // ---------------------------------------------------------

        canvas.restore()

        return out
    }

    // -------------------------------------------------------------
    // RESET TRACKING
    // -------------------------------------------------------------

    fun reset() {
        lastX = 0f
        lastY = 0f
        lastSize = 0f
        lastAngle = 0f
    }
}
