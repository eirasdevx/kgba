package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BIOS de alto nivel: implementa en Kotlin las llamadas SWI de la BIOS de la GBA,
 * para poder jugar sin el archivo de BIOS original.
 */
class Hle(private val gba: Gba) {
    private val cpu get() = gba.cpu
    private val bus get() = gba.bus
    private var waiting = false

    companion object {
        /** Mini-BIOS con los vectores y el manejador de IRQ real (mismos opcodes que la BIOS). */
        fun buildStubBios(bios: ByteArray) {
            java.util.Arrays.fill(bios, 0)
            fun put(addr: Int, op: Long) = bios.w32(addr, op.toInt())
            put(0x00, 0xEA00001E) // b 0x80 (no se usa)
            put(0x04, 0xE1B0F00E) // undefined: movs pc, lr
            put(0x08, 0xE1B0F00E) // swi: movs pc, lr
            put(0x0C, 0xE25EF004) // prefetch abort
            put(0x10, 0xE25EF008) // data abort
            put(0x18, 0xEA000042) // irq: b 0x128
            put(0x1C, 0xE25EF004) // fiq
            put(0x80, 0xEAFFFFFE) // bucle
            put(0x128, 0xE92D500F) // stmfd sp!, {r0-r3, r12, lr}
            put(0x12C, 0xE3A00301) // mov r0, #0x04000000
            put(0x130, 0xE28FE000) // add lr, pc, #0
            put(0x134, 0xE510F004) // ldr pc, [r0, #-4]
            put(0x138, 0xE8BD500F) // ldmfd sp!, {r0-r3, r12, lr}
            put(0x13C, 0xE25EF004) // subs pc, lr, #4
            put(0x140, 0xE92D5800) // (inicio del manejador SWI real, solo para el open bus)
            put(0x144, 0xE55EC002)
        }

        private val SIN = IntArray(256) { (sin(it * Math.PI * 2 / 256) * 16384).roundToInt() }
    }

    fun swi(num: Int) {
        bus.biosLatch = 0xE3A02004.toInt() // lo que se leería de la BIOS tras volver de una SWI
        val r = cpu.r
        when (num) {
            0x00 -> softReset()
            0x01 -> registerRamReset(r[0])
            0x02, 0x03 -> cpu.halted = true
            0x04 -> intrWait(r[0] != 0, r[1])
            0x05 -> intrWait(true, Irq.VBLANK)
            0x06 -> div(r[0], r[1])
            0x07 -> div(r[1], r[0])
            0x08 -> r[0] = sqrt((r[0].toLong() and 0xFFFFFFFFL).toDouble()).toInt()
            0x09 -> arcTan()
            0x0A -> {
                val x = r[0].toShort().toDouble(); val y = r[1].toShort().toDouble()
                var a = atan2(y, x) / (2 * Math.PI)
                if (a < 0) a += 1.0
                r[0] = (a * 0x10000).toInt() and 0xFFFF
            }
            0x0B -> cpuSet(r[0], r[1], r[2])
            0x0C -> cpuFastSet(r[0], r[1], r[2])
            0x0D -> r[0] = 0xBAAE187F.toInt()
            0x0E -> bgAffineSet(r[0], r[1], r[2])
            0x0F -> objAffineSet(r[0], r[1], r[2], r[3])
            0x10 -> bitUnPack(r[0], r[1], r[2])
            0x11 -> write(r[1], lz77(r[0]), false)
            0x12 -> write(r[1], lz77(r[0]), true)
            0x13 -> huffman(r[0], r[1])
            0x14 -> write(r[1], rle(r[0]), false)
            0x15 -> write(r[1], rle(r[0]), true)
            0x16 -> diff8(r[0], r[1], false)
            0x17 -> diff8(r[0], r[1], true)
            0x18 -> diff16(r[0], r[1])
            0x19 -> bus.write16(0x04000088, if (r[0] != 0) 0x200 else 0)
            0x1F -> {
                val freq = (bus.read32(r[0] + 4).toLong() and 0xFFFFFFFFL).toDouble()
                val e = (180.0 - r[1] - r[2] / 256.0) / 12.0
                r[0] = (freq / 2.0.pow(e)).toLong().toInt()
            }
            0x25 -> r[0] = 1 // MultiBoot: fallo
            else -> {} // drivers de sonido de la BIOS, etc.: no-op
        }
    }

    private fun intrWait(discardRequested: Boolean, mask: Int) {
        gba.ime = true
        val discard = discardRequested && !waiting
        var flags = bus.iwram.u16(0x7FF8)
        if (discard) {
            flags = flags and mask.inv()
            bus.iwram.w16(0x7FF8, flags)
        } else if ((flags and mask) != 0) {
            bus.iwram.w16(0x7FF8, flags and mask.inv())
            waiting = false
            return
        }
        waiting = true
        cpu.halted = true
        cpu.rewind()
    }

    private fun div(num: Int, den: Int) {
        val r = cpu.r
        if (den == 0) {
            r[0] = if (num < 0) -1 else 1; r[1] = num; r[3] = 1
            return
        }
        val q = num / den
        r[0] = q; r[1] = num % den; r[3] = if (q < 0) -q else q
    }

