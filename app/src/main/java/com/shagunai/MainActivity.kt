package com.shagunai

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val upload = findViewById<Button>(R.id.btnUpload)
        val bindi = findViewById<Button>(R.id.btnBindi)
        val process = findViewById<Button>(R.id.btnProcess)

        upload.setOnClickListener {
            Toast.makeText(this, "Upload Video", Toast.LENGTH_SHORT).show()
        }

        bindi.setOnClickListener {
            Toast.makeText(this, "Select Bindi", Toast.LENGTH_SHORT).show()
        }

        process.setOnClickListener {
            Toast.makeText(this, "Processing Coming Soon", Toast.LENGTH_SHORT).show()
        }
    }
}
