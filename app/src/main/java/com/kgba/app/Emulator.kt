package com.kgba.app

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import com.kgba.core.Apu
import com.kgba.core.Gba
import com.kgba.core.LinkPort
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hilo de emulación: ejecuta frames, pinta en la Surface y envía el audio.
 * Con sonido activo, la escritura bloqueante en AudioTrack marca el ritmo (59,73 fps);
 * sin sonido o en avance rápido, el ritmo se controla con el reloj.
 */
class Emulator(
    private val rom: ByteArray,
    private val bios: ByteArray?,
    private val saveFile: File,
) : Thread("KGBA-emu") {

    @Volatile var touchButtons = 0
    @Volatile var hwButtons = 0
    @Volatile var fastForward = false
    /** Velocidad del avance rápido (×2, ×3...). 0 = sin límite. */
    @Volatile var ffSpeed = 3
    @Volatile var soundOn = true
    /** Rebobinado: se guarda un estado cada [REWIND_INTERVAL] frames. */
    @Volatile var rewindEnabled = true
    @Volatile var rewinding = false
    /** Filtro de imagen: 0 nítido, 1 suavizado, 2 líneas de barrido, 3 rejilla LCD. */
    @Volatile var filter = 0
        set(v) { field = v; paint.isFilterBitmap = v == 1 }
    /** Imita los colores apagados de la pantalla de la GBA. */
    @Volatile var lcdColors = false
        set(v) { field = v; paint.colorFilter = if (v) LCD_FILTER else null }
    /** Margen superior (muesca de la cámara) en vertical. */
    @Volatile var topInset = 0
    /** Dibuja la carcasa de consola (la imagen queda dentro del marco). */
    @Volatile var skin = true

    // Sensores del cartucho, los rellena la actividad
    @Volatile var lux = 0
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    @Volatile var gyroZ = 0f
    /** Se llama (desde este hilo) cuando el motor de vibración del cartucho cambia. */
    @Volatile var onRumble: ((Boolean) -> Unit)? = null

    /** Cable link con otro móvil (null = sin cable). */
    @Volatile var link: LinkPort? = null
        set(v) { field = v; post { it.sio.link = v; rewindBuf.clear() } }
    private val linked get() = link?.connected == true

    @Volatile private var running = true
    @Volatile private var paused = false
    private val lock = Object()
    private val commands = ConcurrentLinkedQueue<(Gba) -> Unit>()

    private var holder: SurfaceHolder? = null
    private var surfaceW = 0
    private var surfaceH = 0

    var gba: Gba = Gba(rom, bios, saveFile.takeIf { it.isFile }?.readBytes())
        private set

    private val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val linePaint = Paint().apply { color = 0x48000000 }
    private val gridPaint = Paint().apply { color = 0x30000000 }
    private val dst = Rect()

    private val rewindBuf = ArrayDeque<ByteArray>()
    private var rumbleOn = false

    fun setSurface(h: SurfaceHolder?, w: Int, hgt: Int) {
        synchronized(lock) {
            holder = h; surfaceW = w; surfaceH = hgt
        }
        if (paused) post { draw(it) } // repinta aunque esté en pausa
    }

    /** Ejecuta [cmd] en el hilo de emulación entre dos frames. */
    fun post(cmd: (Gba) -> Unit) {
        commands.add(cmd)
        synchronized(lock) { lock.notifyAll() }
    }

    fun pauseEmu() { paused = true }

    fun resumeEmu() {
        paused = false
        synchronized(lock) { lock.notifyAll() }
    }

    fun shutdown() {
        running = false
        resumeEmu()
    }

    fun reset() = post {
        gba = Gba(rom, bios, it.backup.data.copyOf())
        gba.sio.link = link
        rewindBuf.clear()
    }

    /** Guarda la partida (memoria del cartucho) si ha cambiado. Llamar desde el hilo de emulación. */
    private fun flushSave(g: Gba) {
        val b = g.backup
        if (!b.dirty || b.data.isEmpty()) return
        b.dirty = false
        try {
            val tmp = File(saveFile.path + ".tmp")
            tmp.writeBytes(b.data)
            if (!tmp.renameTo(saveFile)) { saveFile.delete(); tmp.renameTo(saveFile) }
        } catch (e: Exception) {
            Log.e("KGBA", "no se pudo guardar la partida", e)
        }
    }

    fun requestSave() = post { flushSave(it) }

    /** Guarda un estado en [file] y su miniatura en [thumb]. [done] recibe null o el error. */
    fun saveStateTo(file: File, thumb: File, done: (String?) -> Unit) = post { g ->
        val err = try {
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(g.saveState())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            val bmp = Bitmap.createBitmap(g.frame, 240, 160, Bitmap.Config.ARGB_8888)
            thumb.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        done(err)
    }

    /** Carga el estado de [file]. [done] recibe null o el error. */
    fun loadStateFrom(file: File, done: (String?) -> Unit) = post { g ->
        val err = try {
            g.loadState(file.readBytes())
            rewindBuf.clear()
            g.runFrame(); g.apu.bufferPos = 0 // para tener ya la imagen del estado
            draw(g)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        done(err)
    }

    /** Copia del último frame (ARGB 240x160). */
    fun capture(done: (IntArray) -> Unit) = post { done(it.frame.copyOf()) }

    override fun run() {
        val track = createAudio()
        var nextFrame = System.nanoTime()
        var frames = 0L
        try {
            while (running) {
                while (true) commands.poll()?.invoke(gba) ?: break
                if (paused) {
                    track?.pause()
                    flushSave(gba)
                    setRumble(false)
                    synchronized(lock) { while (paused && running && commands.isEmpty()) lock.wait(250) }
                    nextFrame = System.nanoTime()
                    continue
                }
                val g = gba
                g.setButtons(touchButtons or hwButtons)
                g.sensors.lux = lux; g.sensors.tiltX = tiltX; g.sensors.tiltY = tiltY; g.sensors.gyroZ = gyroZ

                // Con cable link no se rebobina: la otra consola no puede volver atrás
                val rew = rewinding && rewindEnabled && !linked
                val ff = fastForward && !rew
                if (rew) {
                    // Un estado atrás cada 2 frames: unas 5 veces más rápido que el juego
                    if (frames % 2 == 0L) rewindBuf.removeLastOrNull()?.let {
                        try { g.loadState(it) } catch (e: Exception) { rewindBuf.clear() }
                        g.runFrame()
                    }
                    g.apu.bufferPos = 0
                } else {
                    val steps = if (ff) (if (ffSpeed <= 0) 6 else ffSpeed) else 1
                    for (i in 0 until steps) {
                        if (rewindEnabled && !linked && g.frameCount % REWIND_INTERVAL == 0L) pushRewind(g)
                        g.runFrame()
                        if (ff || !soundOn) g.apu.bufferPos = 0
                    }
                }
                draw(g)
                frames++
                if (frames % 300 == 0L) flushSave(g)
                setRumble(g.gpio?.rumble == true)

                if (soundOn && !ff && !rew && track != null) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    track.write(g.apu.buffer, 0, g.apu.bufferPos)
                    g.apu.bufferPos = 0
                    nextFrame = System.nanoTime()
                } else {
                    track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) { it.pause(); it.flush() } }
                    if (ff && ffSpeed <= 0) { nextFrame = System.nanoTime(); continue }
                    nextFrame += FRAME_NS
                    val wait = nextFrame - System.nanoTime()
                    if (wait > 0) sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    else if (wait < -FRAME_NS * 4) nextFrame = System.nanoTime()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            flushSave(gba)
            setRumble(false)
            track?.release()
        }
    }

    private fun pushRewind(g: Gba) {
        try {
            rewindBuf.addLast(g.saveState(fast = true))
            while (rewindBuf.size > REWIND_STATES) rewindBuf.removeFirst()
        } catch (e: Exception) {
            Log.e("KGBA", "rebobinado", e)
        }
    }

    private fun setRumble(on: Boolean) {
        if (on == rumbleOn) return
        rumbleOn = on
        onRumble?.invoke(on)
    }

    private fun draw(g: Gba) {
        synchronized(lock) {
            val h = holder ?: return
            if (surfaceW <= 0 || surfaceH <= 0) return
            bitmap.setPixels(g.frame, 0, 240, 0, 0, 240, 160)
            val canvas = try {
                if (Build.VERSION.SDK_INT >= 26) h.lockHardwareCanvas() else h.lockCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                canvas.drawColor(BACKGROUND)
                // The buffer can change size during rotation before surfaceChanged arrives.
                layoutScreen(canvas.width, canvas.height, topInset, skin, dst)
                canvas.drawBitmap(bitmap, null, dst, paint)
                val f = filter
                if (f >= 2 && dst.height() >= 320) {
                    val dh = dst.height() / 160f
                    val th = maxOf(1f, dh / 3f)
                    for (y in 1..160) {
                        val b = dst.top + y * dh
                        canvas.drawRect(dst.left.toFloat(), b - th, dst.right.toFloat(), b, linePaint)
                    }
                    if (f == 3) {
                        val dw = dst.width() / 240f
                        val tw = maxOf(1f, dw / 4f)
                        for (x in 1..240) {
                            val r = dst.left + x * dw
                            canvas.drawRect(r - tw, dst.top.toFloat(), r, dst.bottom.toFloat(), gridPaint)
                        }
                    }
                }
            } finally {
                h.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun createAudio(): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(
            Apu.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        // unos 4 frames de margen: poca latencia y sin cortes
        val size = maxOf(min, 549 * 4 * 4)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Apu.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (e: Exception) {
        Log.e("KGBA", "sin audio", e)
        null
    }

    companion object {
        private const val FRAME_NS = 16_742_706L // 1 / 59,7275 Hz
        private const val REWIND_INTERVAL = 10L  // 6 estados por segundo
        private const val REWIND_STATES = 90     // 15 segundos
        const val BACKGROUND = 0xFF0E0E12.toInt()

        /** Colores de la pantalla de la GBA: menos saturados y algo más oscuros. */
        private val LCD_FILTER = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.80f, 0.15f, 0.05f, 0f, 0f,
            0.10f, 0.75f, 0.15f, 0f, 0f,
            0.10f, 0.15f, 0.75f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))

        /**
         * Dónde se dibuja la imagen de 240x160. Sin carcasa: en vertical ocupa todo el ancho
         * arriba y en horizontal va centrada. Con carcasa queda dentro del marco: en vertical,
         * en la tapa de la consola plegable; en horizontal, en el centro de la consola apaisada.
         */
        /**
         * Márgenes del marco de pantalla de la carcasa importada, como fracción del LCD
         * (izq, arr, der, abj). Vacío si no hay carcasa en imágenes.
         */
        @Volatile var frameInsets = android.graphics.RectF()

        /** Sin carcasa en imágenes se usa el marco clásico dibujado (más fino en horizontal). */
        @Volatile var classicFrame = true
        /** Vertical: tapa de portátil plegable con marco negro grueso y logotipo abajo. */
        private val CLASSIC_PORTRAIT = android.graphics.RectF(0.05f, 0.08f, 0.05f, 0.15f)
        /** En horizontal, marco negro grueso estilo portátil apaisada (logotipo en la franja inferior). */
        private val CLASSIC_LANDSCAPE = android.graphics.RectF(0.11f, 0.11f, 0.11f, 0.17f)
        private val NO_FRAME = android.graphics.RectF()

        /** Márgenes del marco para este tamaño de vista (no modificar el resultado). */
        fun insets(w: Int, h: Int, skin: Boolean): android.graphics.RectF = when {
            !skin -> NO_FRAME
            classicFrame -> if (h > w) CLASSIC_PORTRAIT else CLASSIC_LANDSCAPE
            else -> frameInsets
        }

        /**
         * Zona de la cámara (muesca/agujero) en píxeles de la vista. La carcasa se dibuja por
         * debajo de ella, pero la imagen del juego y los controles la esquivan.
         */
        @Volatile var cutout = Rect()

        /** Márgenes seguros para una vista w×h; a los lados se igualan para que todo quede centrado. */
        fun safeInsets(w: Int, h: Int): Rect {
            val c = cutout
            val side = maxOf(c.left, c.right)
            return Rect(side, c.top, side, c.bottom)
        }

        /** Hueco de la bisagra en vertical (carcasa clásica), en proporción al ancho. */
        const val HINGE = 0.055f

        fun layoutScreen(w: Int, h: Int, topInset: Int, skin: Boolean, out: Rect) {
            if (w <= 0 || h <= 0) { out.setEmpty(); return }
            val portrait = h > w
            val safe = safeInsets(w, h)
            layoutSafe(w - safe.left - safe.right, h - safe.top - safe.bottom, portrait, topInset, skin, out)
            out.offset(safe.left, safe.top)
        }

        private fun layoutSafe(w: Int, h: Int, portrait: Boolean, topInset: Int, skin: Boolean, out: Rect) {
            if (w <= 0 || h <= 0) { out.setEmpty(); return }
            val availableH = (h - topInset).coerceAtLeast(1)
            val fi = insets(if (portrait) 1 else 2, if (portrait) 2 else 1, skin)
            val fx = 1f + fi.left + fi.right
            val fy = 1f + fi.top + fi.bottom
            if (skin && classicFrame) {
                // Carcasa clásica. Vertical: la tapa ocupa la mitad superior (como una portátil
                // plegable) y la pantalla se ajusta a ella casi de lado a lado. Horizontal: pantalla
                // grande, con la franja de accesos rápidos encima del marco.
                val lid = if (portrait) (availableH - w * HINGE) / 2 else 0f
                val scale = if (portrait) minOf(w * 0.95f / (240f * fx), lid * 0.94f / (160f * fy))
                else minOf(availableH * 0.64f / 160f, (w - availableH * 0.72f) / (240f * fx), availableH * 0.84f / (160f * fy))
                val dw = (240 * scale).toInt().coerceAtLeast(1)
                val dh = (160 * scale).toInt().coerceAtLeast(1)
                val left = (w - dw) / 2 + ((fi.left - fi.right) * dw / 2).toInt()
                val bezelH = dh * fy
                val top = topInset + (if (portrait) (lid - bezelH) * 0.45f + fi.top * dh
                else availableH - availableH * 0.045f - bezelH + fi.top * dh).toInt()
                out.set(left, top, left + dw, top + dh)
                return
            }
            val scale = if (portrait) {
                val widthShare = if (fi.isEmpty) (if (skin) 0.90f else 0.96f) else 0.94f
                minOf(w * widthShare / (240f * fx), availableH * 0.43f / (160f * fy))
            } else {
                // Reserve only the side controls and a thin frame; fit the whole 3:2 image.
                val side = minOf(w * 0.17f, availableH * 0.34f)
                minOf((w - side * 2) / (240f * fx), availableH * 0.96f / (160f * fy))
            }
            val dw = (240 * scale).toInt().coerceAtLeast(1)
            val dh = (160 * scale).toInt().coerceAtLeast(1)
            val left = (w - dw) / 2 + ((fi.left - fi.right) * dw / 2).toInt()
            val top = topInset + if (portrait) {
                maxOf((availableH * 0.035f).toInt(), (fi.top * dh + availableH * 0.02f).toInt())
            } else (availableH - dh) / 2 + ((fi.top - fi.bottom) * dh / 2).toInt()
            out.set(left, top, left + dw, top + dh)
        }
    }
}
