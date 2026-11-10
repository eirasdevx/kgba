package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

class Timers(private val gba: Gba) {
    private val counter = IntArray(4)
    private val reload = IntArray(4)
    private val control = IntArray(4)
    private val pre = IntArray(4)
    private val shift = IntArray(4)
    private var activeMask = 0 // temporizadores activos que no están en cascada

    private val prescaleShift = intArrayOf(0, 6, 8, 10)

    fun read(off: Int): Int {
        val i = (off - 0x100) shr 2
        return if ((off and 2) == 0) counter[i] else control[i]
    }

    fun write(off: Int, value: Int) {
        val i = (off - 0x100) shr 2
        if ((off and 2) == 0) {
            reload[i] = value and 0xFFFF
        } else {
            val wasOn = (control[i] and 0x80) != 0
            control[i] = value and 0xC7
            shift[i] = prescaleShift[value and 3]
            if (!wasOn && (value and 0x80) != 0) {
                counter[i] = reload[i]
                pre[i] = 0
            }
            updateMask()
        }
    }

    private fun updateMask() {
        activeMask = 0
        for (i in 0..3) {
            val cnt = control[i]
            if ((cnt and 0x80) != 0 && (i == 0 || (cnt and 0x04) == 0)) activeMask = activeMask or (1 shl i)
        }
    }

    fun tick(cycles: Int) {
        if (activeMask == 0) return
        for (i in 0..3) {
            if ((activeMask and (1 shl i)) == 0) continue
            val p = pre[i] + cycles
            val sh = shift[i]
            val ticks = p ushr sh
            pre[i] = p - (ticks shl sh)
            if (ticks > 0) addTicks(i, ticks)
        }
    }

    private fun addTicks(i: Int, ticks: Int) {
        var cnt = counter[i] + ticks
        while (cnt >= 0x10000) {
            cnt = cnt - 0x10000 + reload[i]
            overflow(i)
        }
        counter[i] = cnt
    }

    private fun overflow(i: Int) {
        if ((control[i] and 0x40) != 0) gba.requestIrq(Irq.TIMER0 shl i)
        if (i < 2) gba.apu.timerOverflow(i)
        if (i < 3) {
            val next = control[i + 1]
            if ((next and 0x84) == 0x84) addTicks(i + 1, 1)
        }
    }

    /** Ciclos que faltan para el próximo desbordamiento (para avanzar rápido en HALT). */
    fun cyclesToEvent(): Int {
        var best = Int.MAX_VALUE
        for (i in 0..3) {
            if ((activeMask and (1 shl i)) == 0) continue
            val left = ((0x10000 - counter[i]).toLong() shl shift[i]) - pre[i]
            if (left < best) best = maxOf(1L, left).toInt()
        }
        return best
    }

    internal fun save(o: DataOutputStream) {
        o.ints(counter); o.ints(reload); o.ints(control); o.ints(pre)
    }

    internal fun load(i: DataInputStream) {
        i.ints(counter); i.ints(reload); i.ints(control); i.ints(pre)
        for (k in 0..3) shift[k] = prescaleShift[control[k] and 3]
        updateMask()
    }
}
