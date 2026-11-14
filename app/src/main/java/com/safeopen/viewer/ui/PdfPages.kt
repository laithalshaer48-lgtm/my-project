package com.safeopen.viewer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * Renders PDF pages with Android's built-in PdfRenderer. PdfRenderer only draws page
 * content: it has no JavaScript engine, cannot launch programs and cannot open links,
 * so active PDF content is inert here.
 */
class PdfDocument(file: File) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Any()
    @Volatile private var closed = false
    val pageCount = renderer.pageCount
    val sizes: List<Pair<Int, Int>> = (0 until pageCount).map { i ->
        synchronized(lock) { renderer.openPage(i).use { it.width to it.height } }
    }

    fun render(index: Int, targetWidth: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        val (pw, ph) = sizes[index]
        val w = targetWidth.coerceIn(200, 4096)
        val h = (w.toLong() * ph / pw).toInt().coerceIn(1, 8192)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        renderer.openPage(index).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        bmp
    }

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; renderer.close(); fd.close() }
    }
}

class PdfPageAdapter(private val doc: PdfDocument, private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<PdfPageAdapter.VH>() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    class VH(val image: ImageView) : RecyclerView.ViewHolder(image)

    override fun getItemCount() = doc.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val iv = ImageView(parent.context)
        iv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8, 0, 8)
        }
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setBackgroundColor(Color.WHITE)
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val width = (holder.itemView.parent as? ViewGroup)?.width?.takeIf { it > 0 } ?: holder.itemView.resources.displayMetrics.widthPixels
        val (pw, ph) = doc.sizes[position]
        holder.image.minimumHeight = (width.toLong() * ph / pw).toInt()
        holder.image.contentDescription = "${position + 1}"
        holder.image.setOnClickListener { onClick(position) }
        val cached = cache.get(position)
        if (cached != null) { holder.image.setImageBitmap(cached); return }
        holder.image.setImageDrawable(null)
        holder.image.tag = position
        executor.execute {
            val bmp = try { doc.render(position, width) } catch (e: Exception) { null } ?: return@execute
            cache.put(position, bmp)
            main.post { if (holder.image.tag == position) holder.image.setImageBitmap(bmp) }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        cache.evictAll()
    }
}
