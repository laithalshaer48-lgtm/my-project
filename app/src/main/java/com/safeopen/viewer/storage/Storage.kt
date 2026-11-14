package com.safeopen.viewer.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** A folder location: either a folder the user granted through the system picker, or a real path. */
sealed class Loc {
    data class Saf(val tree: Uri, val docId: String) : Loc()
    data class Local(val path: String) : Loc()
}

data class Item(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val dir: Loc?,      // set for folders
    val uri: Uri?       // set for files
)

object Storage {

    fun list(ctx: Context, loc: Loc): List<Item> = when (loc) {
        is Loc.Saf -> listSaf(ctx, loc)
        is Loc.Local -> listLocal(loc)
    }.sortedWith(compareBy<Item>({ !it.isDir }, { it.name.lowercase() }))

    private fun listSaf(ctx: Context, loc: Loc.Saf): List<Item> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(loc.tree, loc.docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<Item>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: id
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) -1 else c.getLong(3)
                val mod = if (c.isNull(4)) 0 else c.getLong(4)
                out += if (isDir) Item(name, true, -1, mod, Loc.Saf(loc.tree, id), null)
                else Item(name, false, size, mod, null, DocumentsContract.buildDocumentUriUsingTree(loc.tree, id))
            }
        }
        return out
    }

    private fun listLocal(loc: Loc.Local): List<Item> {
        val dir = File(loc.path)
        return (dir.listFiles() ?: emptyArray()).map { f ->
            if (f.isDirectory) Item(f.name, true, -1, f.lastModified(), Loc.Local(f.path), null)
            else Item(f.name, false, f.length(), f.lastModified(), null, Uri.fromFile(f))
        }
    }

    fun rootOf(tree: Uri): Loc.Saf = Loc.Saf(tree, DocumentsContract.getTreeDocumentId(tree))

    fun treeLabel(tree: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { tree.toString() }
        val path = id.substringAfter(':', id)
        return if (path.isEmpty()) id.substringBefore(':') else path.substringAfterLast('/')
    }
}
