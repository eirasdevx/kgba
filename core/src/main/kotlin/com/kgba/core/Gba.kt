package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * La consola completa. Uso típico desde el frontend:
 *
 *     val gba = Gba(rom, bios = null, save = datosGuardados)
 *     while (jugando) {
 *         gba.setButtons(botones)
 *         gba.runFrame()          // ~1/59.73 s de emulación
 *         pintar(gba.frame)       // IntArray ARGB 240x160
 *         audio(gba.apu.buffer, gba.apu.bufferPos); gba.apu.bufferPos = 0
 *     }
 */
class Gba(rom: ByteArray, bios: ByteArray? = null, save: ByteArray? = null) {
    companion object {
        const val CYCLES_PER_FRAME = 280896
        private const val STATE_MAGIC = 0x4B474241 // "KGBA"
        private const val STATE_VERSION = 2
    }

    val io = ByteArray(0x400)
    val bus = Bus(this)
    val cpu = Cpu(bus)
    val ppu = Ppu(this)
    val apu = Apu(this)
    val dma = Dma(this)
    val timers = Timers(this)
    /** Puerto serie / cable link. Asigna [Sio.link] para conectar con otra consola. */
    val sio = Sio(this)
    val backup: Backup
    val usingRealBios = bios != null && bios.size >= 0x4000
    /** Sensores del cartucho (luz, inclinación, giro): los rellena el frontend. */
    val sensors = Sensors()
    /** Puerto GPIO del cartucho (RTC, sensor solar, giroscopio, vibración), o null. */
    val gpio: Gpio?
    /** Sensor de inclinación (Yoshi Topsy-Turvy, Koro Koro Puzzle), o null. */
    val tilt: TiltSensor?

    var ie = 0
    var irqFlags = 0
    var ime = false
    private var keys = 0x3FF
    private var dispstat = 0
    private var vcount = 0
    private var cycleDebt = 0
    var frameCount = 0L
        private set

    val frame: IntArray get() = ppu.frame
    val gameTitle: String = String(rom, 0xA0, 12, Charsets.US_ASCII).trim { it <= ' ' }
    val gameCode: String = String(rom, 0xAC, 4, Charsets.US_ASCII).trim { it <= ' ' }

    init {
        bus.loadRom(rom)
        backup = Backup.detect(rom)
        backup.load(save)
        bus.backup = backup
        gpio = CartHardware.gpio(gameCode, rom, sensors)
        tilt = CartHardware.tilt(gameCode, sensors)
        bus.gpio = gpio
        bus.tilt = tilt
        if (usingRealBios) {
            System.arraycopy(bios!!, 0, bus.bios, 0, 0x4000)
            cpu.setCpsr(Cpu.SVC or 0xC0)
            cpu.branchTo(0)
        } else {
            Hle.buildStubBios(bus.bios)
            cpu.hle = Hle(this)
            cpu.setCpsr(Cpu.SVC or 0xC0)
            cpu.r[13] = 0x03007FE0
            cpu.setCpsr(Cpu.IRQ or 0xC0)
            cpu.r[13] = 0x03007FA0
            cpu.setCpsr(Cpu.SYS)
            cpu.r[13] = 0x03007F00
            cpu.branchTo(0x08000000)
            io.w16(0x20, 0x100); io.w16(0x26, 0x100)
            io.w16(0x30, 0x100); io.w16(0x36, 0x100)
            io.w16(0x88, 0x200)
        }
    }

    /** Botones pulsados (máscara de [Button]). */
    fun setButtons(pressed: Int) {
        keys = pressed.inv() and 0x3FF
        checkKeypadIrq()
    }

    fun requestIrq(mask: Int) {
        irqFlags = irqFlags or mask
    }

    // ---------------------------------------------------------------- planificador

