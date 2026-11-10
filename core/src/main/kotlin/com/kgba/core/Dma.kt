package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

class Dma(private val gba: Gba) {
    companion object {
        const val IMMEDIATE = 0; const val VBLANK = 1; const val HBLANK = 2; const val SPECIAL = 3
    }

    private val src = IntArray(4)
    private val dst = IntArray(4)
    private val count = IntArray(4)
    private val control = IntArray(4)
    private var latch = 0
    private var running = false

    private fun base(ch: Int) = 0xB0 + ch * 12

    fun readControl(ch: Int) = control[ch]

    fun writeControl(ch: Int, value: Int) {
        val wasOn = (control[ch] and 0x8000) != 0
        control[ch] = value
        if (!wasOn && (value and 0x8000) != 0) {
            val io = gba.io
            val b = base(ch)
            src[ch] = io.u32(b) and (if (ch == 0) 0x07FFFFFF else 0x0FFFFFFF)
            dst[ch] = io.u32(b + 4) and (if (ch == 3) 0x0FFFFFFF else 0x07FFFFFF)
            loadCount(ch)
            if (((value ushr 12) and 3) == IMMEDIATE) run(ch)
        }
    }

    private fun loadCount(ch: Int) {
        val raw = gba.io.u16(base(ch) + 8)
        count[ch] = if (ch == 3) (if (raw == 0) 0x10000 else raw)
        else (if ((raw and 0x3FFF) == 0) 0x4000 else raw and 0x3FFF)
    }

    fun trigger(timing: Int) {
        for (ch in 0..3) {
            val cnt = control[ch]
            if ((cnt and 0x8000) != 0 && ((cnt ushr 12) and 3) == timing) run(ch)
        }
    }

    /** DMA3 en modo especial: captura de vídeo, una transferencia por línea de la 2 a la 161. */
    fun videoCapture(line: Int) {
        val cnt = control[3]
        if ((cnt and 0x8000) == 0 || ((cnt ushr 12) and 3) != SPECIAL) return
        run(3)
        if (line == 161 && (control[3] and 0x8000) != 0) {
            control[3] = control[3] and 0x8000.inv()
            gba.io.w16(base(3) + 10, control[3])
        }
    }

    /** Petición de la FIFO de sonido A (0) o B (1). */
    fun fifoRequest(fifo: Int) {
        val target = 0x040000A0 + fifo * 4
        for (ch in 1..2) {
            val cnt = control[ch]
            if ((cnt and 0x8000) != 0 && ((cnt ushr 12) and 3) == SPECIAL && dst[ch] == target) {
                run(ch, fifoMode = true)
                return
            }
        }
    }

    private fun run(ch: Int, fifoMode: Boolean = false) {
        if (running && !fifoMode) return
        val bus = gba.bus
        val cnt = control[ch]
        val word = fifoMode || (cnt and 0x400) != 0
        val step = if (word) 4 else 2
        val dstCtl = if (fifoMode) 2 else (cnt ushr 5) and 3
        val srcCtl = (cnt ushr 7) and 3
        val units = if (fifoMode) 4 else count[ch]
        val dStep = when (dstCtl) { 1 -> -step; 2 -> 0; else -> step }
        var sStep = when (srcCtl) { 1 -> -step; 2 -> 0; else -> step }
        var s = src[ch]
        var d = dst[ch]
        if ((s ushr 24) in 0x08..0x0D && sStep < 0) sStep = step
        if (ch == 3) {
            val bk = bus.backup
            if (bk is Backup.Eeprom && (bus.isEeprom(d) || bus.isEeprom(s))) bk.detectFromDma(units)
        }
        val wasRunning = running
        running = true
        if (word) {
            for (k in 0 until units) {
                if (s >= 0x02000000) latch = bus.read32(s)
                bus.write32(d, latch)
                s += sStep; d += dStep
            }
        } else {
            for (k in 0 until units) {
                if (s >= 0x02000000) {
                    val x = bus.read16(s)
                    latch = x or (x shl 16)
                }
                bus.write16(d, latch and 0xFFFF)
                s += sStep; d += dStep
            }
        }
        running = wasRunning
        src[ch] = s
        if (!fifoMode) dst[ch] = d
        if ((cnt and 0x4000) != 0) gba.requestIrq(Irq.DMA0 shl ch)
        val timing = (cnt ushr 12) and 3
        if ((cnt and 0x200) != 0 && timing != IMMEDIATE) {
            if (!fifoMode) loadCount(ch)
            if (dstCtl == 3) dst[ch] = gba.io.u32(base(ch) + 4) and (if (ch == 3) 0x0FFFFFFF else 0x07FFFFFF)
        } else {
            control[ch] = cnt and 0x8000.inv()
            gba.io.w16(base(ch) + 10, control[ch])
        }
    }

    internal fun save(o: DataOutputStream) {
        o.ints(src); o.ints(dst); o.ints(count); o.ints(control); o.writeInt(latch)
    }

    internal fun load(i: DataInputStream) {
        i.ints(src); i.ints(dst); i.ints(count); i.ints(control); latch = i.readInt()
    }
}
