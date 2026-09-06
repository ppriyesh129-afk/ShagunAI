package com.shagunai

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var selectedBindiUri: Uri? = null
    private lateinit var videoPreview: VideoView

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                selectedVideoUri = uri
                videoPreview.setVideoURI(uri)
                videoPreview.seekTo(100)

                Toast.makeText(
                    this,
                    "Video selected successfully",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val bindiPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                selectedBindiUri = uri

                Toast.makeText(
                    this,
                    "Bindi selected successfully",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoPreview = findViewById(R.id.videoPreview)

        val upload = findViewById<Button>(R.id.btnUpload)
        val bindi = findViewById<Button>(R.id.btnBindi)
        val process = findViewById<Button>(R.id.btnProcess)

        upload.setOnClickListener {
            videoPicker.launch("video/*")
        }

        bindi.setOnClickListener {
            bindiPicker.launch("image/*")
        }

        process.setOnClickListener {

            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please upload a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (selectedBindiUri == null) {
                Toast.makeText(this, "Please choose a bindi first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Video and bindi ready", Toast.LENGTH_LONG).show()
        }
    }
}
