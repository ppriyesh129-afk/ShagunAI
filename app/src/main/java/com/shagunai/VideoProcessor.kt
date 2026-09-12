package com.shagunai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File

class VideoProcessor(
    private val context: Context,
    private val videoUri: Uri,
    private val bindi: Bitmap,
    private val detector: FaceDetector,
    private val onProgress: (Int, Int) -> Unit,
    private val onResult: (Uri?) -> Unit
) {
    companion object {
        const val FPS = 24
        private const val TIMEOUT_US = 10_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var muxerStarted = false
    private var trackIndex = -1

    fun process() {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            
            // ✅ FIX 1: Correct Android constant for video rotation
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            
            val probe = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            if (durationMs == 0L || probe == null) throw Exception("Bad video")

            var w = probe.width
            var h = probe.height
            if (rotation == 90 || rotation == 270) { 
                val temp = w; w = h; h = temp 
            }
            // Ensure dimensions are even numbers for the encoder
            w -= w % 2
            h -= h % 2
            if (w <= 0) w = 720
            if (h <= 0) h = 1280

            val outFile = File(context.cacheDir, "shagun_${System.currentTimeMillis()}.mp4")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * FPS).coerceAtLeast(2_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val totalFrames = ((durationMs * FPS) / 1000).toInt().coerceAtLeast(1)
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }

            for (i in 0 until totalFrames) {
                val tUs = i * 1_000_000L / FPS
                val raw = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val oriented = if (rotation != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true) else raw
                val sized = if (oriented.width != w || oriented.height != h) Bitmap.createScaledBitmap(oriented, w, h, true) else oriented
                val rendered = BindiRenderer.render(sized, bindi, detector) ?: sized

                feedAndDrain(encoder, muxer, rendered, tUs)
                mainHandler.post { onProgress(i + 1, totalFrames) }

                if (sized !== oriented) sized.recycle()
                if (oriented !== raw) oriented.recycle()
                raw.recycle()
            }

            drain(encoder, muxer, true)
            encoder.stop(); encoder.release()
            muxer.stop(); muxer.release()
            retriever.release()
            encoder = null; muxer = null; retriever = null

            val savedUri = publish(outFile)
            outFile.delete()
            mainHandler.post { onResult(savedUri) }
        } catch (e: Exception) {
            e.printStackTrace()
            try { encoder?.release(); muxer?.release(); retriever?.release() } catch (_: Exception) {}
            mainHandler.post { onResult(null) }
        }
    }

    private fun feedAndDrain(encoder: MediaCodec, muxer: MediaMuxer, bmp: Bitmap, ptsUs: Long) {
        while (true) {
            val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val image = encoder.getInputImage(inIdx)
                if (image != null) {
                    bitmapToYuv(bmp, image)
                    image.close()
                }
                encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                break
            }
            drain(encoder, muxer, false)
        }
        drain(encoder, muxer, false)
    }

    private fun drain(encoder: MediaCodec, muxer: MediaMuxer, endOfStream: Boolean) {
        if (endOfStream) encoder.signalEndOfInputStream()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val buf = encoder.getOutputBuffer(outIdx)
                    if (buf == null) { encoder.releaseOutputBuffer(outIdx, false); continue }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buf, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun bitmapToYuv(bmp: Bitmap, image: Image) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        
        // ✅ FIX 2 & 3: Explicitly declare 'val' for planes and color variables
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        val yRow = yPlane.rowStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride
        
        for (r in 0 until h) {
            for (c in 0 until w) {
                val p = px[r * w + c]
                val rr = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val bb = p and 0xFF
                
                val y = ((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16
                val u = ((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128
                val v = ((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128
                
                yPlane.buffer.put(r * yRow + c, y.toByte())
                if (r % 2 == 0 && c % 2 == 0) {
                    uPlane.buffer.put((r / 2) * uvRow + (c / 2) * uvPix, u.toByte())
                    vPlane.buffer.put((r / 2) * uvRow + (c / 2) * uvPix, v.toByte())
                }
            }
        }
    }

    private fun publish(file: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ShagunAI")
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        return uri
    }
}
