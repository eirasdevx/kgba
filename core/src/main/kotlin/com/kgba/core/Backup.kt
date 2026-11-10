package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Memoria de guardado del cartucho: SRAM, Flash o EEPROM. */
sealed class Backup {
    abstract val data: ByteArray
    /** Se pone a true cuando el juego escribe; el frontend lo usa para saber cuándo guardar. */
    var dirty = false
    abstract val typeName: String

    open fun read8(addr: Int): Int = 0xFF
    open fun write8(addr: Int, value: Int) {}

    internal open fun saveState(o: DataOutputStream) = o.bytes(data)
    internal open fun loadState(i: DataInputStream) = i.bytes(data)

    fun load(saved: ByteArray?) {
        if (saved == null) return
        System.arraycopy(saved, 0, data, 0, minOf(saved.size, data.size))
    }

    class None : Backup() {
        override val data = ByteArray(0)
        override val typeName = "NONE"
    }

    class Sram : Backup() {
        override val data = ByteArray(0x8000) { 0xFF.toByte() }
        override val typeName = "SRAM"
        override fun read8(addr: Int) = data.u8(addr and 0x7FFF)
        override fun write8(addr: Int, value: Int) {
            data[addr and 0x7FFF] = value.toByte(); dirty = true
        }
    }

    class Flash(size: Int) : Backup() {
        override val data = ByteArray(size) { 0xFF.toByte() }
        override val typeName = if (size == 0x20000) "FLASH1M" else "FLASH512"
        private val big = size == 0x20000
        private var state = 0
        private var idMode = false
        private var eraseArmed = false
        private var writeNext = false
        private var bankNext = false
        private var bank = 0

        override fun saveState(o: DataOutputStream) {
            super.saveState(o)
            o.writeInt(state); o.writeBoolean(idMode); o.writeBoolean(eraseArmed); o.writeBoolean(writeNext)
            o.writeBoolean(bankNext); o.writeInt(bank)
        }

        override fun loadState(i: DataInputStream) {
            super.loadState(i)
            state = i.readInt(); idMode = i.readBoolean(); eraseArmed = i.readBoolean(); writeNext = i.readBoolean()
            bankNext = i.readBoolean(); bank = i.readInt()
        }

        override fun read8(addr: Int): Int {
            val a = addr and 0xFFFF
            if (idMode && a < 2) {
                return if (big) (if (a == 0) 0x62 else 0x13) else (if (a == 0) 0x32 else 0x1B)
            }
            return data.u8(bank * 0x10000 + a)
        }

        override fun write8(addr: Int, value: Int) {
            val a = addr and 0xFFFF
            if (writeNext) {
                data[bank * 0x10000 + a] = value.toByte()
                dirty = true; writeNext = false; return
            }
            if (bankNext) {
                if (a == 0 && big) bank = value and 1
                bankNext = false; return
            }
            when (state) {
                0 -> if (a == 0x5555 && value == 0xAA) state = 1
                1 -> state = if (a == 0x2AAA && value == 0x55) 2 else 0
                2 -> {
                    state = 0
                    if (eraseArmed && value == 0x30) {
                        val base = bank * 0x10000 + (a and 0xF000)
                        java.util.Arrays.fill(data, base, base + 0x1000, 0xFF.toByte())
                        dirty = true; eraseArmed = false
                    } else if (a == 0x5555) when (value) {
                        0x90 -> idMode = true
                        0xF0 -> idMode = false
                        0x80 -> eraseArmed = true
                        0x10 -> if (eraseArmed) {
                            java.util.Arrays.fill(data, 0xFF.toByte()); dirty = true; eraseArmed = false
                        }
                        0xA0 -> writeNext = true
                        0xB0 -> bankNext = true
                    }
                }
            }
        }
    }

    /** EEPROM de 512 bytes u 8 KB, accedida bit a bit por DMA en 0x0D000000. */
    class Eeprom : Backup() {
        override val data = ByteArray(0x2000) { 0xFF.toByte() }
        override val typeName get() = if (addrBits == 6) "EEPROM512" else "EEPROM8K"
        var addrBits = 0 // 0 = aún sin detectar
        private val bits = IntArray(128)
        private var count = 0
        private var readAddr = 0
        private var readPos = -1

        override fun saveState(o: DataOutputStream) {
            super.saveState(o)
            o.writeInt(addrBits); o.ints(bits); o.writeInt(count); o.writeInt(readAddr); o.writeInt(readPos)
        }

        override fun loadState(i: DataInputStream) {
            super.loadState(i)
            addrBits = i.readInt(); i.ints(bits); count = i.readInt(); readAddr = i.readInt(); readPos = i.readInt()
        }

        /** El DMA avisa del número de transferencias para deducir el tamaño de la EEPROM. */
        fun detectFromDma(units: Int) {
            if (addrBits != 0) return
            when (units) {
                9, 73 -> addrBits = 6
                17, 81 -> addrBits = 14
            }
        }

        fun writeBit(bit: Int) {
            if (count < bits.size) bits[count++] = bit
            val ab = if (addrBits == 0) 14 else addrBits
            if (count >= 2) {
                val cmd = (bits[0] shl 1) or bits[1]
                if (cmd == 3 && count == 2 + ab + 1) {
                    readAddr = (addr(2, ab) and 0x3FF) * 8
                    readPos = 0
                    count = 0
                } else if (cmd == 2 && count == 2 + ab + 64 + 1) {
                    val base = (addr(2, ab) and 0x3FF) * 8
                    for (byte in 0 until 8) {
                        var x = 0
                        for (b in 0 until 8) x = (x shl 1) or bits[2 + ab + byte * 8 + b]
                        data[base + byte] = x.toByte()
                    }
                    dirty = true
                    count = 0
                    readPos = -1
                } else if (cmd < 2) count = 0
            }
        }

        private fun addr(start: Int, n: Int): Int {
            var x = 0
            for (i in 0 until n) x = (x shl 1) or bits[start + i]
            return x
        }

        fun readBit(): Int {
            if (readPos < 0) return 1
            val p = readPos++
            if (p >= 68) { readPos = -1; return 1 }
            if (p < 4) return 0
            val bitIndex = p - 4
            val byte = data.u8(readAddr + (bitIndex shr 3))
            if (p == 67) readPos = -1
            return (byte ushr (7 - (bitIndex and 7))) and 1
        }
    }

    companion object {
        fun detect(rom: ByteArray): Backup {
            fun has(s: String): Boolean {
                val p = s.toByteArray()
                var i = 0
                while (i + p.size <= rom.size) {
                    var ok = true
                    for (j in p.indices) if (rom[i + j] != p[j]) { ok = false; break }
                    if (ok) return true
                    i += 4
                }
                return false
            }
            return when {
                has("EEPROM_V") -> Eeprom()
                has("FLASH1M_V") -> Flash(0x20000)
                has("FLASH512_V") || has("FLASH_V") -> Flash(0x10000)
                has("SRAM_V") || has("SRAM_F_V") -> Sram()
                else -> Sram() // por si acaso: muchos homebrew usan SRAM sin firma
            }
        }
    }
}
