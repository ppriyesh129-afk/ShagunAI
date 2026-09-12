package com.shagunai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RecreateGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.hypot

object BindiRenderer {
    private var lastX = 0f
    private var lastY = 0f
    private var smoothingFactor = 0.7f // 0.7 = 70% current, 30% previous (smoother)
    
    fun render(frame: Bitmap, bindi: Bitmap, detector: FaceDetector): Bitmap? {
        val detection = detector.detect(frame) ?: return null
        
        val eyeR = detection.keypoints[0]
        val eyeL = detection.keypoints[1]
        val nose = detection.keypoints[2]

        val eyeMidX = (eyeR[0] + eyeL[0]) / 2f
        val eyeMidY = (eyeR[1] + eyeL[1]) / 2f
        
        // Calculate forehead position (between eyes and nose)
        val dirX = eyeMidX - nose[0]
        val dirY = eyeMidY - nose[1]
        val dirLen = hypot(dirX.toDouble(), dirY.toDouble()).toFloat()
        
        // Place bindi 40% up from nose to eyes (forehead area)
        val bindiX = nose[0] + dirX * 0.4f
        val bindiY = nose[1] + dirY * 0.4f
        
        // Smooth the position (prevent jitter)
        val smoothX = lastX * (1 - smoothingFactor) + bindiX * smoothingFactor
        val smoothY = lastY * (1 - smoothingFactor) + bindiY * smoothingFactor
        lastX = smoothX
        lastY = smoothY

        // Calculate bindi size based on face size (smaller, more realistic)
        val eyeDist = hypot((eyeL[0] - eyeR[0]).toDouble(), (eyeL[1] - eyeR[1]).toDouble()).toFloat()
        val bindiWidth = (eyeDist * 0.35f).toInt().coerceIn(12, frame.width / 6) // Reduced from 0.55 to 0.35
        val bindiHeight = (bindiWidth.toFloat() / bindiBitmap.width * bindiBitmap.height).toInt()
        
        val scaledBindi = Bitmap.createScaledBitmap(bindi, bindiWidth, bindiHeight, true)
        
        // Create output bitmap
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Calculate rotation angle
        val angle = Math.toDegrees(atan2((eyeL[1] - eyeR[1]).toDouble(), (eyeL[0] - eyeR[0]).toDouble())).toFloat()

        // Apply alpha blending for natural look (85% opacity)
        paint.alpha = 215 // 215/255 ≈ 85% opacity
        
        canvas.save()
        canvas.rotate(angle, smoothX, smoothY)
        
        // Draw with soft edges using PorterDuff for better blending
        canvas.drawBitmap(scaledBindi, smoothX - bindiWidth / 2f, smoothY - bindiHeight / 2f, paint)
        canvas.restore()
        
        return out
    }
    
    // Reset smoothing when starting new video
    fun resetSmoothing() {
        lastX = 0f
        lastY = 0f
    }
}
