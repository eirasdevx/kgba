package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Intérprete del ARM7TDMI (ARMv4T): juegos de instrucciones ARM (32 bits) y Thumb (16 bits).
 *
 * Modelo del pipeline: mientras se ejecuta una instrucción, r[15] vale la dirección de esa
 * instrucción + 8 (ARM) o + 4 (Thumb), igual que en el hardware real.
 */
class Cpu(private val bus: Bus) {
    companion object {
        const val USR = 0x10; const val FIQ = 0x11; const val IRQ = 0x12; const val SVC = 0x13
        const val ABT = 0x17; const val UND = 0x1B; const val SYS = 0x1F

        private const val A_UND = 0; private const val A_DP_REG = 1; private const val A_DP_IMM = 2
        private const val A_MUL = 3; private const val A_MULL = 4; private const val A_SWP = 5
        private const val A_HALF = 6; private const val A_BX = 7; private const val A_MRS = 8
        private const val A_MSR_REG = 9; private const val A_MSR_IMM = 10; private const val A_SDT = 11
        private const val A_SDT_REG = 12; private const val A_BLOCK = 13; private const val A_B = 14
        private const val A_SWI = 15

        private fun armClass(k: Int): Int {
            val hi = k ushr 4
            val lo = k and 0xF
            return when (hi ushr 5) {
                0 -> when {
                    lo == 9 -> when {
                        (hi and 0xFC) == 0x00 -> A_MUL
                        (hi and 0xF8) == 0x08 -> A_MULL
                        (hi and 0xFB) == 0x10 -> A_SWP
                        else -> A_UND
                    }
                    (lo and 9) == 9 -> A_HALF
                    hi == 0x12 && lo == 1 -> A_BX
                    (hi and 0x19) == 0x10 -> if ((hi and 2) != 0) A_MSR_REG else A_MRS
                    else -> A_DP_REG
                }
                1 -> if ((hi and 0x19) == 0x10) { if ((hi and 2) != 0) A_MSR_IMM else A_UND } else A_DP_IMM
                2 -> A_SDT
                3 -> if ((lo and 1) != 0) A_UND else A_SDT_REG
                4 -> A_BLOCK
                5 -> A_B
                6 -> A_UND
                else -> if ((hi and 0x10) != 0) A_SWI else A_UND
            }
        }

        private fun thumbClass(k: Int): Int {
            val i = k shl 6
            return when {
                (i and 0xF800) == 0x1800 -> 2
                (i and 0xE000) == 0x0000 -> 1
                (i and 0xE000) == 0x2000 -> 3
                (i and 0xFC00) == 0x4000 -> 4
                (i and 0xFC00) == 0x4400 -> 5
                (i and 0xF800) == 0x4800 -> 6
                (i and 0xF200) == 0x5000 -> 7
                (i and 0xF200) == 0x5200 -> 8
                (i and 0xE000) == 0x6000 -> 9
                (i and 0xF000) == 0x8000 -> 10
                (i and 0xF000) == 0x9000 -> 11
                (i and 0xF000) == 0xA000 -> 12
                (i and 0xFF00) == 0xB000 -> 13
                (i and 0xF600) == 0xB400 -> 14
                (i and 0xF000) == 0xC000 -> 15
                (i and 0xFF00) == 0xDF00 -> 17
                (i and 0xF000) == 0xD000 -> 16
                (i and 0xF800) == 0xE000 -> 18
                (i and 0xF000) == 0xF000 -> 19
                else -> 0
            }
        }

        private val armTable = ByteArray(4096) { armClass(it).toByte() }
        private val thumbTable = ByteArray(1024) { thumbClass(it).toByte() }

        fun bankOf(m: Int): Int = when (m) {
            FIQ -> 1; IRQ -> 2; SVC -> 3; ABT -> 4; UND -> 5; else -> 0
        }
    }

    val r = IntArray(16)
    var n = false; var z = false; var c = false; var v = false
    var irqDisable = true; var fiqDisable = true; var thumb = false
    var mode = SVC
    private val bankSp = IntArray(6)
    private val bankLr = IntArray(6)
    val spsr = IntArray(6)
    private val usrHi = IntArray(5)
    private val fiqHi = IntArray(5)

    var halted = false
    /** Si no es null, las SWI se resuelven en Kotlin (BIOS de alto nivel). */
    var hle: Hle? = null
    /** Dirección de la instrucción que se está ejecutando. */
    var curPc = 0
        private set

