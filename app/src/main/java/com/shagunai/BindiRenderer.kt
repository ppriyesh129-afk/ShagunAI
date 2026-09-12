package com.shagunai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.atan2
import kotlin.math.hypot

object BindiRenderer {
    fun render(frame: Bitmap, bindi: Bitmap, detector: FaceDetector): Bitmap? {
        val detection = detector.detect(frame) ?: return null
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val eyeR = detection.keypoints[0]
        val eyeL = detection.keypoints[1]
        val nose = detection.keypoints[2]

        val eyeMidX = (eyeR[0] + eyeL[0]) / 2f
        val eyeMidY = (eyeR[1] + eyeL[1]) / 2f
        val centerX = eyeMidX + (eyeMidX - nose[0]) * 0.65f
        val centerY = eyeMidY + (eyeMidY - nose[1]) * 0.65f

        val eyeDist = hypot((eyeL[0] - eyeR[0]).toDouble(), (eyeL[1] - eyeR[1]).toDouble()).toFloat()
        val bw = (eyeDist * 0.55f).toInt().coerceIn(16, out.width / 3)
        val bh = (bw.toFloat() / bindi.width * bindi.height).toInt()
        val scaled = Bitmap.createScaledBitmap(bindi, bw, bh, true)

        val angle = Math.toDegrees(atan2((eyeL[1] - eyeR[1]).toDouble(), (eyeL[0] - eyeR[0]).toDouble())).toFloat()

        canvas.save()
        canvas.rotate(angle, centerX, centerY)
        canvas.drawBitmap(scaled, centerX - bw / 2f, centerY - bh / 2f, paint)
        canvas.restore()
        return out
    }
}
