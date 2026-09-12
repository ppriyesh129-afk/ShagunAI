package com.shagunai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.atan2
import kotlin.math.hypot

object BindiRenderer {
    private var lastX = 0f
    private var lastY = 0f
    private var smoothingFactor = 0.7f // 70% current, 30% previous
    
    fun render(frame: Bitmap, bindi: Bitmap, detector: FaceDetector): Bitmap? {
        val detection = detector.detect(frame) ?: return null
        
        val eyeR = detection.keypoints[0]
        val eyeL = detection.keypoints[1]
        val nose = detection.keypoints[2]

        val eyeMidX = (eyeR[0] + eyeL[0]) / 2f
        val eyeMidY = (eyeR[1] + eyeL[1]) / 2f
        
        // Calculate direction from nose to eyes (forehead direction)
        val dirX = eyeMidX - nose[0]
        val dirY = eyeMidY - nose[1]
        
        // Place bindi on forehead (40% from nose toward eyes)
        val bindiX = nose[0] + dirX * 0.4f
        val bindiY = nose[1] + dirY * 0.4f
        
        // Smooth position to prevent jitter
        val smoothX = if (lastX == 0f) bindiX else lastX * (1 - smoothingFactor) + bindiX * smoothingFactor
        val smoothY = if (lastY == 0f) bindiY else lastY * (1 - smoothingFactor) + bindiY * smoothingFactor
        lastX = smoothX
        lastY = smoothY

        // Calculate bindi size (smaller, more natural - 35% of eye distance)
        val eyeDist = hypot((eyeL[0] - eyeR[0]).toDouble(), (eyeL[1] - eyeR[1]).toDouble()).toFloat()
        val bindiWidth = (eyeDist * 0.35f).toInt().coerceIn(12, frame.width / 6)
        val bindiHeight = (bindiWidth.toFloat() / bindi.width * bindi.height).toInt()
        
        val scaledBindi = Bitmap.createScaledBitmap(bindi, bindiWidth, bindiHeight, true)
        
        // Create output bitmap
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Calculate rotation angle from eye line
        val angle = Math.toDegrees(atan2((eyeL[1] - eyeR[1]).toDouble(), (eyeL[0] - eyeR[0]).toDouble())).toFloat()

        // Set opacity to 90% for natural blending
        paint.alpha = 230
        
        canvas.save()
        canvas.rotate(angle, smoothX, smoothY)
        canvas.drawBitmap(scaledBindi, smoothX - bindiWidth / 2f, smoothY - bindiHeight / 2f, paint)
        canvas.restore()
        
        return out
    }
    
    fun resetSmoothing() {
        lastX = 0f
        lastY = 0f
    }
}