    private var branched = false
    private var shCarry = false
    private var internal = 0

    // ---------------------------------------------------------------- estado / modos

    fun getCpsr(): Int {
        var x = mode
        if (n) x = x or (1 shl 31)
        if (z) x = x or (1 shl 30)
        if (c) x = x or (1 shl 29)
        if (v) x = x or (1 shl 28)
        if (irqDisable) x = x or 0x80
        if (fiqDisable) x = x or 0x40
        if (thumb) x = x or 0x20
        return x
    }

    fun setCpsr(value: Int) {
        n = value < 0
        z = (value and 0x40000000) != 0
        c = (value and 0x20000000) != 0
        v = (value and 0x10000000) != 0
        irqDisable = (value and 0x80) != 0
        fiqDisable = (value and 0x40) != 0
        thumb = (value and 0x20) != 0
        switchMode(value and 0x1F)
    }

    fun switchMode(newMode: Int) {
        val nm = newMode or 0x10
        val ob = bankOf(mode)
        val nb = bankOf(nm)
        if (ob != nb) {
            bankSp[ob] = r[13]; bankLr[ob] = r[14]
            if (ob == 1) for (i in 0..4) { fiqHi[i] = r[8 + i]; r[8 + i] = usrHi[i] }
            if (nb == 1) for (i in 0..4) { usrHi[i] = r[8 + i]; r[8 + i] = fiqHi[i] }
            r[13] = bankSp[nb]; r[14] = bankLr[nb]
        }
        mode = nm
    }

    /** Fija el SP de un modo concreto (se usa al arrancar sin BIOS). */
    fun setBankedSp(m: Int, sp: Int) {
        if (bankOf(m) == bankOf(mode)) r[13] = sp else bankSp[bankOf(m)] = sp
    }

    private fun restoreCpsr() {
        val b = bankOf(mode)
        if (b != 0) setCpsr(spsr[b])
    }

    private fun getUserReg(i: Int): Int = when {
        i in 8..12 && mode == FIQ -> usrHi[i - 8]
        i == 13 && bankOf(mode) != 0 -> bankSp[0]
        i == 14 && bankOf(mode) != 0 -> bankLr[0]
        else -> r[i]
    }

    private fun setUserReg(i: Int, value: Int) {
        when {
            i in 8..12 && mode == FIQ -> usrHi[i - 8] = value
            i == 13 && bankOf(mode) != 0 -> bankSp[0] = value
            i == 14 && bankOf(mode) != 0 -> bankLr[0] = value
            else -> r[i] = value
        }
    }

    fun branchTo(addr: Int) {
        r[15] = if (thumb) (addr and 1.inv()) + 4 else (addr and 3.inv()) + 8
        branched = true
        needRefill = true
    }

    private var needRefill = true
    private var pipe0 = 0
    private var pipe1 = 0

    private fun refill() {
        needRefill = false
        if (thumb) {
            pipe0 = bus.fetch16(r[15] - 4); pipe1 = bus.fetch16(r[15] - 2)
        } else {
            pipe0 = bus.fetch32(r[15] - 8); pipe1 = bus.fetch32(r[15] - 4)
        }
    }

    /** Vuelve a ejecutar la instrucción actual (lo usa IntrWait en la BIOS HLE). */
    fun rewind() = branchTo(curPc)

    private fun setReg(rd: Int, value: Int) {
        if (rd == 15) branchTo(value) else r[rd] = value
    }

    private fun exception(newMode: Int, vector: Int, lr: Int) {
        val old = getCpsr()
        switchMode(newMode)
        spsr[bankOf(newMode)] = old
        r[14] = lr
        thumb = false
        irqDisable = true
        branchTo(vector)
    }

    fun irq() {
        halted = false
        exception(IRQ, 0x18, if (thumb) r[15] else r[15] - 4)
    }

    // ---------------------------------------------------------------- bucle

    fun step(): Int {
        bus.cycles = 0
        internal = 0
        branched = false
        if (needRefill) refill()
        if (thumb) {
            curPc = r[15] - 4
            val op = pipe0
            pipe0 = pipe1
            pipe1 = bus.fetch16(r[15])
            execThumb(op)
            if (!branched) r[15] += 2
        } else {
            curPc = r[15] - 8
            val op = pipe0
            pipe0 = pipe1
            pipe1 = bus.fetch32(r[15])
            if (cond(op ushr 28)) execArm(op)
            if (!branched) r[15] += 4
        }
        return bus.cycles + internal
    }

