package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Mapa de memoria de la GBA, con conteo aproximado de ciclos de espera (WAITCNT). */
class Bus(private val gba: Gba) {
    val bios = ByteArray(0x4000)
    val ewram = ByteArray(0x40000)
    val iwram = ByteArray(0x8000)
    val pram = ByteArray(0x400)
    val vram = ByteArray(0x18000)
    val oam = ByteArray(0x400)
    var rom = ByteArray(4)
        private set
    var romSize = 0
        private set
    var backup: Backup = Backup.None()
    /** Puerto GPIO del cartucho (RTC, sensores, vibración), si lo tiene. */
    var gpio: Gpio? = null
    /** Sensor de inclinación en 0x0E008000, si lo tiene. */
    var tilt: TiltSensor? = null
    private var eepromStart = 0x0D000000

    /** Ciclos acumulados por los accesos de la instrucción en curso. */
    var cycles = 0
    private var lastFetch = -1
    private var pcInBios = false
    var biosLatch = 0xE129F000.toInt()
    private var openBus = 0

    private val n16 = IntArray(16)
    private val s16 = IntArray(16)
    private val n32 = IntArray(16)
    private val s32 = IntArray(16)
    private var prefetch = false

    init { updateWaitstates(0) }

    fun loadRom(data: ByteArray) {
        romSize = minOf(data.size, 0x2000000)
        rom = ByteArray((romSize + 3) and 3.inv())
        System.arraycopy(data, 0, rom, 0, romSize)
        eepromStart = if (romSize > 0x1000000) 0x0DFFFF00 else 0x0D000000
    }

    fun updateWaitstates(w: Int) {
        for (i in 0 until 16) { n16[i] = 1; s16[i] = 1; n32[i] = 1; s32[i] = 1 }
        n16[2] = 3; s16[2] = 3; n32[2] = 6; s32[2] = 6
        n32[5] = 2; s32[5] = 2; n32[6] = 2; s32[6] = 2
        val nTab = intArrayOf(4, 3, 2, 8)
        val sram = 1 + nTab[w and 3]
        for (i in 0xE..0xF) { n16[i] = sram; s16[i] = sram; n32[i] = sram; s32[i] = sram }
        val ns = intArrayOf(
            1 + nTab[(w ushr 2) and 3], 1 + (if ((w and 0x10) != 0) 1 else 2),
            1 + nTab[(w ushr 5) and 3], 1 + (if ((w and 0x80) != 0) 1 else 4),
            1 + nTab[(w ushr 8) and 3], 1 + (if ((w and 0x400) != 0) 1 else 8),
        )
        for (ws in 0..2) for (k in 0..1) {
            val reg = 8 + ws * 2 + k
            val nn = ns[ws * 2]; val ss = ns[ws * 2 + 1]
            n16[reg] = nn; s16[reg] = ss; n32[reg] = nn + ss; s32[reg] = ss * 2
        }
        prefetch = (w and 0x4000) != 0
    }

    // ---------------------------------------------------------------- lectura de instrucciones

    fun fetch32(addr: Int): Int {
        val reg = (addr ushr 24) and 0xF
        cycles += if (addr == lastFetch + 4) {
            if (prefetch && reg in 8..0xD) 1 else s32[reg]
        } else n32[reg]
        lastFetch = addr
        val value: Int
        if ((addr ushr 24) == 0 && addr < 0x4000) {
            pcInBios = true
            value = bios.u32(addr and 0x3FFC)
            biosLatch = value
        } else {
            pcInBios = false
            value = raw32(addr)
        }
        openBus = value
        return value
    }

    fun fetch16(addr: Int): Int {
        val reg = (addr ushr 24) and 0xF
        cycles += if (addr == lastFetch + 2) {
            if (prefetch && reg in 8..0xD) 1 else s16[reg]
        } else n16[reg]
        lastFetch = addr
        val value: Int
        if ((addr ushr 24) == 0 && addr < 0x4000) {
            pcInBios = true
            value = bios.u16(addr and 0x3FFE)
            biosLatch = bios.u32(addr and 0x3FFC)
        } else {
            pcInBios = false
            value = raw16(addr)
        }
        openBus = value or (value shl 16)
        return value
    }

