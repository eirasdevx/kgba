package com.kgba.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/** Lectura de ROMs (también dentro de .zip) y rutas de partidas/estados. */
object RomStore {
    private const val MAX_ROM = 32 * 1024 * 1024

    class Rom(val data: ByteArray, val id: String)

    fun read(context: Context, uri: Uri): Rom {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("no se puede leer el archivo")
        val data = if (raw.size > 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()) unzip(raw) else raw
        require(data.size >= 0xC0) { "el archivo es demasiado pequeño para ser una ROM" }
        require(data.size <= MAX_ROM) { "la ROM supera los 32 MB" }
        val crc = CRC32().apply { update(data) }.value
        val code = String(data, 0xAC, 4, Charsets.US_ASCII).filter { it.isLetterOrDigit() }.ifEmpty { "ROM" }
        return Rom(data, "%s_%08X".format(code, crc))
    }

    private fun unzip(zip: ByteArray): ByteArray {
        ZipInputStream(zip.inputStream()).use { zis ->
            var fallback: ByteArray? = null
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.lowercase()
                if (name.endsWith(".gba") || name.endsWith(".agb") || name.endsWith(".bin")) return zis.readBytes()
                if (fallback == null) fallback = zis.readBytes()
            }
            return fallback ?: error("el .zip está vacío")
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name.substringBeforeLast('.')
            }
        }
        return uri.lastPathSegment ?: "ROM"
    }

    fun saveFile(context: Context, id: String) = File(context.filesDir, "saves").apply { mkdirs() }.resolve("$id.sav")
    const val SLOTS = 10

    /** Archivo del estado de la ranura [slot] (1..[SLOTS]); la 1 es el archivo de versiones anteriores. */
    fun stateFile(context: Context, id: String, slot: Int = 1): File {
        val dir = File(context.filesDir, "states").apply { mkdirs() }
        return dir.resolve(if (slot == 1) "$id.state" else "$id.$slot.state")
    }

    /** Miniatura PNG del estado de la ranura [slot]. */
    fun thumbFile(context: Context, id: String, slot: Int): File =
        File(context.filesDir, "states").apply { mkdirs() }.resolve("$id.$slot.png")
    fun biosFile(context: Context) = File(context.filesDir, "bios.bin")

    private fun prefs(context: Context) = context.getSharedPreferences("kgba", Context.MODE_PRIVATE)

    /** Recuerda la relación ROM ↔ identificador para la biblioteca y el gestor de guardados. */
    fun remember(context: Context, uri: String, id: String, name: String) {
        prefs(context).edit().putString("romid_$uri", id).putString("rom_$id", "$uri\t$name").apply()
    }

    /** Identificador (código + CRC) de una ROM ya abierta alguna vez, o null. */
    fun idFor(context: Context, uri: String): String? = prefs(context).getString("romid_$uri", null)

    /** (uri, nombre) de la ROM con este identificador, si se conoce. */
    fun romFor(context: Context, id: String): Pair<String, String>? =
        prefs(context).getString("rom_$id", null)?.takeIf { '\t' in it }
            ?.let { it.substringBefore('\t') to it.substringAfter('\t') }

    /** Código de cartucho legible, p. ej. «AGB-BZMP», a partir del identificador. */
    fun cartCode(id: String?): String = id?.substringBefore('_')?.takeIf { it.length == 4 && it != "ROM" }
        ?.let { "AGB-$it" } ?: "AGB-????"

    /** Un estado guardado en disco. */
    class SavedState(val id: String, val slot: Int, val file: File, val thumb: File)

    /** Todos los estados guardados, del más reciente al más antiguo. */
    fun allStates(context: Context): List<SavedState> {
        val dir = File(context.filesDir, "states")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".state") } ?: return emptyList()
        return files.mapNotNull { f ->
            val base = f.name.removeSuffix(".state")
            val dot = base.lastIndexOf('.')
            val slot = if (dot > 0) base.substring(dot + 1).toIntOrNull() else 1
            if (slot == null || slot !in 1..SLOTS) return@mapNotNull null
            val id = if (dot > 0) base.substring(0, dot) else base
            SavedState(id, slot, f, thumbFile(context, id, slot))
        }.sortedByDescending { it.file.lastModified() }
    }

    /** Número de ranuras ocupadas de un juego. */
    fun stateCount(context: Context, id: String?): Int =
        if (id == null) 0 else (1..SLOTS).count { stateFile(context, id, it).isFile }

    fun bios(context: Context): ByteArray? {
        val f = biosFile(context)
        return if (f.isFile && f.length() == 0x4000L) f.readBytes() else null
    }
}
