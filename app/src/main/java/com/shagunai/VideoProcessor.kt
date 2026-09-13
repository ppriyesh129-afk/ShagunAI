package com.shagunai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

class VideoProcessor(
    private val context: Context,
    private val videoUri: Uri,
    private val landmarker: FaceLandmarker,
    private val onProgress: (Int, Int) -> Unit,
    private val onResult: (Uri?, String?) -> Unit
) {
    companion object {
        const val FPS = 24
        private const val TIMEOUT_US = 10_000L
    }

    var lastSummary = ""
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var muxerStarted = false
    private var trackIndex = -1
    private var selectedColorFormat = 0

    fun process() {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var retriever: MediaMetadataRetriever? = null
        var stage = "init"
        try {
            stage = "reading video info"
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val probe = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            if (durationMs == 0L || probe == null) throw Exception("Could not read video")

            var w = probe.width
            var h = probe.height
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            w -= w % 2
            h -= h % 2
            if (w < 64) w = 64
            if (h < 64) h = 64
            probe.recycle()

            stage = "creating output file"
            val outFile = File(context.cacheDir, "shagun_${System.currentTimeMillis()}.mp4")

            stage = "configuring encoder"
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * FPS).coerceIn(4_000_000, 20_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codecInfo = findCodecForFormat(MediaFormat.MIMETYPE_VIDEO_AVC)
                ?: throw Exception("No H.264 encoder found")
            
            val colorFormat = findSupportedColorFormat(codecInfo)
                ?: throw Exception("No supported YUV format")
            
            selectedColorFormat = colorFormat
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            lastSummary = "Codec: ${codecInfo.name}\nColor: $colorFormat"

            encoder = MediaCodec.createByCodecName(codecInfo.name)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val totalFrames = ((durationMs * FPS) / 1000).toInt().coerceAtLeast(1)
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }

            var framesProcessed = 0
            for (i in 0 until totalFrames) {
                stage = "frame ${i + 1}/$totalFrames"
                val tUs = i * 1_000_000L / FPS
                val raw = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val oriented = if (rotation != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true) else raw
                val sized = if (oriented.width != w || oriented.height != h) Bitmap.createScaledBitmap(oriented, w, h, true) else oriented
                
                // Run 478-point detection and draw 3D bindi
                val points = landmarker.detect(sized)
                val rendered = BindiRenderer.render(sized, points) ?: sized

                if (feedFrame(encoder, muxer, rendered, tUs)) framesProcessed++
                mainHandler.post { onProgress(i + 1, totalFrames) }

                if (sized !== oriented) sized.recycle()
                if (oriented !== raw) oriented.recycle()
                raw.recycle()
            }

            stage = "finalizing encoder"
            if (framesProcessed == 0) throw Exception("No frames encoded")
            queueEndOfStream(encoder, muxer)
            drainEncoder(encoder, muxer, true)

            stage = "closing files"
            encoder.stop(); encoder.release()
            muxer.stop(); muxer.release()
            retriever.release()
            encoder = null; muxer = null; retriever = null

            stage = "verifying output"
            val verify = MediaMetadataRetriever()
            try {
                verify.setDataSource(outFile.absolutePath)
                val d = verify.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                verify.release()
                if (d <= 0L) throw Exception("duration=0")
                lastSummary += "\nVerified: ${d}ms, ${outFile.length() / 1024}KB"
            } catch (e: Exception) {
                verify.release()
                throw Exception("invalid MP4: ${e.message}")
            }

            stage = "saving to gallery"
            val savedUri = publish(outFile)
            mainHandler.post { onResult(savedUri, null) }
        } catch (e: Exception) {
            e.printStackTrace()
            try { encoder?.release(); muxer?.release(); retriever?.release() } catch (_: Exception) {}
            mainHandler.post { onResult(null, "$stage → ${e.message}") }
        }
    }

    private fun findCodecForFormat(mime: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            try { if (info.getCapabilitiesForType(mime) != null) return info } catch (_: Exception) {}
        }
        return null
    }

    private fun findSupportedColorFormat(info: MediaCodecInfo): Int? {
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val formats = caps.colorFormats
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
        )
        for (fmt in preferred) { if (formats.contains(fmt)) return fmt }
        return formats.firstOrNull()
    }

    private fun queueEndOfStream(encoder: MediaCodec, muxer: MediaMuxer) {
        while (true) {
            val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                encoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drainEncoder(encoder, muxer, false)
        }
    }

    private fun feedFrame(encoder: MediaCodec, muxer: MediaMuxer, bmp: Bitmap, ptsUs: Long): Boolean {
        while (true) {
            val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val buffer = encoder.getInputBuffer(inIdx) ?: return false
                bitmapToYuvBuffer(bmp, buffer)
                encoder.queueInputBuffer(inIdx, 0, buffer.limit(), ptsUs, 0)
                drainEncoder(encoder, muxer, false)
                return true
            } else {
                drainEncoder(encoder, muxer, false)
            }
        }
    }

    private fun bitmapToYuvBuffer(bmp: Bitmap, outBuffer: ByteBuffer) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val ySize = w * h
        val uvSize = ySize / 4

        outBuffer.clear()
        outBuffer.limit(ySize + 2 * uvSize)

        for (i in pixels) {
            val r = (i shr 16) and 0xFF
            val g = (i shr 8) and 0xFF
            val b = i and 0xFF
            val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            outBuffer.put(y.coerceIn(0, 255).toByte())
        }

        val isSemiPlanar = (selectedColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                            selectedColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar)

        if (isSemiPlanar) {
            for (row in 0 until h step 2) {
                for (col in 0 until w step 2) {
                    val idx = row * w + col
                    val r = (pixels[idx] shr 16) and 0xFF
                    val g = (pixels[idx] shr 8) and 0xFF
                    val b = pixels[idx] and 0xFF
                    val u = (-0.169 * r - 0.331 * g + 0.500 * b + 128).toInt()
                    val v = (0.500 * r - 0.419 * g - 0.081 * b + 128).toInt()
                    outBuffer.put(u.coerceIn(0, 255).toByte())
                    outBuffer.put(v.coerceIn(0, 255).toByte())
                }
            }
        } else {
            for (row in 0 until h step 2) {
                for (col in 0 until w step 2) {
                    val idx = row * w + col
                    val r = (pixels[idx] shr 16) and 0xFF
                    val g = (pixels[idx] shr 8) and 0xFF
                    val b = pixels[idx] and 0xFF
                    val u = (-0.169 * r - 0.331 * g + 0.500 * b + 128).toInt()
                    outBuffer.put(u.coerceIn(0, 255).toByte())
                }
            }
            for (row in 0 until h step 2) {
                for (col in 0 until w step 2) {
                    val idx = row * w + col
                    val r = (pixels[idx] shr 16) and 0xFF
                    val g = (pixels[idx] shr 8) and 0xFF
                    val b = pixels[idx] and 0xFF
                    val v = (0.500 * r - 0.419 * g - 0.081 * b + 128).toInt()
                    outBuffer.put(v.coerceIn(0, 255).toByte())
                }
            }
        }
        outBuffer.flip()
    }

    private fun drainEncoder(encoder: MediaCodec, muxer: MediaMuxer, endOfStream: Boolean) {
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
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buf, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
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