    private fun cond(cc: Int): Boolean = when (cc) {
        0 -> z; 1 -> !z; 2 -> c; 3 -> !c; 4 -> n; 5 -> !n; 6 -> v; 7 -> !v
        8 -> c && !z; 9 -> !c || z; 10 -> n == v; 11 -> n != v
        12 -> !z && n == v; 13 -> z || n != v; 14 -> true
        else -> false
    }

    // ---------------------------------------------------------------- ALU

    private fun add(a: Int, b: Int, s: Boolean): Int {
        val res = a + b
        if (s) {
            n = res < 0; z = res == 0
            c = Integer.compareUnsigned(res, a) < 0
            v = ((a xor res) and (b xor res)) < 0
        }
        return res
    }

    private fun sub(a: Int, b: Int, s: Boolean): Int {
        val res = a - b
        if (s) {
            n = res < 0; z = res == 0
            c = Integer.compareUnsigned(a, b) >= 0
            v = ((a xor b) and (a xor res)) < 0
        }
        return res
    }

    private fun adc(a: Int, b: Int, s: Boolean): Int {
        val wide = (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL) + (if (c) 1L else 0L)
        val res = wide.toInt()
        if (s) {
            n = res < 0; z = res == 0
            c = (wide ushr 32) != 0L
            v = ((a xor res) and (b xor res)) < 0
        }
        return res
    }

    private fun sbc(a: Int, b: Int, s: Boolean): Int {
        val wide = (a.toLong() and 0xFFFFFFFFL) - (b.toLong() and 0xFFFFFFFFL) - (if (c) 0L else 1L)
        val res = wide.toInt()
        if (s) {
            n = res < 0; z = res == 0
            c = wide >= 0
            v = ((a xor b) and (a xor res)) < 0
        }
        return res
    }

    private fun shiftImm(type: Int, x: Int, amt: Int): Int = when (type) {
        0 -> if (amt == 0) { shCarry = c; x } else { shCarry = ((x ushr (32 - amt)) and 1) != 0; x shl amt }
        1 -> if (amt == 0) { shCarry = x < 0; 0 } else { shCarry = ((x ushr (amt - 1)) and 1) != 0; x ushr amt }
        2 -> if (amt == 0) { shCarry = x < 0; x shr 31 } else { shCarry = ((x shr (amt - 1)) and 1) != 0; x shr amt }
        else -> if (amt == 0) {
            shCarry = (x and 1) != 0
            (x ushr 1) or (if (c) Int.MIN_VALUE else 0)
        } else {
            shCarry = ((x ushr (amt - 1)) and 1) != 0
            Integer.rotateRight(x, amt)
        }
    }

    private fun shiftReg(type: Int, x: Int, amt: Int): Int {
        if (amt == 0) { shCarry = c; return x }
        return when (type) {
            0 -> when {
                amt < 32 -> { shCarry = ((x ushr (32 - amt)) and 1) != 0; x shl amt }
                amt == 32 -> { shCarry = (x and 1) != 0; 0 }
                else -> { shCarry = false; 0 }
            }
            1 -> when {
                amt < 32 -> { shCarry = ((x ushr (amt - 1)) and 1) != 0; x ushr amt }
                amt == 32 -> { shCarry = x < 0; 0 }
                else -> { shCarry = false; 0 }
            }
            2 -> if (amt < 32) {
                shCarry = ((x shr (amt - 1)) and 1) != 0; x shr amt
            } else { shCarry = x < 0; x shr 31 }
            else -> {
                val a = amt and 31
                if (a == 0) { shCarry = x < 0; x } else {
                    shCarry = ((x ushr (a - 1)) and 1) != 0; Integer.rotateRight(x, a)
                }
            }
        }
    }

    private fun mulCycles(x: Int): Int {
        val y = if (x < 0) x.inv() else x
        return when {
            (y ushr 8) == 0 -> 1
            (y ushr 16) == 0 -> 2
            (y ushr 24) == 0 -> 3
            else -> 4
        }
    }

    // ---------------------------------------------------------------- ARM

