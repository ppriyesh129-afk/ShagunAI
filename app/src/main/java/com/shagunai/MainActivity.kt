package com.shagunai

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var selectedBindiUri: Uri? = null

    private val videoPicker =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->

            if (uri != null) {
                selectedVideoUri = uri

                Toast.makeText(
                    this,
                    "Video selected successfully",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val bindiPicker =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->

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

        val upload = findViewById<Button>(R.id.btnUpload)
        val bindi = findViewById<Button>(R.id.btnBindi)
        val process = findViewById<Button>(R.id.btnProcess)

        // -----------------------------
        // UPLOAD VIDEO
        // -----------------------------

        upload.setOnClickListener {

            videoPicker.launch("video/*")
        }

        // -----------------------------
        // CHOOSE BINDI
        // -----------------------------

        bindi.setOnClickListener {

            bindiPicker.launch("image/*")
        }

        // -----------------------------
        // PROCESS VIDEO
        // -----------------------------

        process.setOnClickListener {

            val video = selectedVideoUri
            val bindiImage = selectedBindiUri

            if (video == null) {

                Toast.makeText(
                    this,
                    "Please upload a video first",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            if (bindiImage == null) {

                Toast.makeText(
                    this,
                    "Please choose a bindi first",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            Toast.makeText(
                this,
                "Video and bindi ready",
                Toast.LENGTH_LONG
            ).show()

            /*
             * PHASE 2 PROCESSING PIPELINE
             *
             * Video
             *   ↓
             * Extract video frames
             *   ↓
             * Detect face
             *   ↓
             * Track face
             *   ↓
             * Calculate forehead position
             *   ↓
             * Place Bindi
             *   ↓
             * Track Sindoor region
             *   ↓
             * Track Mangalsutra region
             *   ↓
             * Render frames
             *   ↓
             * Encode MP4
             *   ↓
             * Save output video
             */
        }
    }
}