    private fun arcTan() {
        val r = cpu.r
        val x = r[0]
        val a = -((x * x) shr 14)
        var b = ((0xA9 * a) shr 14) + 0x390
        b = ((b * a) shr 14) + 0x91C
        b = ((b * a) shr 14) + 0xFB6
        b = ((b * a) shr 14) + 0x16AA
        b = ((b * a) shr 14) + 0x2081
        b = ((b * a) shr 14) + 0x3651
        b = ((b * a) shr 14) + 0xA2F9
        r[0] = (x * b) shr 16
    }

    private fun softReset() {
        val flag = bus.iwram.u8(0x7FFA)
        java.util.Arrays.fill(bus.iwram, 0x7E00, 0x8000, 0)
        cpu.setBankedSp(Cpu.SVC, 0x03007FE0)
        cpu.setBankedSp(Cpu.IRQ, 0x03007FA0)
        cpu.setCpsr(Cpu.SYS)
        cpu.r[13] = 0x03007F00
        for (i in 0..12) cpu.r[i] = 0
        cpu.r[14] = 0
        cpu.branchTo(if (flag != 0) 0x02000000 else 0x08000000)
    }

    private fun registerRamReset(flags: Int) {
        if ((flags and 1) != 0) java.util.Arrays.fill(bus.ewram, 0)
        if ((flags and 2) != 0) java.util.Arrays.fill(bus.iwram, 0, 0x7E00, 0)
        if ((flags and 4) != 0) java.util.Arrays.fill(bus.pram, 0)
        if ((flags and 8) != 0) java.util.Arrays.fill(bus.vram, 0)
        if ((flags and 16) != 0) java.util.Arrays.fill(bus.oam, 0)
        bus.write16(0x04000000, 0x80)
    }

    private fun cpuSet(src0: Int, dst0: Int, cnt: Int) {
        var src = src0; var dst = dst0
        val count = cnt and 0x1FFFFF
        val fill = (cnt and (1 shl 24)) != 0
        if ((cnt and (1 shl 26)) != 0) {
            src = src and 3.inv(); dst = dst and 3.inv()
            val v = bus.read32(src)
            for (i in 0 until count) {
                bus.write32(dst, if (fill) v else bus.read32(src))
                dst += 4; if (!fill) src += 4
            }
        } else {
            src = src and 1.inv(); dst = dst and 1.inv()
            val v = bus.read16(src)
            for (i in 0 until count) {
                bus.write16(dst, if (fill) v else bus.read16(src))
                dst += 2; if (!fill) src += 2
            }
        }
    }

    private fun cpuFastSet(src0: Int, dst0: Int, cnt: Int) {
        var src = src0 and 3.inv(); var dst = dst0 and 3.inv()
        val count = ((cnt and 0x1FFFFF) + 7) and 7.inv()
        val fill = (cnt and (1 shl 24)) != 0
        val v = bus.read32(src)
        for (i in 0 until count) {
            bus.write32(dst, if (fill) v else bus.read32(src))
            dst += 4; if (!fill) src += 4
        }
    }

    private fun bgAffineSet(src0: Int, dst0: Int, n: Int) {
        var src = src0; var dst = dst0
        for (i in 0 until n) {
            val ox = bus.read32(src); val oy = bus.read32(src + 4)
            val cx = bus.read16(src + 8).toShort().toInt(); val cy = bus.read16(src + 10).toShort().toInt()
            val sx = bus.read16(src + 12).toShort().toInt(); val sy = bus.read16(src + 14).toShort().toInt()
            val theta = (bus.read16(src + 16) ushr 8) and 0xFF
            src += 20
            val sinA = SIN[theta]; val cosA = SIN[(theta + 64) and 0xFF]
            val pa = (sx * cosA) shr 14; val pb = -((sx * sinA) shr 14)
            val pc = (sy * sinA) shr 14; val pd = (sy * cosA) shr 14
            bus.write16(dst, pa); bus.write16(dst + 2, pb); bus.write16(dst + 4, pc); bus.write16(dst + 6, pd)
            bus.write32(dst + 8, ox - (pa * cx + pb * cy))
            bus.write32(dst + 12, oy - (pc * cx + pd * cy))
            dst += 16
        }
    }

    private fun objAffineSet(src0: Int, dst0: Int, n: Int, stride: Int) {
        var src = src0; var dst = dst0
        for (i in 0 until n) {
            val sx = bus.read16(src).toShort().toInt(); val sy = bus.read16(src + 2).toShort().toInt()
            val theta = (bus.read16(src + 4) ushr 8) and 0xFF
            src += 8
            val sinA = SIN[theta]; val cosA = SIN[(theta + 64) and 0xFF]
            bus.write16(dst, (sx * cosA) shr 14)
            bus.write16(dst + stride, -((sx * sinA) shr 14))
            bus.write16(dst + stride * 2, (sy * sinA) shr 14)
            bus.write16(dst + stride * 3, (sy * cosA) shr 14)
            dst += stride * 4
        }
    }