    private fun execArm(op: Int) {
        when (armTable[((op ushr 16) and 0xFF0) or ((op ushr 4) and 0xF)].toInt()) {
            A_DP_REG -> dataProc(op, false)
            A_DP_IMM -> dataProc(op, true)
            A_MUL -> multiply(op)
            A_MULL -> multiplyLong(op)
            A_SWP -> swap(op)
            A_HALF -> halfTransfer(op)
            A_BX -> {
                val target = r[op and 0xF]
                thumb = (target and 1) != 0
                branchTo(target)
            }
            A_MRS -> {
                val rd = (op ushr 12) and 0xF
                r[rd] = if ((op and (1 shl 22)) != 0) {
                    val b = bankOf(mode); if (b != 0) spsr[b] else getCpsr()
                } else getCpsr()
            }
            A_MSR_REG -> msr(op, r[op and 0xF])
            A_MSR_IMM -> msr(op, Integer.rotateRight(op and 0xFF, (op ushr 7) and 0x1E))
            A_SDT -> singleTransfer(op, false)
            A_SDT_REG -> singleTransfer(op, true)
            A_BLOCK -> blockTransfer(op)
            A_B -> {
                val off = (op shl 8) shr 6
                if ((op and (1 shl 24)) != 0) r[14] = r[15] - 4
                branchTo(r[15] + off)
            }
            A_SWI -> swi((op ushr 16) and 0xFF)
            else -> undefined()
        }
    }

    private fun swi(num: Int) {
        val h = hle
        if (h != null) h.swi(num)
        else exception(SVC, 0x08, if (thumb) r[15] - 2 else r[15] - 4)
    }

    private fun undefined() {
        exception(UND, 0x04, if (thumb) r[15] - 2 else r[15] - 4)
    }

    private fun dataProc(op: Int, imm: Boolean) {
        val opcode = (op ushr 21) and 0xF
        val s = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val b: Int
        val a: Int
        if (imm) {
            val rot = (op ushr 7) and 0x1E
            b = Integer.rotateRight(op and 0xFF, rot)
            shCarry = if (rot == 0) c else b < 0
            a = r[rn]
        } else {
            val rm = op and 0xF
            val type = (op ushr 5) and 3
            if ((op and 0x10) != 0) {
                val amount = r[(op ushr 8) and 0xF] and 0xFF
                val rmVal = if (rm == 15) r[15] + 4 else r[rm]
                a = if (rn == 15) r[15] + 4 else r[rn]
                b = shiftReg(type, rmVal, amount)
                internal += 1
            } else {
                b = shiftImm(type, r[rm], (op ushr 7) and 0x1F)
                a = r[rn]
            }
        }
        when (opcode) {
            0 -> logic(rd, a and b, s)
            1 -> logic(rd, a xor b, s)
            2 -> writeDp(rd, sub(a, b, s), s)
            3 -> writeDp(rd, sub(b, a, s), s)
            4 -> writeDp(rd, add(a, b, s), s)
            5 -> writeDp(rd, adc(a, b, s), s)
            6 -> writeDp(rd, sbc(a, b, s), s)
            7 -> writeDp(rd, sbc(b, a, s), s)
            8 -> { val res = a and b; n = res < 0; z = res == 0; c = shCarry; if (rd == 15) restoreCpsr() }
            9 -> { val res = a xor b; n = res < 0; z = res == 0; c = shCarry; if (rd == 15) restoreCpsr() }
            10 -> { sub(a, b, true); if (rd == 15) restoreCpsr() }
            11 -> { add(a, b, true); if (rd == 15) restoreCpsr() }
            12 -> logic(rd, a or b, s)
            13 -> logic(rd, b, s)
            14 -> logic(rd, a and b.inv(), s)
            else -> logic(rd, b.inv(), s)
        }
    }

    private fun logic(rd: Int, res: Int, s: Boolean) {
        if (s) { n = res < 0; z = res == 0; c = shCarry }
        writeDp(rd, res, s)
    }

    private fun writeDp(rd: Int, res: Int, s: Boolean) {
        if (rd == 15) {
            if (s) restoreCpsr()
            branchTo(res)
        } else r[rd] = res
    }

