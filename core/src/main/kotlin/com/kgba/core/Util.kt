package com.kgba.core

@Suppress("NOTHING_TO_INLINE")
inline fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

@Suppress("NOTHING_TO_INLINE")
inline fun ByteArray.u16(i: Int): Int =
    (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

@Suppress("NOTHING_TO_INLINE")
inline fun ByteArray.u32(i: Int): Int =
    (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
        ((this[i + 2].toInt() and 0xFF) shl 16) or ((this[i + 3].toInt() and 0xFF) shl 24)

@Suppress("NOTHING_TO_INLINE")
inline fun ByteArray.w16(i: Int, v: Int) {
    this[i] = v.toByte(); this[i + 1] = (v ushr 8).toByte()
}

@Suppress("NOTHING_TO_INLINE")
inline fun ByteArray.w32(i: Int, v: Int) {
    this[i] = v.toByte(); this[i + 1] = (v ushr 8).toByte()
    this[i + 2] = (v ushr 16).toByte(); this[i + 3] = (v ushr 24).toByte()
}

object Irq {
    const val VBLANK = 0x0001
    const val HBLANK = 0x0002
    const val VCOUNT = 0x0004
    const val TIMER0 = 0x0008
    const val SERIAL = 0x0080
    const val DMA0 = 0x0100
    const val KEYPAD = 0x1000
}

/** Bits de los botones (1 = pulsado) para [Gba.setButtons]. */
object Button {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9
}
