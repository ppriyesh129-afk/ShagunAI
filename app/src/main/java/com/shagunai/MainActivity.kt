package com.shagunai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Dialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var selectedBindiFileName: String? = null
    private lateinit var videoPreview: VideoView
    private lateinit var tvModelInfo: TextView
    private lateinit var processButton: Button

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
        tvModelInfo = findViewById(R.id.tvModelInfo)
        processButton = findViewById(R.id.btnProcess)

        initOnnxModel()

        findViewById<Button>(R.id.btnUpload).setOnClickListener { videoPicker.launch("video/*") }
        findViewById<Button>(R.id.btnBindi).setOnClickListener { showBindiGrid() }

        // 🎬 PROCESS = FULL VIDEO PIPELINE (decode -> AI -> encode -> play)
        processButton.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please upload a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedBindiFileName == null) {
                Toast.makeText(this, "Please choose a bindi first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            processButton.isEnabled = false
            tvModelInfo.text = "⏳ Starting AI video processing..."

            CoroutineScope(Dispatchers.IO).launch {
                val bindi = assets.open("bindi/$selectedBindiFileName").use { s ->
                    BitmapFactory.decodeStream(s)
                }

                VideoProcessor(
                    this@MainActivity,
                    selectedVideoUri!!,
                    bindi,
                    faceDetector,
                    onProgress = { i, total ->
                        tvModelInfo.text = "⏳ AI processing frame $i / $total\nPlease wait..."
                    },
                    onResult = { uri ->
                        processButton.isEnabled = true
                        if (uri != null) {
                            tvModelInfo.text = "✅ VIDEO COMPLETE!\nSaved to Movies/ShagunAI\nPlaying result above ☝"
                            videoPreview.setVideoURI(uri)
                            videoPreview.start()
                            Toast.makeText(this@MainActivity, "Done! Video saved & playing.", Toast.LENGTH_LONG).show()
                        } else {
                            tvModelInfo.text = "❌ Processing failed. Try a shorter video."
                            Toast.makeText(this@MainActivity, "Processing failed", Toast.LENGTH_LONG).show()
                        }
                    }
                ).process()
            }
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