    private fun msr(op: Int, value: Int) {
        var mask = 0
        if ((op and (1 shl 19)) != 0) mask = mask or 0xFF000000.toInt()
        if ((op and (1 shl 16)) != 0) mask = mask or 0xFF
        if ((op and (1 shl 22)) != 0) {
            val b = bankOf(mode)
            if (b != 0) spsr[b] = (spsr[b] and mask.inv()) or (value and mask)
        } else {
            if (mode == USR) mask = mask and 0xFF000000.toInt()
            val cur = getCpsr()
            var nv = (cur and mask.inv()) or (value and mask)
            nv = (nv and 0x20.inv()) or (cur and 0x20) // MSR no cambia el estado Thumb
            setCpsr(nv)
        }
    }

    private fun multiply(op: Int) {
        val rd = (op ushr 16) and 0xF
        val rn = (op ushr 12) and 0xF
        val rs = (op ushr 8) and 0xF
        val rm = op and 0xF
        var res = r[rm] * r[rs]
        internal += mulCycles(r[rs])
        if ((op and (1 shl 21)) != 0) { res += r[rn]; internal += 1 }
        if ((op and (1 shl 20)) != 0) { n = res < 0; z = res == 0 }
        r[rd] = res
    }

    private fun multiplyLong(op: Int) {
        val hi = (op ushr 16) and 0xF
        val lo = (op ushr 12) and 0xF
        val rs = (op ushr 8) and 0xF
        val rm = op and 0xF
        val signed = (op and (1 shl 22)) != 0
        var res = if (signed) r[rm].toLong() * r[rs].toLong()
        else (r[rm].toLong() and 0xFFFFFFFFL) * (r[rs].toLong() and 0xFFFFFFFFL)
        internal += mulCycles(r[rs]) + 1
        if ((op and (1 shl 21)) != 0) {
            res += (r[hi].toLong() shl 32) or (r[lo].toLong() and 0xFFFFFFFFL)
            internal += 1
        }
        if ((op and (1 shl 20)) != 0) { n = res < 0; z = res == 0L }
        r[lo] = res.toInt()
        r[hi] = (res ushr 32).toInt()
    }

    private fun swap(op: Int) {
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val rm = op and 0xF
        val addr = r[rn]
        if ((op and (1 shl 22)) != 0) {
            val t = bus.read8(addr)
            bus.write8(addr, r[rm] and 0xFF)
            setReg(rd, t)
        } else {
            val t = Integer.rotateRight(bus.read32(addr), (addr and 3) shl 3)
            bus.write32(addr and 3.inv(), r[rm])
            setReg(rd, t)
        }
        internal += 1
    }

    private fun halfTransfer(op: Int) {
        val p = (op and (1 shl 24)) != 0
        val u = (op and (1 shl 23)) != 0
        val w = (op and (1 shl 21)) != 0
        val l = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val sh = (op ushr 5) and 3
        val offset = if ((op and (1 shl 22)) != 0) ((op ushr 4) and 0xF0) or (op and 0xF) else r[op and 0xF]
        val base = r[rn]
        val offAddr = if (u) base + offset else base - offset
        val addr = if (p) offAddr else base
        val wb = (!p || w) && rn != 15
        if (l) {
            val value = when (sh) {
                1 -> Integer.rotateRight(bus.read16(addr), (addr and 1) shl 3)
                2 -> bus.read8(addr).toByte().toInt()
                else -> if ((addr and 1) != 0) bus.read8(addr).toByte().toInt()
                else bus.read16(addr).toShort().toInt()
            }
            if (wb) r[rn] = offAddr
            setReg(rd, value)
            internal += 1
        } else {
            val value = if (rd == 15) r[15] + 4 else r[rd]
            bus.write16(addr, value and 0xFFFF)
            if (wb) r[rn] = offAddr
        }
    }

    private fun singleTransfer(op: Int, regOffset: Boolean) {
        val p = (op and (1 shl 24)) != 0
        val u = (op and (1 shl 23)) != 0
        val byte = (op and (1 shl 22)) != 0
        val w = (op and (1 shl 21)) != 0
        val l = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val offset = if (regOffset) {
            val saved = shCarry
            val x = shiftImm((op ushr 5) and 3, r[op and 0xF], (op ushr 7) and 0x1F)
            shCarry = saved
            x
        } else op and 0xFFF
        val base = r[rn]
        val offAddr = if (u) base + offset else base - offset
        val addr = if (p) offAddr else base
        val wb = (!p || w) && rn != 15
        if (l) {
            val value = if (byte) bus.read8(addr)
            else Integer.rotateRight(bus.read32(addr), (addr and 3) shl 3)
            if (wb) r[rn] = offAddr
            setReg(rd, value)
            internal += 1
        } else {
            val value = if (rd == 15) r[15] + 4 else r[rd]
            if (byte) bus.write8(addr, value and 0xFF) else bus.write32(addr, value)
            if (wb) r[rn] = offAddr
        }
    }

