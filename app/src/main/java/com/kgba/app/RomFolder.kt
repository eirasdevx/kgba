package com.kgba.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Carpeta de juegos elegida por el usuario (Storage Access Framework). Se recorre entera,
 * subcarpetas incluidas, y cada .gba/.agb/.zip pasa a la biblioteca. El permiso se conserva
 * entre reinicios, así que la carpeta se vuelve a escanear cada vez que se abre la app.
 */
object RomFolder {
    private const val KEY = "rom_folder"
    private const val MAX_DEPTH = 4
    private val EXTENSIONS = setOf("gba", "agb", "zip")

    fun get(context: Context): Uri? = prefs(context).getString(KEY, null)?.let(Uri::parse)

    fun set(context: Context, tree: Uri) {
        get(context)?.let { old ->
            if (old != tree) runCatching {
                context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(context).edit().putString(KEY, tree.toString()).apply()
    }

    fun clear(context: Context) {
        get(context)?.let { runCatching {
            context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } }
        prefs(context).edit().remove(KEY).apply()
    }

    /** Nombre visible de la carpeta. */
    fun label(context: Context, tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').ifEmpty { "Carpeta" }

    /** ¿Este juego viene de la carpeta [tree]? */
    fun contains(tree: Uri, uri: String) = uri.startsWith("$tree/document/")

    /**
     * Lista los juegos de la carpeta como (uri, nombre sin extensión).
     * Lanza una excepción si la carpeta ya no es accesible.
     */
    fun scan(context: Context, tree: Uri): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        val resolver = context.contentResolver
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) walk(id, depth + 1)
                    } else if (name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                        found += DocumentsContract.buildDocumentUriUsingTree(tree, id).toString() to
                            name.substringBeforeLast('.')
                    }
                }
            } ?: error("no se puede leer la carpeta")
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return found.sortedBy { it.second.lowercase() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("kgba", Context.MODE_PRIVATE)
}
