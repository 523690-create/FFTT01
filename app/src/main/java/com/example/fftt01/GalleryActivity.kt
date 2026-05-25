package com.example.fftt01

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewToggle: ImageButton
    private var isGridView = false
    private var files = mutableListOf<File>()
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        isGridView = prefs.getBoolean("gallery_is_grid", false)

        recyclerView = findViewById(R.id.recyclerView)
        btnViewToggle = findViewById(R.id.btnViewToggle)

        updateLayoutManager()
        loadFiles()

        btnViewToggle.setOnClickListener {
            isGridView = !isGridView
            prefs.edit().putBoolean("gallery_is_grid", isGridView).apply()
            updateLayoutManager()
            recyclerView.adapter?.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
        UiUtils.autoScaleText(findViewById(R.id.txtTitleGallery))
        UiUtils.autoScaleText(findViewById(R.id.btnBack))
        UiUtils.autoScaleText(findViewById(R.id.btnViewToggle))
        // Full auto-scale pass after layout settles to ensure captions fit
        UiUtils.autoScaleAll(findViewById(android.R.id.content), 1000)
    }

    private fun loadFiles() {
        val filesDir = getExternalFilesDir(null)
        files = filesDir?.listFiles { file -> file.extension == "flac" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: mutableListOf()
        recyclerView.adapter = GalleryAdapter(files)
    }

    private fun updateLayoutManager() {
        if (isGridView) {
            recyclerView.layoutManager = GridLayoutManager(this, 3)
            btnViewToggle.setImageResource(android.R.drawable.ic_menu_sort_by_size)
        } else {
            recyclerView.layoutManager = LinearLayoutManager(this)
            btnViewToggle.setImageResource(android.R.drawable.ic_dialog_dialer)
        }
    }

    private fun showDeleteDialog(file: File, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                val iconFile = File(file.parent, file.nameWithoutExtension + ".png")
                file.delete()
                if (iconFile.exists()) iconFile.delete()
                files.removeAt(position)
                recyclerView.adapter?.notifyItemRemoved(position)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class GalleryAdapter(private val files: List<File>) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.gallery_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.textView.text = file.nameWithoutExtension
            UiUtils.autoScaleText(holder.textView)

            val iconFile = File(file.parent, file.nameWithoutExtension + ".png")
            if (iconFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                holder.imageView.setImageBitmap(bitmap)
            } else {
                holder.imageView.setImageResource(android.R.drawable.ic_media_play)
            }

            holder.root.setOnClickListener {
                val intent = Intent(this@GalleryActivity, ViewerActivity::class.java)
                intent.putExtra("FILE_PATH", file.absolutePath)
                startActivity(intent)
            }

            holder.root.setOnLongClickListener {
                showDeleteDialog(file, position)
                true
            }

            // Adjust layout for grid/list
            if (isGridView) {
                holder.root.orientation = LinearLayout.VERTICAL
                holder.textView.maxLines = 1
                holder.imageView.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                holder.imageView.layoutParams.height = holder.imageView.width
            } else {
                holder.root.orientation = LinearLayout.HORIZONTAL
                holder.textView.maxLines = 2
                holder.imageView.layoutParams.width = holder.imageView.context.resources.displayMetrics.density.toInt() * 64
                holder.imageView.layoutParams.height = holder.imageView.context.resources.displayMetrics.density.toInt() * 64
            }
        }

        override fun getItemCount() = files.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: LinearLayout = view.findViewById(R.id.galleryItemRoot)
            val imageView: ImageView = view.findViewById(R.id.itemIcon)
            val textView: TextView = view.findViewById(R.id.itemText)
        }
    }
}