    private fun blockTransfer(op: Int) {
        val p = (op and (1 shl 24)) != 0
        val u = (op and (1 shl 23)) != 0
        val s = (op and (1 shl 22)) != 0
        val w = (op and (1 shl 21)) != 0
        val l = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        var list = op and 0xFFFF
        val count: Int
        if (list == 0) { list = 0x8000; count = 16 } else count = Integer.bitCount(list)
        val base = r[rn]
        val size = count * 4
        var addr = if (u) { if (p) base + 4 else base } else { if (p) base - size else base - size + 4 }
        val newBase = if (u) base + size else base - size
        val userBank = s && !(l && (list and 0x8000) != 0)
        if (l) {
            if (w && (list and (1 shl rn)) == 0) r[rn] = newBase
            for (i in 0..15) {
                if ((list and (1 shl i)) == 0) continue
                val value = bus.read32(addr)
                addr += 4
                when {
                    i == 15 -> { if (s) restoreCpsr(); branchTo(value) }
                    userBank -> setUserReg(i, value)
                    else -> r[i] = value
                }
            }
            internal += 1
        } else {
            var first = true
            for (i in 0..15) {
                if ((list and (1 shl i)) == 0) continue
                val value = when {
                    i == 15 -> r[15] + 4
                    i == rn && !userBank -> if (first || !w) base else newBase
                    userBank -> getUserReg(i)
                    else -> r[i]
                }
                bus.write32(addr and 3.inv(), value)
                addr += 4
                first = false
            }
            if (w) r[rn] = newBase
        }
    }

    // ---------------------------------------------------------------- Thumb

