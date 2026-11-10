package com.kgba.desktop

import com.kgba.core.Apu
import com.kgba.core.Gba
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Ejecuta una ROM sin ventana y guarda capturas PNG (y opcionalmente el audio en WAV).
 * Útil para pruebas automáticas.
 *
 * Uso: Headless <rom.gba> <frames> <salida.png> [--bios bios.bin] [--keys 100:8,104:0]
 *               [--shots 200,400] [--wav audio.wav]
 *
 * --keys: lista frame:máscara (bits de com.kgba.core.Button) que se aplican desde ese frame.
 * --shots: frames en los que guardar capturas extra (salida-<frame>.png).
 */
fun main(args: Array<String>) {
    val rom = File(args[0]).readBytes()
    val frames = args.getOrElse(1) { "300" }.toInt()
    val out = File(args.getOrElse(2) { "out.png" })
    var bios: ByteArray? = null
    val keys = sortedMapOf<Int, Int>()
    val shots = mutableSetOf<Int>()
    var wav: File? = null
    var stateAt = -1
    var i = 3
    while (i < args.size) {
        when (args[i]) {
            "--bios" -> bios = File(args[++i]).readBytes()
            "--keys" -> args[++i].split(",").forEach { val (f, m) = it.split(":"); keys[f.toInt()] = m.toInt() }
            "--shots" -> args[++i].split(",").forEach { shots += it.toInt() }
            "--wav" -> wav = File(args[++i])
            "--statetest" -> stateAt = args[++i].toInt()
        }
        i++
    }
    val gba = Gba(rom, bios)
    val pcm = ByteArrayOutputStream()
    val t0 = System.nanoTime()
    for (f in 0 until frames) {
        keys[f]?.let { gba.setButtons(it) }
        gba.runFrame()
        if (wav != null) {
            val bb = ByteBuffer.allocate(gba.apu.bufferPos * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until gba.apu.bufferPos) bb.putShort(gba.apu.buffer[k])
            pcm.write(bb.array())
        }
        gba.apu.bufferPos = 0
        if (f in shots) save(gba, File(out.path.removeSuffix(".png") + "-$f.png"))
    }
    val ms = (System.nanoTime() - t0) / 1_000_000.0
    if (stateAt >= 0) stateTest(rom, bios, keys, stateAt, frames)
    save(gba, out)
    wav?.let { writeWav(it, pcm.toByteArray()) }
    println(
        "${gba.gameTitle}: $frames frames en %.0f ms (%.1f fps), guardado=${gba.backup.typeName}, pc=%08X thumb=${gba.cpu.thumb} r12=${gba.cpu.r[12]}"
            .format(ms, frames * 1000 / ms, gba.cpu.curPc)
    )
}

private fun save(gba: Gba, f: File) {
    val img = BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, 240, 160, gba.frame, 0, 240)
    ImageIO.write(img, "png", f)
}

private fun writeWav(f: File, data: ByteArray) {
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(Apu.SAMPLE_RATE)
        .putInt(Apu.SAMPLE_RATE * 4).putShort(4).putShort(16)
    h.put("data".toByteArray()).putInt(data.size)
    f.writeBytes(h.array() + data)
}

/** Comprueba que guardar y cargar un estado reproduce exactamente la misma ejecución. */
private fun stateTest(rom: ByteArray, bios: ByteArray?, keys: Map<Int, Int>, at: Int, frames: Int) {
    val a = Gba(rom, bios)
    var state = ByteArray(0)
    for (f in 0 until frames) {
        keys[f]?.let { a.setButtons(it) }
        if (f == at) state = a.saveState()
        a.runFrame(); a.apu.bufferPos = 0
    }
    val ref = a.frame.copyOf()
    val b = Gba(rom, bios)
    b.loadState(state)
    var lastKeys = keys.filterKeys { it < at }.maxByOrNull { it.key }?.value ?: 0
    b.setButtons(lastKeys)
    for (f in at until frames) {
        keys[f]?.let { b.setButtons(it) }
        b.runFrame(); b.apu.bufferPos = 0
    }
    val same = ref.contentEquals(b.frame)
    println("estado: ${state.size} bytes, reproducción idéntica = $same")
}
