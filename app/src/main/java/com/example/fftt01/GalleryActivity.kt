package com.example.fftt01

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val filesDir = getExternalFilesDir(null)
        val files = filesDir?.listFiles { file -> file.extension == "flac" }?.sortedByDescending { it.lastModified() } ?: emptyList()

        recyclerView.adapter = GalleryAdapter(files)

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    inner class GalleryAdapter(private val files: List<File>) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.gallery_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.textView.text = file.name
            
            val iconFile = File(file.parent, file.nameWithoutExtension + ".png")
            if (iconFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                holder.imageView.setImageBitmap(bitmap)
            } else {
                holder.imageView.setImageDrawable(null)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@GalleryActivity, ViewerActivity::class.java).apply {
                    putExtra("FILE_PATH", file.absolutePath)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = files.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.itemIcon)
            val textView: TextView = view.findViewById(R.id.itemText)
        }
    }
}