    private fun execThumb(op: Int) {
        when (thumbTable[op ushr 6].toInt()) {
            1 -> { // desplazamiento inmediato
                val rd = op and 7
                val x = r[(op ushr 3) and 7]
                val res = shiftImm((op ushr 11) and 3, x, (op ushr 6) and 0x1F)
                c = shCarry
                n = res < 0; z = res == 0
                r[rd] = res
            }
            2 -> { // add/sub
                val rd = op and 7
                val a = r[(op ushr 3) and 7]
                val field = (op ushr 6) and 7
                val b = if ((op and 0x400) != 0) field else r[field]
                r[rd] = if ((op and 0x200) != 0) sub(a, b, true) else add(a, b, true)
            }
            3 -> { // mov/cmp/add/sub inmediato
                val rd = (op ushr 8) and 7
                val imm = op and 0xFF
                when ((op ushr 11) and 3) {
                    0 -> { r[rd] = imm; n = false; z = imm == 0 }
                    1 -> sub(r[rd], imm, true)
                    2 -> r[rd] = add(r[rd], imm, true)
                    else -> r[rd] = sub(r[rd], imm, true)
                }
            }
            4 -> thumbAlu(op)
            5 -> thumbHiReg(op)
            6 -> { // LDR PC-relativo
                val rd = (op ushr 8) and 7
                r[rd] = bus.read32(((r[15] and 2.inv()) + ((op and 0xFF) shl 2)) and 3.inv())
                internal += 1
            }
            7 -> { // load/store con registro de offset
                val rd = op and 7
                val addr = r[(op ushr 3) and 7] + r[(op ushr 6) and 7]
                when ((op ushr 10) and 3) {
                    0 -> bus.write32(addr, r[rd])
                    1 -> bus.write8(addr, r[rd] and 0xFF)
                    2 -> { r[rd] = Integer.rotateRight(bus.read32(addr), (addr and 3) shl 3); internal += 1 }
                    else -> { r[rd] = bus.read8(addr); internal += 1 }
                }
            }
            8 -> { // halfword / con signo
                val rd = op and 7
                val addr = r[(op ushr 3) and 7] + r[(op ushr 6) and 7]
                when ((op ushr 10) and 3) {
                    0 -> bus.write16(addr, r[rd] and 0xFFFF)
                    1 -> { r[rd] = bus.read8(addr).toByte().toInt(); internal += 1 }
                    2 -> { r[rd] = Integer.rotateRight(bus.read16(addr), (addr and 1) shl 3); internal += 1 }
                    else -> {
                        r[rd] = if ((addr and 1) != 0) bus.read8(addr).toByte().toInt()
                        else bus.read16(addr).toShort().toInt()
                        internal += 1
                    }
                }
            }
            9 -> { // load/store con offset inmediato
                val rd = op and 7
                val rb = (op ushr 3) and 7
                val off = (op ushr 6) and 0x1F
                val byte = (op and 0x1000) != 0
                val load = (op and 0x800) != 0
                if (byte) {
                    val addr = r[rb] + off
                    if (load) { r[rd] = bus.read8(addr); internal += 1 } else bus.write8(addr, r[rd] and 0xFF)
                } else {
                    val addr = r[rb] + (off shl 2)
                    if (load) {
                        r[rd] = Integer.rotateRight(bus.read32(addr), (addr and 3) shl 3); internal += 1
                    } else bus.write32(addr, r[rd])
                }
            }
            10 -> { // halfword con offset inmediato
                val rd = op and 7
                val addr = r[(op ushr 3) and 7] + (((op ushr 6) and 0x1F) shl 1)
                if ((op and 0x800) != 0) {
                    r[rd] = Integer.rotateRight(bus.read16(addr), (addr and 1) shl 3); internal += 1
                } else bus.write16(addr, r[rd] and 0xFFFF)
            }
            11 -> { // relativo a SP
                val rd = (op ushr 8) and 7
                val addr = r[13] + ((op and 0xFF) shl 2)
                if ((op and 0x800) != 0) {
                    r[rd] = Integer.rotateRight(bus.read32(addr), (addr and 3) shl 3); internal += 1
                } else bus.write32(addr, r[rd])
            }
            12 -> { // ADD rd, PC/SP, #imm
                val rd = (op ushr 8) and 7
                val imm = (op and 0xFF) shl 2
                r[rd] = if ((op and 0x800) != 0) r[13] + imm else (r[15] and 2.inv()) + imm
            }
            13 -> { // ADD SP, #imm
                val imm = (op and 0x7F) shl 2
                r[13] = if ((op and 0x80) != 0) r[13] - imm else r[13] + imm
            }
            14 -> pushPop(op)
            15 -> thumbMulti(op)
            16 -> { // salto condicional
                if (cond((op ushr 8) and 0xF)) branchTo(r[15] + ((op shl 24) shr 23))
            }
            17 -> swi(op and 0xFF)
            18 -> branchTo(r[15] + ((op shl 21) shr 20))
            19 -> {
                val off = op and 0x7FF
                if ((op and 0x800) == 0) {
                    r[14] = r[15] + ((off shl 21) shr 9)
                } else {
                    val next = (r[15] - 2) or 1
                    branchTo(r[14] + (off shl 1))
                    r[14] = next
                }
            }
            else -> undefined()
        }
    }

    private fun thumbAlu(op: Int) {
        val rd = op and 7
        val rs = r[(op ushr 3) and 7]
        val d = r[rd]
        when ((op ushr 6) and 0xF) {
            0 -> setNZ(rd, d and rs)
            1 -> setNZ(rd, d xor rs)
            2 -> { val x = shiftReg(0, d, rs and 0xFF); c = shCarry; setNZ(rd, x); internal += 1 }
            3 -> { val x = shiftReg(1, d, rs and 0xFF); c = shCarry; setNZ(rd, x); internal += 1 }
            4 -> { val x = shiftReg(2, d, rs and 0xFF); c = shCarry; setNZ(rd, x); internal += 1 }
            5 -> r[rd] = adc(d, rs, true)
            6 -> r[rd] = sbc(d, rs, true)
            7 -> { val x = shiftReg(3, d, rs and 0xFF); c = shCarry; setNZ(rd, x); internal += 1 }
            8 -> { val x = d and rs; n = x < 0; z = x == 0 }
            9 -> r[rd] = sub(0, rs, true)
            10 -> sub(d, rs, true)
            11 -> add(d, rs, true)
            12 -> setNZ(rd, d or rs)
            13 -> { internal += mulCycles(d); setNZ(rd, d * rs) }
            14 -> setNZ(rd, d and rs.inv())
            else -> setNZ(rd, rs.inv())
        }
    }