    fun runFrame() {
        for (line in 0 until 228) {
            sio.poll()
            vcount = line
            dispstat = if (line in 160..226) dispstat or 1 else dispstat and 1.inv()
            val lyc = dispstat ushr 8
            if (line == lyc) {
                dispstat = dispstat or 4
                if ((dispstat and 0x20) != 0) requestIrq(Irq.VCOUNT)
            } else dispstat = dispstat and 4.inv()
            if (line == 160) {
                if ((dispstat and 0x08) != 0) requestIrq(Irq.VBLANK)
                dmaAt(Dma.VBLANK)
                ppu.vblankReload()
            }
            run(960)
            if (line < 160) ppu.renderLine(line)
            dispstat = dispstat or 2
            if ((dispstat and 0x10) != 0) requestIrq(Irq.HBLANK)
            if (line < 160) dmaAt(Dma.HBLANK)
            if (line in 2..161) dmaAt(-line) // DMA3 de captura de vídeo
            run(272)
            dispstat = dispstat and 2.inv()
        }
        frameCount++
    }

    /**
     * Lanza los DMA de un momento de la línea. El DMA para la CPU, así que su tiempo
     * se descuenta del que le toca ejecutar a la CPU (y avanzan temporizadores y sonido).
     */
    private fun dmaAt(timing: Int) {
        bus.cycles = 0
        if (timing < 0) dma.videoCapture(-timing) else dma.trigger(timing)
        val c = bus.cycles
        if (c > 0) {
            bus.cycles = 0
            timers.tick(c)
            apu.tick(c)
            cycleDebt -= c
        }
    }

    private fun run(cycles: Int) {
        var remain = cycles + cycleDebt
        val cpu = cpu
        while (remain > 0) {
            if (cpu.halted) {
                if ((ie and irqFlags) != 0) cpu.halted = false
                else {
                    val step = minOf(remain, timers.cyclesToEvent())
                    timers.tick(step)
                    apu.tick(step)
                    remain -= step
                    continue
                }
            }
            if (ime && !cpu.irqDisable && (ie and irqFlags) != 0) cpu.irq()
            val c = cpu.step()
            timers.tick(c)
            apu.tick(c)
            remain -= c
        }
        cycleDebt = remain
    }

    private fun checkKeypadIrq() {
        val cnt = io.u16(0x132)
        if ((cnt and 0x4000) == 0) return
        val sel = cnt and 0x3FF
        val pressed = keys.inv() and 0x3FF
        val hit = if ((cnt and 0x8000) != 0) (pressed and sel) == sel && sel != 0 else (pressed and sel) != 0
        if (hit) requestIrq(Irq.KEYPAD)
    }

    // ---------------------------------------------------------------- registros de E/S

    fun ioRead16(addr: Int): Int {
        val off = addr and 0xFFFFFE
        if (off >= 0x400) return 0
        return when (off) {
            0x004 -> dispstat
            0x006 -> vcount
            in 0x010..0x046, 0x04C, 0x04E, 0x054, 0x056 -> 0
            in 0x060..0x0AE -> apu.read16(off)
            0x0BA -> dma.readControl(0); 0x0C6 -> dma.readControl(1)
            0x0D2 -> dma.readControl(2); 0x0DE -> dma.readControl(3)
            in 0x0B0..0x0DE -> 0
            in 0x100..0x10E -> timers.read(off)
            0x128 -> sio.read(off)
            0x130 -> keys
            0x200 -> ie
            0x202 -> irqFlags
            0x208 -> if (ime) 1 else 0
            else -> io.u16(off)
        }
    }

    fun ioWrite16(addr: Int, value: Int) {
        val off = addr and 0xFFFFFE
        if (off >= 0x400) return
        when (off) {
            0x004 -> { dispstat = (dispstat and 7) or (value and 0xFFF8); io.w16(off, dispstat) }
            0x006, 0x130 -> {}
            in 0x028..0x02E, in 0x038..0x03E -> { io.w16(off, value); ppu.latchRef(off) }
            in 0x060..0x0AE -> apu.write16(off, value)
            0x0BA -> { io.w16(off, value); dma.writeControl(0, value) }
            0x0C6 -> { io.w16(off, value); dma.writeControl(1, value) }
            0x0D2 -> { io.w16(off, value); dma.writeControl(2, value) }
            0x0DE -> { io.w16(off, value); dma.writeControl(3, value) }
            in 0x100..0x10E -> { io.w16(off, value); timers.write(off, value) }
            0x128 -> sio.writeCnt(value)
            0x132 -> { io.w16(off, value); checkKeypadIrq() }
            0x200 -> { ie = value and 0x3FFF; io.w16(off, ie) }
            0x202 -> irqFlags = irqFlags and value.inv()
            0x204 -> { io.w16(off, value); bus.updateWaitstates(value) }
            0x208 -> { ime = (value and 1) != 0; io.w16(off, value and 1) }
            0x300 -> { io[0x300] = value.toByte(); cpu.halted = true } // HALTCNT en el byte alto
            else -> io.w16(off, value)
        }
    }

