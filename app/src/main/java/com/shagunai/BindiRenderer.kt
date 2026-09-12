package com.shagunai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import kotlin.math.atan2
import kotlin.math.hypot

object BindiRenderer {
    private var lastX = 0f
    private var lastY = 0f
    private var smoothingFactor = 0.6f // Smoother tracking
    
    fun render(frame: Bitmap, bindi: Bitmap, detector: FaceDetector): Bitmap? {
        val detection = detector.detect(frame) ?: return null
        
        // Use the face bounding box to find the forehead (top 25% of the face)
        val faceHeight = detection.y2 - detection.y1
        val faceWidth = detection.x2 - detection.x1
        
        // Forehead position: center X, and 25% down from the top of the face box
        val targetX = (detection.x1 + detection.x2) / 2f
        val targetY = detection.y1 + faceHeight * 0.25f
        
        // Smooth the position
        val smoothX = if (lastX == 0f) targetX else lastX * (1 - smoothingFactor) + targetX * smoothingFactor
        val smoothY = if (lastY == 0f) targetY else lastY * (1 - smoothingFactor) + targetY * smoothingFactor
        lastX = smoothX
        lastY = smoothY

        // Size: 20% of face width (natural size)
        val bindiWidth = (faceWidth * 0.20f).toInt().coerceIn(15, frame.width / 5)
        val bindiHeight = (bindiWidth.toFloat() / bindi.width * bindi.height).toInt()
        
        val scaledBindi = Bitmap.createScaledBitmap(bindi, bindiWidth, bindiHeight, true)
        
        // Tint the bindi red if it's black/white, to ensure it looks like a real bindi
        val tintedBindi = Bitmap.createBitmap(bindiWidth, bindiHeight, Bitmap.Config.ARGB_8888)
        val tintCanvas = Canvas(tintedBindi)
        val tintPaint = Paint()
        tintPaint.colorFilter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP)
        tintCanvas.drawBitmap(scaledBindi, 0f, 0f, tintPaint)

        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        
        // 85% opacity for natural skin blending
        paint.alpha = 215 

        // Calculate slight rotation based on eye tilt
        val eyeR = detection.keypoints[0]
        val eyeL = detection.keypoints[1]
        val angle = Math.toDegrees(atan2((eyeL[1] - eyeR[1]).toDouble(), (eyeL[0] - eyeR[0]).toDouble())).toFloat()

        canvas.save()
        canvas.rotate(angle, smoothX, smoothY)
        canvas.drawBitmap(tintedBindi, smoothX - bindiWidth / 2f, smoothY - bindiHeight / 2f, paint)
        canvas.restore()
        
        return out
    }
    
    fun resetSmoothing() {
        lastX = 0f
        lastY = 0f
    }
}