    private fun setNZ(rd: Int, x: Int) {
        n = x < 0; z = x == 0; r[rd] = x
    }

    private fun thumbHiReg(op: Int) {
        val rd = (op and 7) or ((op ushr 4) and 8)
        val rs = (op ushr 3) and 0xF
        val sv = r[rs]
        when ((op ushr 8) and 3) {
            0 -> { val res = r[rd] + sv; if (rd == 15) branchTo(res) else r[rd] = res }
            1 -> sub(r[rd], sv, true)
            2 -> if (rd == 15) branchTo(sv) else r[rd] = sv
            else -> { thumb = (sv and 1) != 0; branchTo(sv) }
        }
    }

    private fun pushPop(op: Int) {
        val list = op and 0xFF
        val extra = (op and 0x100) != 0
        if ((op and 0x800) == 0) { // PUSH
            if (list == 0 && !extra) {
                r[13] -= 0x40
                bus.write32(r[13] and 3.inv(), r[15] + 2)
                return
            }
            val cnt = Integer.bitCount(list) + (if (extra) 1 else 0)
            var addr = r[13] - cnt * 4
            r[13] = addr
            for (i in 0..7) if ((list and (1 shl i)) != 0) { bus.write32(addr and 3.inv(), r[i]); addr += 4 }
            if (extra) bus.write32(addr and 3.inv(), r[14])
        } else { // POP
            if (list == 0 && !extra) {
                val value = bus.read32(r[13] and 3.inv())
                r[13] += 0x40
                branchTo(value)
                return
            }
            var addr = r[13]
            for (i in 0..7) if ((list and (1 shl i)) != 0) { r[i] = bus.read32(addr); addr += 4 }
            if (extra) {
                val value = bus.read32(addr)
                addr += 4
                r[13] = addr
                branchTo(value)
            } else r[13] = addr
            internal += 1
        }
    }

    private fun thumbMulti(op: Int) {
        val rb = (op ushr 8) and 7
        val list = op and 0xFF
        val base = r[rb]
        val load = (op and 0x800) != 0
        if (list == 0) {
            if (load) { val value = bus.read32(base and 3.inv()); r[rb] = base + 0x40; branchTo(value) }
            else { bus.write32(base and 3.inv(), r[15] + 2); r[rb] = base + 0x40 }
            return
        }
        val newBase = base + Integer.bitCount(list) * 4
        var addr = base
        if (load) {
            for (i in 0..7) if ((list and (1 shl i)) != 0) { r[i] = bus.read32(addr); addr += 4 }
            if ((list and (1 shl rb)) == 0) r[rb] = newBase
            internal += 1
        } else {
            var first = true
            for (i in 0..7) if ((list and (1 shl i)) != 0) {
                bus.write32(addr and 3.inv(), if (i == rb && !first) newBase else r[i])
                addr += 4
                first = false
            }
            r[rb] = newBase
        }
    }

    // ---------------------------------------------------------------- estado guardado

    internal fun save(o: DataOutputStream) {
        o.ints(r); o.writeInt(getCpsr()); o.ints(bankSp); o.ints(bankLr); o.ints(spsr); o.ints(usrHi); o.ints(fiqHi)
        o.writeBoolean(halted); o.writeInt(curPc); o.writeBoolean(needRefill); o.writeInt(pipe0); o.writeInt(pipe1)
    }

    internal fun load(i: DataInputStream) {
        i.ints(r); val cpsr = i.readInt(); i.ints(bankSp); i.ints(bankLr); i.ints(spsr); i.ints(usrHi); i.ints(fiqHi)
        // fijamos el modo sin pasar por switchMode (los bancos ya vienen cargados)
        n = cpsr < 0; z = (cpsr and 0x40000000) != 0; c = (cpsr and 0x20000000) != 0; v = (cpsr and 0x10000000) != 0
        irqDisable = (cpsr and 0x80) != 0; fiqDisable = (cpsr and 0x40) != 0; thumb = (cpsr and 0x20) != 0
        mode = cpsr and 0x1F
        halted = i.readBoolean(); curPc = i.readInt(); needRefill = i.readBoolean(); pipe0 = i.readInt(); pipe1 = i.readInt()
    }
}
