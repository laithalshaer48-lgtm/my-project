package com.safeopen.viewer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.safeopen.viewer.R

class EntryAdapter(
    private var rows: List<Row>
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    class Row(
        val title: String,
        val subtitle: String,
        val icon: Int,
        val onLongClick: (() -> Unit)? = null,
        val onClick: () -> Unit
    )

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        holder.icon.setImageResource(r.icon)
        holder.title.text = r.title
        holder.subtitle.text = r.subtitle
        holder.subtitle.visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { r.onClick() }
        holder.itemView.setOnLongClickListener { r.onLongClick?.invoke(); r.onLongClick != null }
    }

    companion object {
        fun iconFor(name: String, isDir: Boolean): Int {
            if (isDir) return R.drawable.ic_folder
            return when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif" -> R.drawable.ic_image
                "mp4", "mkv", "webm", "avi", "3gp", "mov", "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr" -> R.drawable.ic_media
                "zip", "rar", "7z", "gz", "tar", "apk", "jar" -> R.drawable.ic_archive
                else -> R.drawable.ic_file
            }
        }
    }
}
