package com.shagunai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Dialog
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
    private lateinit var videoPreview: VideoView
    private lateinit var tvModelInfo: TextView
    private lateinit var processButton: Button

    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private lateinit var faceLandmarker: FaceLandmarker

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

        videoPreview.setOnErrorListener { _, what, extra ->
            tvModelInfo.append("\n❌ Player error what=$what extra=$extra\nTrying local fallback...")
            val fallback = cacheDir.listFiles()
                ?.filter { it.name.startsWith("shagun_") && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
            if (fallback != null) {
                videoPreview.setVideoURI(Uri.fromFile(fallback))
                videoPreview.start()
            }
            true
        }

        initOnnxModel()

        findViewById<Button>(R.id.btnUpload).setOnClickListener { videoPicker.launch("video/*") }
        
        // We removed the Bindi grid button since we are drawing it procedurally now!
        findViewById<Button>(R.id.btnBindi).visibility = View.GONE 

        processButton.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please upload a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            processButton.isEnabled = false
            tvModelInfo.text = " Starting AI video processing..."

            CoroutineScope(Dispatchers.IO).launch {
                var processorRef: VideoProcessor? = null
                
                val processor = VideoProcessor(
                    this@MainActivity,
                    selectedVideoUri!!,
                    faceLandmarker,
                    onProgress = { i, total ->
                        tvModelInfo.text = "⏳ AI processing frame $i / $total\nPlease wait..."
                    },
                    onResult = { uri, error ->
                        processButton.isEnabled = true
                        if (uri != null) {
                            tvModelInfo.text = "✅ VIDEO COMPLETE!\n${processorRef?.lastSummary ?: ""}\nSaved to Movies/ShagunAI\nPlaying result above "
                            videoPreview.setVideoURI(uri)
                            videoPreview.requestFocus()
                            videoPreview.start()
                            Toast.makeText(this@MainActivity, "Done! Video saved & playing.", Toast.LENGTH_LONG).show()
                        } else {
                            tvModelInfo.text = "❌ Failed at: $error"
                            Toast.makeText(this@MainActivity, "Processing failed", Toast.LENGTH_LONG).show()
                        }
                    }
                )
                
                processorRef = processor
                BindiRenderer.reset()
                processor.process()
            }
        }
    }

    private fun initOnnxModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            // LOAD THE 478-POINT LANDMARKER MODEL
            val modelBytes = assets.open("models/face_landmarker_Nx3x256x256.onnx").use { it.readBytes() }
            ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            faceLandmarker = FaceLandmarker(ortEnv, ortSession)
            tvModelInfo.text = "✅ 478-Point Landmarker Loaded!\n3D Bindi engine ready.\nUpload a video and Process."
        } catch (e: Exception) {
            Log.e("ShagunAI", "Failed to load model", e)
            tvModelInfo.text = "❌ Failed: ${e.message}"
        }
    }
}
