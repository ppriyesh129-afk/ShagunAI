package com.shagunai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Dialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var selectedBindiFileName: String? = null
    private lateinit var videoPreview: VideoView
    private lateinit var processedImageView: ImageView
    private lateinit var tvModelInfo: TextView

    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private lateinit var faceDetector: FaceDetector

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                selectedVideoUri = uri
                videoPreview.setVideoURI(uri)
                videoPreview.seekTo(100)
                Toast.makeText(this, "Video selected successfully", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoPreview = findViewById(R.id.videoPreview)
        processedImageView = findViewById(R.id.processedImage)
        tvModelInfo = findViewById(R.id.tvModelInfo)

        initOnnxModel()

        findViewById<Button>(R.id.btnUpload).setOnClickListener { videoPicker.launch("video/*") }
        findViewById<Button>(R.id.btnBindi).setOnClickListener { showBindiGrid() }
        findViewById<Button>(R.id.btnProcess).setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please upload a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedBindiFileName == null) {
                Toast.makeText(this, "Please choose a bindi first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Processing started...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch { processVideoFrame() }
        }
    }

    private fun initOnnxModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = assets.open("models/face_detection_short_range.onnx").use { it.readBytes() }
            ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            faceDetector = FaceDetector(ortEnv, ortSession)
            tvModelInfo.text = "✅ AI Model Loaded!\nBlazeFace short-range ready.\nSelect video + bindi, then Process."
        } catch (e: Exception) {
            Log.e("ShagunAI_ONNX", "Failed to load ONNX model", e)
            tvModelInfo.text = "❌ Failed to load model: ${e.message}"
        }
    }

    private suspend fun processVideoFrame() {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, selectedVideoUri)
            val originalFrame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()

            if (originalFrame == null) { showToast("Failed to extract frame."); return }

            val bindiBitmap = assets.open("bindi/$selectedBindiFileName").use {
                BitmapFactory.decodeStream(it)
            } ?: return

            // 🤖 RUN THE AI ON THE FRAME
            val detection = faceDetector.detect(originalFrame)

            if (detection == null) {
                withContext(Dispatchers.Main) {
                    tvModelInfo.text = "❌ NO FACE detected in this frame.\nTry a video where the face is clear & front-facing."
                }
                showToast("No face detected!")
                return
            }

            val mutableFrame = originalFrame.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableFrame)
            val paint = Paint()

            // 🎯 COMPUTE BINDI POSITION FROM EYE + NOSE KEYPOINTS
            val eyeR = detection.keypoints[0]
            val eyeL = detection.keypoints[1]
            val nose = detection.keypoints[2]

            val eyeMidX = (eyeR[0] + eyeL[0]) / 2f
            val eyeMidY = (eyeR[1] + eyeL[1]) / 2f
            val dirX = eyeMidX - nose[0]   // vector pointing "up" the face
            val dirY = eyeMidY - nose[1]

            val centerX = eyeMidX + dirX * 0.65f   // forehead spot
            val centerY = eyeMidY + dirY * 0.65f

            val eyeDist = hypot((eyeL[0] - eyeR[0]).toDouble(), (eyeL[1] - eyeR[1]).toDouble()).toFloat()
            val bindiWidth = (eyeDist * 0.55f).toInt().coerceIn(24, mutableFrame.width / 3)
            val bindiHeight = (bindiWidth.toFloat() / bindiBitmap.width * bindiBitmap.height).toInt()
            val scaledBindi = Bitmap.createScaledBitmap(bindiBitmap, bindiWidth, bindiHeight, true)

            // Rotate bindi to match head tilt
            val angle = Math.toDegrees(atan2((eyeL[1] - eyeR[1]).toDouble(), (eyeL[0] - eyeR[0]).toDouble())).toFloat()

            canvas.save()
            canvas.rotate(angle, centerX, centerY)
            canvas.drawBitmap(scaledBindi, centerX - bindiWidth / 2f, centerY - bindiHeight / 2f, paint)
            canvas.restore()

            // 🟩 DEBUG: draw face box (green) + keypoints (yellow)
            paint.color = Color.GREEN
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawRect(detection.x1, detection.y1, detection.x2, detection.y2, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            for (kp in detection.keypoints) canvas.drawCircle(kp[0], kp[1], 6f, paint)

            withContext(Dispatchers.Main) {
                processedImageView.visibility = View.VISIBLE
                processedImageView.setImageBitmap(mutableFrame)
                tvModelInfo.text = "✅ FACE DETECTED!\nScore: ${"%.2f".format(detection.score)}\n" +
                        "Box: [${detection.x1.toInt()}, ${detection.y1.toInt()}, ${detection.x2.toInt()}, ${detection.y2.toInt()}]\n" +
                        "Bindi placed at: [${centerX.toInt()}, ${centerY.toInt()}]"
            }

            saveImageToGallery(mutableFrame)
            showToast("Success! AI placed the bindi on the forehead!")

        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Error: ${e.message}")
        }
    }

    private fun saveImageToGallery(bitmap: Bitmap) {
        val resolver = contentResolver
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ShagunAI_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ShagunAI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val imageUri = resolver.insert(imageCollection, contentValues)
        imageUri?.let { uri ->
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
    }

    private fun showBindiGrid() {
        val files = assets.list("bindi")?.filter { it.endsWith(".png") }?.sorted() ?: return
        val dialog = Dialog(this)
        dialog.setTitle("Choose Bindi")
        val grid = GridView(this)
        grid.numColumns = 4
        grid.verticalSpacing = 20
        grid.horizontalSpacing = 20
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.setPadding(20, 20, 20, 20)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(position: Int) = files[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val image = (convertView as? ImageView) ?: ImageView(this@MainActivity)
                image.layoutParams = AbsListView.LayoutParams(170, 170)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                assets.open("bindi/${files[position]}").use { input ->
                    image.setImageBitmap(BitmapFactory.decodeStream(input))
                }
                return image
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            selectedBindiFileName = files[position]
            Toast.makeText(this, "Selected: ${files[position]}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.setContentView(grid)
        dialog.show()
    }
}
