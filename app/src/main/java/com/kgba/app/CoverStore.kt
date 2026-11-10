package com.kgba.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Carátulas originales de las cajas. La ROM se identifica por su CRC32 (y si no, por el código
 * del cartucho o el nombre del archivo) con la base de datos No-Intro de libretro, y la imagen se
 * descarga de las miniaturas de libretro. Se guarda reducida (JPEG ~300 px) para ocupar poco.
 */
object CoverStore {
    private const val DAT_URL =
        "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro/Nintendo%20-%20Game%20Boy%20Advance.dat"
    private val THUMB_BASES = listOf(
        "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/",
        "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/",
    )
    private const val COVER_PX = 320
    private const val RETRY_MS = 3L * 24 * 60 * 60 * 1000  // reintentar los fallidos cada 3 días

    private class Entry(val name: String, val crc: String, val serial: String)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    @Volatile private var index: List<Entry>? = null

    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }
    private fun key(uri: String) = MessageDigest.getInstance("MD5").digest(uri.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** Carátula ya descargada, o null. */
    fun file(context: Context, uri: String): File? = File(dir(context), key(uri) + ".jpg").takeIf { it.isFile }

    fun forget(context: Context, uri: String) {
        File(dir(context), key(uri) + ".jpg").delete()
        File(dir(context), key(uri) + ".none").delete()
        File(dir(context), key(uri) + ".title").delete()
        File(dir(context), key(uri) + ".notitle").delete()
    }

    /** Nombre oficial del juego (No-Intro), ya formateado, o null si aún no se conoce. */
    fun officialTitle(context: Context, uri: String): String? =
        File(dir(context), key(uri) + ".title").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Busca en segundo plano lo que falte: el nombre oficial y la carátula.
     * [done] se llama en el hilo principal si aparece algo nuevo.
     */
    fun request(context: Context, uri: String, displayName: String, done: () -> Unit) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val miss = File(dir(app), key(uri) + ".none")
        val titleFile = File(dir(app), key(uri) + ".title")
        val noTitle = File(dir(app), key(uri) + ".notitle")
        val needCover = file(app, uri) == null && !(miss.isFile && now - miss.lastModified() < RETRY_MS)
        val needTitle = !titleFile.isFile && !(noTitle.isFile && now - noTitle.lastModified() < RETRY_MS)
        if (!needCover && !needTitle) return
        synchronized(pending) { if (!pending.add(uri)) return }
        worker.execute {
            try {
                val ident = identify(app, uri, displayName)
                var changed = false
                if (needTitle) {
                    val official = ident.official?.let { prettyTitle(it) }
                    if (official != null) { titleFile.writeText(official); noTitle.delete(); changed = true }
                    else if (ident.indexed) noTitle.writeText("")
                }
                if (needCover) {
                    if (fetchCover(app, uri, ident)) { miss.delete(); changed = true } else miss.writeText("")
                }
                if (changed) main.post(done)
            } catch (_: Exception) {
                // Sin conexión: se reintenta la próxima vez que se abra la biblioteca.
            } finally {
                synchronized(pending) { pending.remove(uri) }
            }
        }
    }

    /** Lo que se sabe de una ROM: nombre No-Intro exacto (o del mismo juego) y nombres candidatos. */
    private class Identity(val official: String?, val candidates: List<String>, val indexed: Boolean)

    private fun identify(context: Context, uri: String, displayName: String): Identity {
        val candidates = LinkedHashSet<String>()
        candidates += displayName  // muchas ROMs ya tienen el nombre No-Intro
        val (crc, serial) = romIdentity(context, uri)
        val entries = loadIndex(context)
        if (entries.isEmpty()) throw java.io.IOException("índice no disponible")
        val byCrc = crc?.let { c -> entries.firstOrNull { it.crc == c } }
        byCrc?.let { candidates.add(it.name) }
        // Mismo juego en otras regiones: libretro no siempre tiene todas.
        val title = titleOf(byCrc?.name ?: displayName)
        val related = entries.filter {
            (serial != null && it.serial == serial) || normalize(titleOf(it.name)) == normalize(title)
        }.sortedBy { regionRank(it.name) }
        related.forEach { candidates += it.name }
        if (serial != null && related.isEmpty()) {
            // ROM modificada: se prueba con el mismo juego por las 3 primeras letras del código.
            entries.filter { it.serial.length == 4 && it.serial.take(3) == serial.take(3) }
                .sortedBy { regionRank(it.name) }.forEach { candidates += it.name }
        }
        // Nombre oficial: el del CRC exacto; si no, el del mismo código de cartucho.
        val official = byCrc?.name ?: entries.filter { serial != null && it.serial == serial }
            .minByOrNull { regionRank(it.name) }?.name
        return Identity(official, candidates.toList(), true)
    }

    private fun fetchCover(context: Context, uri: String, ident: Identity): Boolean {
        for (name in ident.candidates.take(12)) {
            val bytes = THUMB_BASES.firstNotNullOfOrNull { download(it + encode(name) + ".png") } ?: continue
            if (save(bytes, File(dir(context), key(uri) + ".jpg"))) return true
        }
        return false
    }

    /**
     * «Legend of Zelda, The - The Minish Cap (USA)» → «The Legend of Zelda: The Minish Cap».
     * Quita región/versión y recoloca el artículo que No-Intro pone al final.
     */
    fun prettyTitle(noIntro: String): String {
        val parts = titleOf(noIntro).split(" - ").map { part ->
            val m = Regex("""^(.*), (The|A|An|El|La|Los|Las|Le|Les|Der|Die|Das)$""").find(part)
            if (m != null) "${m.groupValues[2]} ${m.groupValues[1]}" else part
        }
        return parts.joinToString(": ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun romIdentity(context: Context, uri: String): Pair<String?, String?> = try {
        val rom = RomStore.read(context, Uri.parse(uri))  // id = CÓDIGO_CRC
        val crc = rom.id.substringAfterLast('_')
        val serial = String(rom.data, 0xAC, 4, Charsets.US_ASCII).takeIf { s -> s.all { it.isLetterOrDigit() } }
        crc to serial
    } catch (_: Exception) {
        null to null
    }

    /** Índice No-Intro (nombre, CRC, código), descargado una vez y guardado compacto. */
    private fun loadIndex(context: Context): List<Entry> {
        index?.let { return it }
        val cache = File(dir(context), "gba_index.tsv")
        if (!cache.isFile) {
            val dat = download(DAT_URL)?.toString(Charsets.UTF_8) ?: return emptyList()
            val out = StringBuilder()
            Regex("""name "([^"]+)"[^)]*?rom \( name "[^"]*" size \d+ crc ([0-9A-Fa-f]{8})[^)]*?(?:serial "([^"]*)")?\s*\)""")
                .findAll(dat).forEach { m ->
                    out.append(m.groupValues[1]).append('\t').append(m.groupValues[2].uppercase())
                        .append('\t').append(m.groupValues[3]).append('\n')
                }
            if (out.isEmpty()) return emptyList()
            cache.writeText(out.toString())
        }
        return cache.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 3) Entry(p[0], p[1], p[2]) else null
        }.also { index = it }
    }

    private fun titleOf(name: String) = name.substringBefore(" (").substringBefore(" [").trim()
    private fun normalize(s: String) = s.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]"), "")
    private fun regionRank(name: String) = when {
        "(USA" in name || "USA)" in name || ", USA" in name -> 0
        "(Europe" in name || "(World" in name -> 1
        "(Spain" in name -> 2
        "(Japan" in name -> 4
        else -> 3
    } * 10 + (if ("(Beta" in name || "(Proto" in name || "(Demo" in name) 5 else 0)

    /** libretro sustituye estos caracteres por "_" en los nombres de archivo. */
    private fun encode(name: String) =
        URLEncoder.encode(name.replace(Regex("""[&*/:`<>?\\|"]"""), "_"), "UTF-8").replace("+", "%20")

    /** null si no existe (404); los fallos de red se lanzan para no marcar el juego como "sin carátula". */
    private fun download(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "KGBA")
            when (c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }
                in 500..599 -> throw java.io.IOException("servidor no disponible")
                else -> null
            }
        } finally {
            c.disconnect()
        }
    }

    private fun save(png: ByteArray, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_PX) sample *= 2
        val raw = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val s = COVER_PX.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (s < 1f) Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true) else raw
        // Las cajas tienen fondo transparente en algunos casos: se aplana sobre el color del panel.
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.RGB_565)
        android.graphics.Canvas(flat).apply { drawColor(0xFF1C2026.toInt()); drawBitmap(bmp, 0f, 0f, null) }
        val tmp = File(dest.path + ".tmp")
        tmp.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return tmp.renameTo(dest)
    }
}
