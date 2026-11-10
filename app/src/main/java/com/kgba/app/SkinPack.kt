package com.kgba.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Carcasas en imágenes (formato de piezas sueltas: bg_portrait_20x9.png, screen_gba.png, a.png,
 * cross_ul.png...). Se importan desde un .zip a la carpeta privada de la app; la app no incluye
 * ninguna. Un .zip puede ser una carcasa o un paquete con varias carcasas en .zip dentro.
 */
class SkinPack private constructor(private val dir: File) {
    val name: String get() = dir.name

    /** Marco de la pantalla y la zona del LCD dentro de él (en píxeles del PNG). */
    var frame: Bitmap? = null; private set
    val frameInner = RectF()

    private val cache = HashMap<String, Bitmap?>()

    /** Imagen de un control ("a", "a_pressed", "cross_ul"...), o null si la carcasa no la trae. */
    fun image(key: String): Bitmap? = cache.getOrPut(key) {
        val f = File(dir, "$key.png")
        if (f.isFile) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    /** Márgenes del marco respecto al LCD, como fracción del tamaño del LCD (izq, arr, der, abj). */
    fun frameInsets(out: RectF) {
        val fr = frame
        if (fr == null || frameInner.isEmpty) { out.setEmpty(); return }
        val iw = frameInner.width(); val ih = frameInner.height()
        out.set(frameInner.left / iw, frameInner.top / ih, (fr.width - frameInner.right) / iw,
            (fr.height - frameInner.bottom) / ih)
    }

    /** Fondo de la carcasa con la relación de aspecto más cercana, escalado a [w]x[h]. */
    fun background(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val portrait = h > w
        val prefix = if (portrait) "bg_portrait_" else "bg_landscape_"
        val target = maxOf(w, h).toFloat() / minOf(w, h)
        val best = dir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".png") }
            ?.mapNotNull { f ->
                val tag = f.name.removePrefix(prefix).removeSuffix(".png")
                val ratio = when {
                    tag.startsWith("fold") -> 1.15f
                    else -> Regex("""^(\d+(?:\.\d+)?)x(\d+(?:\.\d+)?)$""").find(tag)?.let {
                        it.groupValues[1].toFloat() / it.groupValues[2].toFloat()
                    }
                } ?: return@mapNotNull null  // ignora variantes como "22x9-1"
                f to abs(ratio - target)
            }?.minByOrNull { it.second }?.first ?: return null
        return runCatching { decodeScaled(best, w, h) }.getOrNull()
    }

    private fun load(): SkinPack {
        val f = FRAMES.map { File(dir, it) }.firstOrNull { it.isFile } ?: return this
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return this
        findInner(bmp, frameInner)
        // Un marco cuyo hueco no se encuentra no se usa: taparía el juego.
        if (frameInner.width() < bmp.width * 0.4f || frameInner.height() < bmp.height * 0.4f) {
            frameInner.setEmpty(); bmp.recycle()
        } else frame = bmp
        return this
    }

    companion object {
        private val FRAMES = listOf("screen_gba.png", "sticker_screen-gba.png", "sticker_screen-gba-alt.png")
        private const val PREF = "skin_pack"

        fun root(context: Context) = File(context.filesDir, "skins").apply { mkdirs() }

        fun installed(context: Context): List<String> =
            root(context).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

        fun activeName(context: Context): String? =
            context.getSharedPreferences("kgba", Context.MODE_PRIVATE).getString(PREF, null)
                ?.takeIf { File(root(context), it).isDirectory }

        fun setActive(context: Context, name: String?) {
            context.getSharedPreferences("kgba", Context.MODE_PRIVATE).edit().putString(PREF, name).apply()
        }

        fun delete(context: Context, name: String) {
            File(root(context), name).deleteRecursively()
            if (activeName(context) == null) setActive(context, null)
        }

        fun loadActive(context: Context): SkinPack? =
            activeName(context)?.let { SkinPack(File(root(context), it)).load() }

        /** Nombre legible: "PizzaBoyACS GBA-Red.zip" → "GBA-Red". */
        fun cleanName(raw: String) = raw.substringAfterLast('/').removeSuffix(".zip")
            .removePrefix("PizzaBoyACS").trim().replace(Regex("""[^\w .\-]"""), "_").ifEmpty { "Carcasa" }

        private fun isSkinImage(name: String) = name.endsWith(".png", true)

        /**
         * Lista el contenido del .zip: si dentro hay otros .zip es un paquete y devuelve sus
         * carcasas (sin las "-Shared", que se mezclan solas); si no, devuelve una lista vacía.
         */
        fun listPack(context: Context, uri: Uri): List<String> = open(context, uri) { zis ->
            val names = mutableListOf<String>()
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".zip", true)) names += e.name
            }
            names.filterNot { cleanName(it).endsWith("-Shared", true) }
        }

        /** Importa una carcasa suelta (el .zip contiene las imágenes). */
        fun importSingle(context: Context, uri: Uri): String {
            val name = cleanName(RomStore.displayName(context, uri))
            val dest = File(root(context), name).apply { deleteRecursively(); mkdirs() }
            val count = open(context, uri) { extract(it, dest) }
            if (count == 0) { dest.deleteRecursively(); error("el .zip no contiene imágenes de carcasa") }
            return name
        }

        /** Importa del paquete las carcasas elegidas, completadas con su "-Shared" si existe. */
        fun importFromPack(context: Context, uri: Uri, chosen: List<String>): List<String> {
            if (chosen.isEmpty()) return emptyList()
            val shared = chosen.associateWith { entry ->
                val clean = cleanName(entry)
                if ('-' in clean) entry.substringBeforeLast('-') + "-Shared.zip" else null
            }
            val wanted = chosen.toSet() + shared.values.filterNotNull()
            val temp = File(context.cacheDir, "skin_import").apply { deleteRecursively(); mkdirs() }
            try {
                // Una pasada por el paquete: se extraen solo los .zip que hacen falta.
                open(context, uri) { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        if (e.name in wanted) {
                            val out = File(temp, cleanName(e.name)).apply { mkdirs() }
                            extract(ZipInputStream(zis), out, closeInner = false)
                        }
                    }
                }
                return chosen.mapNotNull { entry ->
                    val name = cleanName(entry)
                    val dest = File(root(context), name).apply { deleteRecursively(); mkdirs() }
                    shared[entry]?.let { File(temp, cleanName(it)).listFiles()?.forEach { f -> f.copyTo(File(dest, f.name), true) } }
                    File(temp, name).listFiles()?.forEach { f -> f.copyTo(File(dest, f.name), true) }
                    if (dest.list()?.isNotEmpty() == true) name else { dest.deleteRecursively(); null }
                }
            } finally {
                temp.deleteRecursively()
            }
        }

        private fun <T> open(context: Context, uri: Uri, block: (ZipInputStream) -> T): T =
            (context.contentResolver.openInputStream(uri) ?: error("no se puede leer el archivo")).use { input ->
                ZipInputStream(input.buffered(1 shl 16)).use(block)
            }

        private fun extract(zis: ZipInputStream, dest: File, closeInner: Boolean = true): Int {
            var count = 0
            while (true) {
                val e = zis.nextEntry ?: break
                val file = e.name.substringAfterLast('/')
                if (e.isDirectory || !isSkinImage(file) || file.startsWith(".")) continue
                File(dest, file.lowercase()).outputStream().use { copy(zis, it) }
                count++
            }
            if (closeInner) zis.close()
            return count
        }

        private fun copy(input: InputStream, out: java.io.OutputStream) {
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        }

        private fun decodeScaled(f: File, w: Int, h: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (raw.width == w && raw.height == h) return raw
            return Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
        }

        /** Busca el rectángulo del LCD: desde el centro, mientras el color se parezca al del centro. */
        private fun findInner(bmp: Bitmap, out: RectF) {
            val cx = bmp.width / 2; val cy = bmp.height / 2
            val ref = bmp.getPixel(cx, cy)
            fun same(x: Int, y: Int): Boolean {
                val p = bmp.getPixel(x, y)
                return abs(Color.alpha(p) - Color.alpha(ref)) < 40 && abs(Color.red(p) - Color.red(ref)) < 40 &&
                    abs(Color.green(p) - Color.green(ref)) < 40 && abs(Color.blue(p) - Color.blue(ref)) < 40
            }
            var l = cx; while (l > 0 && same(l - 1, cy)) l--
            var r = cx; while (r < bmp.width - 1 && same(r + 1, cy)) r++
            var t = cy; while (t > 0 && same(cx, t - 1)) t--
            var b = cy; while (b < bmp.height - 1 && same(cx, b + 1)) b++
            out.set(l.toFloat(), t.toFloat(), (r + 1).toFloat(), (b + 1).toFloat())
        }
    }
}