    // ---------------------------------------------------------------- accesos de datos

    fun read8(addr: Int): Int { cycles += n16[(addr ushr 24) and 0xF]; return raw8(addr) }
    fun read16(addr: Int): Int { cycles += n16[(addr ushr 24) and 0xF]; return raw16(addr) }
    fun read32(addr: Int): Int { cycles += n32[(addr ushr 24) and 0xF]; return raw32(addr) }

    private fun vramIndex(a: Int): Int {
        val o = a and 0x1FFFF
        return if (o >= 0x18000) o - 0x8000 else o
    }

    fun raw8(a: Int): Int = when (a ushr 24) {
        0x00 -> if (a < 0x4000) {
            if (pcInBios) bios.u8(a) else (biosLatch ushr ((a and 3) shl 3)) and 0xFF
        } else (openBus ushr ((a and 3) shl 3)) and 0xFF
        0x02 -> ewram.u8(a and 0x3FFFF)
        0x03 -> iwram.u8(a and 0x7FFF)
        0x04 -> (gba.ioRead16(a and 1.inv()) ushr ((a and 1) shl 3)) and 0xFF
        0x05 -> pram.u8(a and 0x3FF)
        0x06 -> vram.u8(vramIndex(a))
        0x07 -> oam.u8(a and 0x3FF)
        in 0x08..0x0D -> {
            val off = a and 0x1FFFFFF
            val g = gpio
            if (g != null && off in 0xC4..0xC9) (g.read(off) ushr ((a and 1) shl 3)) and 0xFF
            else if (off < romSize) rom.u8(off) else ((off ushr 1) ushr ((a and 1) shl 3)) and 0xFF
        }
        0x0E, 0x0F -> saveRead(a)
        else -> (openBus ushr ((a and 3) shl 3)) and 0xFF
    }

    fun raw16(addr: Int): Int {
        val a = addr and 1.inv()
        return when (a ushr 24) {
            0x00 -> if (a < 0x4000) {
                if (pcInBios) bios.u16(a) else (biosLatch ushr ((a and 2) shl 3)) and 0xFFFF
            } else (openBus ushr ((a and 2) shl 3)) and 0xFFFF
            0x02 -> ewram.u16(a and 0x3FFFE)
            0x03 -> iwram.u16(a and 0x7FFE)
            0x04 -> gba.ioRead16(a)
            0x05 -> pram.u16(a and 0x3FE)
            0x06 -> vram.u16(vramIndex(a))
            0x07 -> oam.u16(a and 0x3FE)
            in 0x08..0x0D -> {
                val bk = backup
                if (bk is Backup.Eeprom && a >= eepromStart) bk.readBit()
                else {
                    val off = a and 0x1FFFFFF
                    val g = gpio
                    if (g != null && off in 0xC4..0xC9) g.read(off)
                    else if (off < romSize) rom.u16(off) else (off ushr 1) and 0xFFFF
                }
            }
            0x0E, 0x0F -> saveRead(addr) * 0x0101
            else -> (openBus ushr ((a and 2) shl 3)) and 0xFFFF
        }
    }

    fun raw32(addr: Int): Int {
        val a = addr and 3.inv()
        return when (a ushr 24) {
            0x00 -> if (a < 0x4000) { if (pcInBios) bios.u32(a) else biosLatch } else openBus
            0x02 -> ewram.u32(a and 0x3FFFC)
            0x03 -> iwram.u32(a and 0x7FFC)
            0x04 -> gba.ioRead16(a) or (gba.ioRead16(a + 2) shl 16)
            0x05 -> pram.u32(a and 0x3FC)
            0x06 -> vram.u32(vramIndex(a))
            0x07 -> oam.u32(a and 0x3FC)
            in 0x08..0x0D -> {
                val off = a and 0x1FFFFFF
                val g = gpio
                if (g != null && off in 0xC4..0xC9) g.read(off) or (g.read(off + 2) shl 16)
                else if (off < romSize) rom.u32(off)
                else ((off ushr 1) and 0xFFFF) or ((((off + 2) ushr 1) and 0xFFFF) shl 16)
            }
            0x0E, 0x0F -> saveRead(addr) * 0x01010101
            else -> openBus
        }
    }