    private fun bitUnPack(src: Int, dst0: Int, info: Int) {
        var dst = dst0
        val len = bus.read16(info)
        val srcW = bus.read8(info + 2)
        val dstW = bus.read8(info + 3)
        val raw = bus.read32(info + 4)
        val offset = raw and 0x7FFFFFFF
        val zero = raw < 0
        if (srcW !in intArrayOf(1, 2, 4, 8) || dstW !in intArrayOf(1, 2, 4, 8, 16, 32)) return
        var out = 0; var outBits = 0
        val srcMask = (1 shl srcW) - 1
        for (i in 0 until len) {
            val b = bus.read8(src + i)
            var j = 0
            while (j < 8) {
                var v = (b ushr j) and srcMask
                if (v != 0 || zero) v += offset
                out = out or (v shl outBits)
                outBits += dstW
                if (outBits >= 32) { bus.write32(dst, out); dst += 4; out = 0; outBits = 0 }
                j += srcW
            }
        }
    }

    private fun lz77(src0: Int): ByteArray {
        var src = src0
        val size = bus.read32(src) ushr 8
        src += 4
        val out = ByteArray(size)
        var o = 0
        while (o < size) {
            val flags = bus.read8(src++)
            for (bit in 7 downTo 0) {
                if (o >= size) break
                if (((flags ushr bit) and 1) != 0) {
                    val b1 = bus.read8(src); val b2 = bus.read8(src + 1); src += 2
                    val len = (b1 ushr 4) + 3
                    val disp = (((b1 and 0xF) shl 8) or b2) + 1
                    for (k in 0 until len) {
                        if (o >= size) break
                        out[o] = if (o - disp >= 0) out[o - disp] else 0
                        o++
                    }
                } else out[o++] = bus.read8(src++).toByte()
            }
        }
        return out
    }

    private fun rle(src0: Int): ByteArray {
        var src = src0
        val size = bus.read32(src) ushr 8
        src += 4
        val out = ByteArray(size)
        var o = 0
        while (o < size) {
            val flag = bus.read8(src++)
            if ((flag and 0x80) != 0) {
                val len = (flag and 0x7F) + 3
                val b = bus.read8(src++).toByte()
                for (k in 0 until len) { if (o >= size) break; out[o++] = b }
            } else {
                val len = (flag and 0x7F) + 1
                for (k in 0 until len) { if (o >= size) break; out[o++] = bus.read8(src++).toByte() }
            }
        }
        return out
    }

    private fun write(dst: Int, data: ByteArray, vram: Boolean) {
        if (vram) {
            var i = 0
            while (i < data.size) {
                val lo = data[i].toInt() and 0xFF
                val hi = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
                bus.write16(dst + i, lo or (hi shl 8))
                i += 2
            }
        } else for (i in data.indices) bus.write8(dst + i, data[i].toInt() and 0xFF)
    }

    private fun huffman(src: Int, dst0: Int) {
        var dst = dst0
        val header = bus.read32(src)
        val bits = header and 0xF
        val size = header ushr 8
        val treeStart = src + 5
        var dataPtr = src + 4 + ((bus.read8(src + 4) + 1) shl 1)
        var outWord = 0; var outBits = 0; var written = 0
        var node = treeStart
        var nodeVal = bus.read8(node)
        val dataMask = (1 shl bits) - 1
        while (written < size) {
            val word = bus.read32(dataPtr); dataPtr += 4
            for (bit in 31 downTo 0) {
                val dir = (word ushr bit) and 1
                val child = (node and 1.inv()) + (nodeVal and 0x3F) * 2 + 2 + dir
                val isData = if (dir == 0) (nodeVal and 0x80) != 0 else (nodeVal and 0x40) != 0
                if (isData) {
                    outWord = outWord or ((bus.read8(child) and dataMask) shl outBits)
                    outBits += bits
                    if (outBits >= 32) {
                        bus.write32(dst, outWord); dst += 4; written += 4
                        outWord = 0; outBits = 0
                        if (written >= size) break
                    }
                    node = treeStart
                } else node = child
                nodeVal = bus.read8(node)
            }
        }
    }

    private fun diff8(src: Int, dst: Int, vram: Boolean) {
        val size = bus.read32(src) ushr 8
        val out = ByteArray(size)
        var acc = 0
        for (i in 0 until size) { acc = (acc + bus.read8(src + 4 + i)) and 0xFF; out[i] = acc.toByte() }
        write(dst, out, vram)
    }

    private fun diff16(src: Int, dst: Int) {
        val size = bus.read32(src) ushr 8
        var acc = 0
        var i = 0
        while (i < size) {
            acc = (acc + bus.read16(src + 4 + i)) and 0xFFFF
            bus.write16(dst + i, acc)
            i += 2
        }
    }

    internal fun save(o: DataOutputStream) = o.writeBoolean(waiting)
    internal fun load(i: DataInputStream) { waiting = i.readBoolean() }
}
