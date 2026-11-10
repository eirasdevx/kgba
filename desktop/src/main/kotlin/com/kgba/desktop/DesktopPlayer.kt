package com.kgba.desktop

import com.kgba.core.Apu
import com.kgba.core.Button
import com.kgba.core.Gba
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Reproductor de escritorio para probar el núcleo sin móvil.
 * Uso: ./gradlew :desktop:run --args="juego.gba [bios.bin]"
 * Teclas: flechas, X=A, Z=B, A=L, S=R, Enter=Start, Retroceso=Select,
 *         Tab (mantener)=avance rápido, F5=guardar estado, F8=cargar estado.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Uso: DesktopPlayer <juego.gba> [bios.bin]"); return
    }
    val romFile = File(args[0])
    val saveFile = File(romFile.path.substringBeforeLast('.') + ".sav")
    val stateFile = File(romFile.path.substringBeforeLast('.') + ".state")
    val gba = Gba(romFile.readBytes(), args.getOrNull(1)?.let { File(it).readBytes() },
        saveFile.takeIf { it.isFile }?.readBytes())

    val image = BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB)
    val pixels = (image.raster.dataBuffer as DataBufferInt).data
    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val s = minOf(width / 240, height / 160).coerceAtLeast(1)
            g.drawImage(image, (width - 240 * s) / 2, (height - 160 * s) / 2, 240 * s, 160 * s, null)
        }
    }
    panel.preferredSize = Dimension(240 * 3, 160 * 3)
    panel.background = java.awt.Color.BLACK

    val ctl = Controls()
    val pendingState = java.util.concurrent.atomic.AtomicInteger(0)
    val keyMap = mapOf(
        KeyEvent.VK_UP to Button.UP, KeyEvent.VK_DOWN to Button.DOWN,
        KeyEvent.VK_LEFT to Button.LEFT, KeyEvent.VK_RIGHT to Button.RIGHT,
        KeyEvent.VK_X to Button.A, KeyEvent.VK_Z to Button.B,
        KeyEvent.VK_A to Button.L, KeyEvent.VK_S to Button.R,
        KeyEvent.VK_ENTER to Button.START, KeyEvent.VK_BACK_SPACE to Button.SELECT,
    )

    val frame = JFrame("KGBA — ${gba.gameTitle}")
    frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    frame.add(panel)
    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.focusTraversalKeysEnabled = false
    frame.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            keyMap[e.keyCode]?.let { ctl.buttons = ctl.buttons or it }
            when (e.keyCode) {
                KeyEvent.VK_TAB -> ctl.fast = true
                KeyEvent.VK_F5 -> pendingState.set(1)
                KeyEvent.VK_F8 -> pendingState.set(2)
            }
        }
        override fun keyReleased(e: KeyEvent) {
            keyMap[e.keyCode]?.let { ctl.buttons = ctl.buttons and it.inv() }
            if (e.keyCode == KeyEvent.VK_TAB) ctl.fast = false
        }
    })
    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosed(e: WindowEvent) { ctl.running = false }
    })
    frame.isVisible = true

    val format = AudioFormat(Apu.SAMPLE_RATE.toFloat(), 16, 2, true, false)
    val line = AudioSystem.getSourceDataLine(format).apply { open(format, 549 * 4 * 6); start() }
    val bytes = ByteArray(gba.apu.buffer.size * 2)
    var frames = 0L
    while (ctl.running) {
        when (pendingState.getAndSet(0)) {
            1 -> stateFile.writeBytes(gba.saveState())
            2 -> if (stateFile.isFile) gba.loadState(stateFile.readBytes())
        }
        gba.setButtons(ctl.buttons)
        gba.runFrame()
        val fast = ctl.fast
        if (fast) { gba.runFrame(); gba.runFrame() }
        System.arraycopy(gba.frame, 0, pixels, 0, pixels.size)
        SwingUtilities.invokeLater { panel.repaint() }
        val n = gba.apu.bufferPos
        gba.apu.bufferPos = 0
        if (fast) {
            Thread.sleep(16)
        } else {
            for (k in 0 until n) {
                val v = gba.apu.buffer[k].toInt()
                bytes[k * 2] = v.toByte(); bytes[k * 2 + 1] = (v shr 8).toByte()
            }
            line.write(bytes, 0, n * 2) // bloquea: marca el ritmo a 59,73 fps
        }
        if (++frames % 300 == 0L && gba.backup.dirty) { gba.backup.dirty = false; saveFile.writeBytes(gba.backup.data) }
    }
    if (gba.backup.dirty) saveFile.writeBytes(gba.backup.data)
    line.close()
}

private class Controls {
    @Volatile var buttons = 0
    @Volatile var fast = false
    @Volatile var running = true
}