    fun write8(a: Int, value: Int) {
        cycles += n16[(a ushr 24) and 0xF]
        val b = value.toByte()
        when (a ushr 24) {
            0x02 -> ewram[a and 0x3FFFF] = b
            0x03 -> iwram[a and 0x7FFF] = b
            0x04 -> gba.ioWrite8(a, value and 0xFF)
            0x05 -> { val i = a and 0x3FE; pram[i] = b; pram[i + 1] = b }
            0x06 -> {
                val i = vramIndex(a) and 1.inv()
                val limit = if (gba.ppu.bitmapMode()) 0x14000 else 0x10000
                if (i < limit) { vram[i] = b; vram[i + 1] = b }
            }
            in 0x08..0x09 -> gpioWrite(a, value and 0xFF)
            0x0E, 0x0F -> saveWrite(a, value and 0xFF)
        }
    }

    fun write16(addr: Int, value: Int) {
        cycles += n16[(addr ushr 24) and 0xF]
        val a = addr and 1.inv()
        when (a ushr 24) {
            0x02 -> ewram.w16(a and 0x3FFFE, value)
            0x03 -> iwram.w16(a and 0x7FFE, value)
            0x04 -> gba.ioWrite16(a, value and 0xFFFF)
            0x05 -> pram.w16(a and 0x3FE, value)
            0x06 -> vram.w16(vramIndex(a), value)
            0x07 -> oam.w16(a and 0x3FE, value)
            in 0x08..0x09 -> gpioWrite(a, value and 0xFFFF)
            0x0D -> { val bk = backup; if (bk is Backup.Eeprom && a >= eepromStart) bk.writeBit(value and 1) }
            0x0E, 0x0F -> saveWrite(addr, (value ushr ((addr and 1) shl 3)) and 0xFF)
        }
    }

    fun write32(addr: Int, value: Int) {
        cycles += n32[(addr ushr 24) and 0xF]
        val a = addr and 3.inv()
        when (a ushr 24) {
            0x02 -> ewram.w32(a and 0x3FFFC, value)
            0x03 -> iwram.w32(a and 0x7FFC, value)
            0x04 -> gba.ioWrite32(a, value)
            0x05 -> pram.w32(a and 0x3FC, value)
            0x06 -> vram.w32(vramIndex(a), value)
            0x07 -> oam.w32(a and 0x3FC, value)
            in 0x08..0x09 -> { gpioWrite(a, value and 0xFFFF); gpioWrite(a + 2, value ushr 16) }
            0x0D -> { val bk = backup; if (bk is Backup.Eeprom && a >= eepromStart) bk.writeBit(value and 1) }
            0x0E, 0x0F -> saveWrite(addr, (value ushr ((addr and 3) shl 3)) and 0xFF)
        }
    }

    private fun gpioWrite(a: Int, value: Int) {
        val off = a and 0x1FFFFFF
        if (off in 0xC4..0xC9) gpio?.write(off, value)
    }

    private fun saveRead(addr: Int): Int {
        val t = tilt
        if (t != null) { val v = t.read(addr and 0xFFFF); if (v >= 0) return v }
        return backup.read8(addr and 0xFFFF)
    }

    private fun saveWrite(addr: Int, value: Int) {
        val t = tilt
        val a = addr and 0xFFFF
        if (t != null && (a == 0x8000 || a == 0x8100)) t.write(a, value) else backup.write8(a, value)
    }

    fun isEeprom(addr: Int): Boolean = backup is Backup.Eeprom && (addr ushr 24) == 0x0D && addr >= eepromStart

    internal fun save(o: DataOutputStream) {
        o.bytes(ewram); o.bytes(iwram); o.bytes(pram); o.bytes(vram); o.bytes(oam)
        o.writeInt(lastFetch); o.writeBoolean(pcInBios); o.writeInt(biosLatch); o.writeInt(openBus)
    }

    internal fun load(i: DataInputStream) {
        i.bytes(ewram); i.bytes(iwram); i.bytes(pram); i.bytes(vram); i.bytes(oam)
        lastFetch = i.readInt(); pcInBios = i.readBoolean(); biosLatch = i.readInt(); openBus = i.readInt()
        updateWaitstates(gba.io.u16(0x204))
    }
}
