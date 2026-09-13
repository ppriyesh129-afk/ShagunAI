package com.shagunai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.hypot

object BindiRenderer {
    private var lastX = 0f
    private var lastY = 0f
    private var lastSize = 0f
    private val smoothing = 0.7f

    fun render(frame: Bitmap, points: Array<FloatArray>?): Bitmap? {
        if (points == null || points.size < 478) return null

        // 478-Point MediaPipe Topology
        val ptLeftEyeInner = points[133]
        val ptRightEyeInner = points[362]
        val ptGlabella = points[10]      // Between eyebrows
        val ptForehead = points[9]       // Just above glabella

        // 1. Perfect Position: Shift slightly up from glabella towards forehead
        val targetX = ptGlabella[0] + (ptForehead[0] - ptGlabella[0]) * 0.3f
        val targetY = ptGlabella[1] + (ptForehead[1] - ptGlabella[1]) * 0.3f

        // 2. Perfect Size: Based on distance between inner eye corners
        val eyeDist = hypot((ptLeftEyeInner[0] - ptRightEyeInner[0]).toDouble(), (ptLeftEyeInner[1] - ptRightEyeInner[1]).toDouble()).toFloat()
        val targetSize = (eyeDist * 0.45f).coerceIn(15f, frame.width / 4f)

        // 3. Calculate Face Roll (Tilt)
        val angle = Math.toDegrees(atan2((ptRightEyeInner[1] - ptLeftEyeInner[1]).toDouble(), (ptRightEyeInner[0] - ptLeftEyeInner[0]).toDouble())).toFloat()

        // 4. Smooth movement
        val smoothX = if (lastX == 0f) targetX else lastX * (1 - smoothing) + targetX * smoothing
        val smoothY = if (lastY == 0f) targetY else lastY * (1 - smoothing) + targetY * smoothing
        val smoothSize = if (lastSize == 0f) targetSize else lastSize * (1 - smoothing) + targetSize * smoothing
        
        lastX = smoothX; lastY = smoothY; lastSize = smoothSize

        // 5. Draw 3D Bindi
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // --- LAYER 1: Drop Shadow (Raised effect) ---
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        shadowPaint.color = Color.argb(50, 0, 0, 0)
        canvas.drawCircle(smoothX + 2f, smoothY + 4f, smoothSize / 2f, shadowPaint)

        // --- LAYER 2: Base Volume (Domed red shape) ---
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val baseGradient = RadialGradient(
            smoothX, smoothY, smoothSize / 2f,
            intArrayOf(
                Color.argb(255, 230, 30, 30),   // Bright red center
                Color.argb(255, 160, 0, 0),     // Maroon middle
                Color.argb(255, 90, 0, 0)       // Dark red edge
            ),
            floatArrayOf(0.0f, 0.6f, 1.0f),
            Shader.TileMode.CLAMP
        )
        basePaint.shader = baseGradient
        canvas.drawCircle(smoothX, smoothY, smoothSize / 2f, basePaint)

        // --- LAYER 3: Specular Highlight (3D Glossy Reflection) ---
        canvas.save()
        canvas.rotate(angle, smoothX, smoothY)
        
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val highlightGradient = RadialGradient(
            smoothX - smoothSize * 0.15f, smoothY - smoothSize * 0.15f, smoothSize * 0.35f,
            intArrayOf(Color.argb(220, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0.0f, 1.0f),
            Shader.TileMode.CLAMP
        )
        highlightPaint.shader = highlightGradient
        
        val highlightRect = RectF(
            smoothX - smoothSize * 0.3f, 
            smoothY - smoothSize * 0.35f, 
            smoothX + smoothSize * 0.1f, 
            smoothY + smoothSize * 0.05f
        )
        canvas.drawOval(highlightRect, highlightPaint)
        canvas.restore()

        return out
    }

    fun reset() {
        lastX = 0f; lastY = 0f; lastSize = 0f
    }
}
