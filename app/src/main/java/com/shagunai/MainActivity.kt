package com.shagunai

import android.app.Dialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var selectedBindiUri: Uri? = null
    private lateinit var videoPreview: VideoView

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

        val upload = findViewById<Button>(R.id.btnUpload)
        val bindi = findViewById<Button>(R.id.btnBindi)
        val process = findViewById<Button>(R.id.btnProcess)

        upload.setOnClickListener {
            videoPicker.launch("video/*")
        }

        bindi.setOnClickListener {
            showBindiGrid()
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

            Toast.makeText(this, "Video and Bindi ready", Toast.LENGTH_LONG).show()
        }
    }

    private fun showBindiGrid() {

        val files = assets.list("bindi")
            ?.filter { it.endsWith(".png") }
            ?.sorted()
            ?: return

        val dialog = Dialog(this)
        val grid = GridView(this)

        grid.numColumns = 4
        grid.verticalSpacing = 20
        grid.horizontalSpacing = 20
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH

        grid.adapter = object : BaseAdapter() {

            override fun getCount() = files.size

            override fun getItem(position: Int) = files[position]

            override fun getItemId(position: Int) = position.toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {

                val image = (convertView as? ImageView) ?: ImageView(this@MainActivity)

                image.layoutParams = GridView.LayoutParams(170, 170)
                image.scaleType = ImageView.ScaleType.FIT_CENTER

                val input = assets.open("bindi/${files[position]}")
                image.setImageBitmap(BitmapFactory.decodeStream(input))
                input.close()

                return image
            }
        }

        grid.setOnItemClickListener { _, _, position, _ ->
            selectedBindiUri = Uri.parse("file:///android_asset/bindi/${files[position]}")
            Toast.makeText(this, "Selected: ${files[position]}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setContentView(grid)
        dialog.show()
    }
}
