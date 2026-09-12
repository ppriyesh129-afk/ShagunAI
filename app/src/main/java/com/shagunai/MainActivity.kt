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
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var selectedBindiFileName: String? = null
    private lateinit var videoPreview: VideoView
    private lateinit var processedImageView: ImageView

    // ONNX Runtime variables
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession

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

        // 1. Load the AI Model when the app starts
        initOnnxModel()

        val upload = findViewById<Button>(R.id.btnUpload)
        val bindi = findViewById<Button>(R.id.btnBindi)
        val process = findViewById<Button>(R.id.btnProcess)

        upload.setOnClickListener { videoPicker.launch("video/*") }
        bindi.setOnClickListener { showBindiGrid() }

        process.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please upload a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedBindiFileName == null) {
                Toast.makeText(this, "Please choose a bindi first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Processing started...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                processVideoFrame()
            }
        }
    }

    private fun initOnnxModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = assets.open("models/face_detection_short_range.onnx").use { it.readBytes() }
            val sessionOptions = OrtSession.SessionOptions()
            ortSession = ortEnv.createSession(modelBytes, sessionOptions)
            
            // This is crucial! It prints what the AI model expects as input.
            Log.d("ShagunAI_ONNX", "✅ Model loaded successfully!")
            Log.d("ShagunAI_ONNX", "Inputs: ${ortSession.inputNames}")
            Log.d("ShagunAI_ONNX", "Outputs: ${ortSession.outputNames}")
            
        } catch (e: Exception) {
            Log.e("ShagunAI_ONNX", "❌ Failed to load ONNX model", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this, "Failed to load AI model", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun processVideoFrame() {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, selectedVideoUri)
            val originalFrame: Bitmap? = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()

            if (originalFrame == null) {
                showToast("Failed to extract frame.")
                return
            }

            val bindiBitmap = assets.open("bindi/$selectedBindiFileName").use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: return

            val mutableFrame = originalFrame.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableFrame)
            val paint = Paint()

            // For now, we place it in the center. Next step: use ONNX to find the face!
            val bindiWidth = mutableFrame.width / 8
            val bindiHeight = (bindiWidth.toFloat() / bindiBitmap.width * bindiBitmap.height).toInt()
            val scaledBindi = Bitmap.createScaledBitmap(bindiBitmap, bindiWidth, bindiHeight, true)

            val xPos = (mutableFrame.width - bindiWidth) / 2
            val yPos = mutableFrame.height / 4 

            canvas.drawBitmap(scaledBindi, xPos.toFloat(), yPos.toFloat(), paint)

            // Draw a debugging box where the AI is currently looking
            paint.color = Color.RED
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            canvas.drawRect(xPos.toFloat(), yPos.toFloat(), (xPos + bindiWidth).toFloat(), (yPos + bindiHeight).toFloat(), paint)

            // Update the UI with the processed image
            withContext(Dispatchers.Main) {
                processedImageView.setImageBitmap(mutableFrame)
            }

            saveImageToGallery(mutableFrame)
            showToast("Success! Processed image displayed and saved.")

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
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
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