    fun ioWrite8(addr: Int, value: Int) {
        val off = addr and 0xFFFFFF
        if (off >= 0x400) return
        when (off) {
            0x300 -> io[0x300] = value.toByte()
            0x301 -> cpu.halted = true
            0x202 -> irqFlags = irqFlags and value.inv()
            0x203 -> irqFlags = irqFlags and (value shl 8).inv()
            in 0x0A0..0x0A7 -> apu.fifoPush(off, value)
            else -> {
                val al = off and 1.inv()
                val sh = (off and 1) shl 3
                val cur = when (al) {
                    0x004 -> dispstat; 0x200 -> ie; 0x208 -> if (ime) 1 else 0
                    in 0x090..0x09F -> apu.read16(al)
                    else -> io.u16(al)
                }
                ioWrite16(al, (cur and (0xFF shl sh).inv()) or (value shl sh))
            }
        }
    }

    fun ioWrite32(addr: Int, value: Int) {
        ioWrite16(addr, value and 0xFFFF)
        ioWrite16(addr + 2, value ushr 16)
    }

    // ---------------------------------------------------------------- estados guardados

    /**
     * Serializa toda la máquina. Llamar entre frames (no durante [runFrame]).
     * Con [fast] comprime menos pero mucho más rápido (para el rebobinado).
     */
    fun saveState(fast: Boolean = false): ByteArray {
        val bos = java.io.ByteArrayOutputStream(if (fast) 0x20000 else 0x40000)
        val deflater = java.util.zip.Deflater(if (fast) 1 else 6)
        try {
          DataOutputStream(java.io.BufferedOutputStream(java.util.zip.DeflaterOutputStream(bos, deflater, 0x8000), 0x8000)).use { o ->
            o.writeInt(STATE_MAGIC); o.writeInt(STATE_VERSION); o.writeUTF(gameCode)
            o.bytes(io); o.writeInt(ie); o.writeInt(irqFlags); o.writeBoolean(ime)
            o.writeInt(dispstat); o.writeInt(vcount); o.writeInt(cycleDebt)
            cpu.save(o); bus.save(o); ppu.save(o); apu.save(o); dma.save(o); timers.save(o)
            backup.saveState(o)
            o.writeBoolean(cpu.hle != null); cpu.hle?.save(o)
            o.writeBoolean(gpio != null); gpio?.save(o)
            o.writeBoolean(tilt != null); tilt?.save(o)
          }
        } finally {
            deflater.end()
        }
        return bos.toByteArray()
    }

    /** Restaura un estado creado con [saveState]. Lanza excepción si no es de este juego. */
    fun loadState(data: ByteArray) {
        val inflater = java.util.zip.Inflater()
        try {
          DataInputStream(java.io.BufferedInputStream(java.util.zip.InflaterInputStream(data.inputStream(), inflater, 0x8000), 0x8000)).use { i ->
            require(i.readInt() == STATE_MAGIC) { "no es un estado de KGBA" }
            val version = i.readInt()
            require(version in 1..STATE_VERSION) { "versión de estado distinta" }
            require(i.readUTF() == gameCode) { "el estado es de otro juego" }
            i.bytes(io); ie = i.readInt(); irqFlags = i.readInt(); ime = i.readBoolean()
            dispstat = i.readInt(); vcount = i.readInt(); cycleDebt = i.readInt()
            cpu.load(i); bus.load(i); ppu.load(i); apu.load(i); dma.load(i); timers.load(i)
            backup.loadState(i)
            backup.dirty = true
            if (i.readBoolean()) cpu.hle?.load(i)
            if (version >= 2) {
                if (i.readBoolean()) gpio?.load(i)
                if (i.readBoolean()) tilt?.load(i)
            }
          }
        } finally {
            inflater.end()
        }
    }
}
