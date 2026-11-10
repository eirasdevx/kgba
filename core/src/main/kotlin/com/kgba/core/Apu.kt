package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Sonido: 4 canales PSG heredados de la Game Boy + 2 canales Direct Sound (FIFO A/B).
 * Genera audio estéreo de 16 bits a [SAMPLE_RATE] Hz.
 */
class Apu(private val gba: Gba) {
    companion object {
        const val SAMPLE_RATE = 32768
        private const val CYCLES_PER_SAMPLE = 16777216 / SAMPLE_RATE // 512
        private val DUTY = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 0),
        )
    }

    /** Muestras estéreo intercaladas (L, R) generadas desde la última vez que se vació. */
    val buffer = ShortArray(8192)
    var bufferPos = 0

    private var sampleCycles = 0
    private var frameSeqStep = 0
    private var samplesToFrameSeq = 64

    // ---- Direct Sound
    private val fifo = Array(2) { ByteArray(32) }
    private val fifoRead = IntArray(2)
    private val fifoWrite = IntArray(2)
    private val fifoCount = IntArray(2)
    private val fifoSample = IntArray(2)

    // ---- PSG
    private class Square {
        var on = false; var freq = 0; var duty = 0; var pos = 0; var timer = 0
        var length = 0; var lengthEn = false
        var vol = 0; var envDir = 0; var envPeriod = 0; var envTimer = 0
        var sweepShift = 0; var sweepDown = false; var sweepPeriod = 0; var sweepTimer = 0; var shadow = 0
        fun period() = (2048 - freq) * 16
    }

    private val sq1 = Square()
    private val sq2 = Square()

    private var wOn = false; private var wFreq = 0; private var wTimer = 0; private var wPos = 0
    private var wLength = 0; private var wLengthEn = false
    private val waveRam = ByteArray(32)

    private var nOn = false; private var nTimer = 0; private var nLfsr = 0x7FFF
    private var nLength = 0; private var nLengthEn = false
    private var nVol = 0; private var nEnvDir = 0; private var nEnvPeriod = 0; private var nEnvTimer = 0

    private fun reg(off: Int) = gba.io.u16(off)

    // ---------------------------------------------------------------- registros

    fun read16(off: Int): Int = when (off) {
        0x60 -> reg(0x60) and 0x7F
        0x62 -> reg(0x62) and 0xFFC0
        0x64 -> reg(0x64) and 0x4000
        0x68 -> reg(0x68) and 0xFFC0
        0x6C -> reg(0x6C) and 0x4000
        0x70 -> reg(0x70) and 0xE0
        0x72 -> reg(0x72) and 0xE000
        0x74 -> reg(0x74) and 0x4000
        0x78 -> reg(0x78) and 0xFF00
        0x7C -> reg(0x7C) and 0x40FF
        0x80 -> reg(0x80) and 0xFF77
        0x82 -> reg(0x82) and 0x770F
        0x84 -> (reg(0x84) and 0x80) or (if (sq1.on) 1 else 0) or (if (sq2.on) 2 else 0) or
            (if (wOn) 4 else 0) or (if (nOn) 8 else 0)
        0x88 -> reg(0x88)
        in 0x90..0x9F -> {
            val bank = if ((reg(0x70) and 0x40) != 0) 0 else 16
            waveRam.u16(bank + (off - 0x90))
        }
        else -> 0
    }

    fun write16(off: Int, value: Int) {
        val masterOn = (reg(0x84) and 0x80) != 0
        if (!masterOn && off < 0x80 && off != 0x84) return
        if (off in 0x90..0x9F) {
            val bank = if ((reg(0x70) and 0x40) != 0) 0 else 16
            waveRam.w16(bank + (off - 0x90), value)
            return
        }
        if (off in 0xA0..0xA7) {
            fifoPush(off, value and 0xFF); fifoPush(off, (value ushr 8) and 0xFF)
            return
        }
        gba.io.w16(off, value)
        when (off) {
            0x60 -> {
                sq1.sweepShift = value and 7; sq1.sweepDown = (value and 8) != 0; sq1.sweepPeriod = (value ushr 4) and 7
            }
            0x62 -> squareEnvelope(sq1, value)
            0x64 -> squareFreq(sq1, value, true)
            0x68 -> squareEnvelope(sq2, value)
            0x6C -> squareFreq(sq2, value, false)
            0x70 -> if ((value and 0x80) == 0) wOn = false
            0x72 -> wLength = 256 - (value and 0xFF)
            0x74 -> {
                wFreq = value and 0x7FF
                wLengthEn = (value and 0x4000) != 0
                if ((value and 0x8000) != 0 && (reg(0x70) and 0x80) != 0) {
                    wOn = true
                    if (wLength == 0) wLength = 256
                    wTimer = (2048 - wFreq) * 8
                    wPos = 0
                }
            }
            0x78 -> {
                nLength = 64 - (value and 0x3F)
                nEnvPeriod = (value ushr 8) and 7; nEnvDir = if ((value and 0x800) != 0) 1 else -1
                if ((value and 0xF800) == 0) nOn = false
            }
            0x7C -> {
                nLengthEn = (value and 0x4000) != 0
                if ((value and 0x8000) != 0) {
                    val env = reg(0x78)
                    nOn = (env and 0xF800) != 0
                    if (nLength == 0) nLength = 64
                    nVol = env ushr 12; nEnvTimer = nEnvPeriod
                    nLfsr = if ((value and 8) != 0) 0x7F else 0x7FFF
                    nTimer = noisePeriod()
                }
            }
            0x82 -> {
                if ((value and 0x0800) != 0) resetFifo(0)
                if ((value and 0x8000) != 0) resetFifo(1)
                gba.io.w16(0x82, value and 0x770F)
            }
            0x84 -> if ((value and 0x80) == 0) {
                sq1.on = false; sq2.on = false; wOn = false; nOn = false
                for (r in 0x60..0x81 step 2) gba.io.w16(r, 0)
            }
        }
    }

    private fun squareEnvelope(s: Square, value: Int) {
        s.length = 64 - (value and 0x3F)
        s.duty = (value ushr 6) and 3
        s.envPeriod = (value ushr 8) and 7
        s.envDir = if ((value and 0x800) != 0) 1 else -1
        if ((value and 0xF800) == 0) s.on = false
    }

    private fun squareFreq(s: Square, value: Int, sweep: Boolean) {
        s.freq = value and 0x7FF
        s.lengthEn = (value and 0x4000) != 0
        if ((value and 0x8000) != 0) {
            val env = if (sweep) reg(0x62) else reg(0x68)
            s.on = (env and 0xF800) != 0
            if (s.length == 0) s.length = 64
            s.vol = env ushr 12
            s.envTimer = s.envPeriod
            s.timer = s.period()
            if (sweep) {
                s.shadow = s.freq
                s.sweepTimer = if (s.sweepPeriod == 0) 8 else s.sweepPeriod
                if (s.sweepShift != 0 && sweepCalc(s) > 2047) s.on = false
            }
        }
    }

    private fun sweepCalc(s: Square): Int {
        val d = s.shadow ushr s.sweepShift
        return if (s.sweepDown) s.shadow - d else s.shadow + d
    }

    private fun noisePeriod(): Int {
        val v = reg(0x7C)
        val r = v and 7
        val sh = (v ushr 4) and 0xF
        return ((if (r == 0) 8 else r * 16) shl sh) shl 2
    }

    // ---------------------------------------------------------------- FIFO

    fun fifoPush(off: Int, b: Int) {
        val f = if (off < 0xA4) 0 else 1
        if (fifoCount[f] >= 32) return
        fifo[f][fifoWrite[f]] = b.toByte()
        fifoWrite[f] = (fifoWrite[f] + 1) and 31
        fifoCount[f]++
    }

    private fun resetFifo(f: Int) {
        fifoRead[f] = 0; fifoWrite[f] = 0; fifoCount[f] = 0; fifoSample[f] = 0
    }

    fun timerOverflow(timer: Int) {
        if ((reg(0x84) and 0x80) == 0) return
        val cnt = reg(0x82)
        for (f in 0..1) {
            val sel = (cnt ushr (10 + f * 4)) and 1
            if (sel != timer) continue
            if (fifoCount[f] > 0) {
                fifoSample[f] = fifo[f][fifoRead[f]].toInt()
                fifoRead[f] = (fifoRead[f] + 1) and 31
                fifoCount[f]--
            }
            if (fifoCount[f] <= 16) gba.dma.fifoRequest(f)
        }
    }

    // ---------------------------------------------------------------- generación

    fun tick(cycles: Int) {
        sampleCycles += cycles
        while (sampleCycles >= CYCLES_PER_SAMPLE) {
            sampleCycles -= CYCLES_PER_SAMPLE
            generate()
        }
    }

    private fun frameSequencer() {
        val step = frameSeqStep
        frameSeqStep = (frameSeqStep + 1) and 7
        if ((step and 1) == 0) { // longitud, 256 Hz
            if (sq1.lengthEn && sq1.length > 0 && --sq1.length == 0) sq1.on = false
            if (sq2.lengthEn && sq2.length > 0 && --sq2.length == 0) sq2.on = false
            if (wLengthEn && wLength > 0 && --wLength == 0) wOn = false
            if (nLengthEn && nLength > 0 && --nLength == 0) nOn = false
        }
        if (step == 2 || step == 6) { // barrido, 128 Hz
            val s = sq1
            if (--s.sweepTimer <= 0) {
                s.sweepTimer = if (s.sweepPeriod == 0) 8 else s.sweepPeriod
                if (s.on && s.sweepPeriod != 0) {
                    val nf = sweepCalc(s)
                    if (nf > 2047) s.on = false
                    else if (s.sweepShift != 0) {
                        s.shadow = nf; s.freq = nf
                        if (sweepCalc(s) > 2047) s.on = false
                    }
                }
            }
        }
        if (step == 7) { // envolvente, 64 Hz
            envelope(sq1); envelope(sq2)
            if (nEnvPeriod != 0 && --nEnvTimer <= 0) {
                nEnvTimer = nEnvPeriod
                val nv = nVol + nEnvDir
                if (nv in 0..15) nVol = nv
            }
        }
    }

    private fun envelope(s: Square) {
        if (s.envPeriod != 0 && --s.envTimer <= 0) {
            s.envTimer = s.envPeriod
            val nv = s.vol + s.envDir
            if (nv in 0..15) s.vol = nv
        }
    }

    private fun squareOut(s: Square): Int {
        if (!s.on) return 0
        val p = s.period()
        s.timer -= CYCLES_PER_SAMPLE
        while (s.timer <= 0) { s.timer += p; s.pos = (s.pos + 1) and 7 }
        return if (DUTY[s.duty][s.pos] != 0) s.vol else -s.vol
    }

    private fun waveOut(): Int {
        if (!wOn) return 0
        val cnt3 = reg(0x70)
        val p = (2048 - wFreq) * 8
        val total = if ((cnt3 and 0x20) != 0) 64 else 32
        wTimer -= CYCLES_PER_SAMPLE
        while (wTimer <= 0) { wTimer += p; wPos = (wPos + 1) % total }
        val startBank = if ((cnt3 and 0x40) != 0) 16 else 0
        val idx = (startBank * 2 + wPos) and 63
        val b = waveRam.u8(idx shr 1)
        val sample = if ((idx and 1) == 0) b ushr 4 else b and 0xF
        val h = reg(0x72)
        val centered = sample * 2 - 15
        return when {
            (h and 0x8000) != 0 -> centered * 3 / 8
            else -> when ((h ushr 13) and 3) {
                0 -> 0; 1 -> centered / 2; 2 -> centered / 4; else -> centered / 8
            }
        }
    }

    private fun noiseOut(): Int {
        if (!nOn) return 0
        val p = noisePeriod()
        val narrow = (reg(0x7C) and 8) != 0
        nTimer -= CYCLES_PER_SAMPLE
        var high = 0
        var steps = 0
        while (nTimer <= 0) {
            nTimer += p
            val x = (nLfsr xor (nLfsr ushr 1)) and 1
            nLfsr = (nLfsr ushr 1) or (x shl 14)
            if (narrow) nLfsr = (nLfsr and 0x40.inv()) or (x shl 6)
            high += if ((nLfsr and 1) == 0) 1 else 0
            steps++
        }
        val on = if (steps == 0) (nLfsr and 1) == 0 else high * 2 >= steps
        return if (on) nVol else -nVol
    }

    private fun generate() {
        if (--samplesToFrameSeq <= 0) { samplesToFrameSeq = 64; frameSequencer() }
        var left = 0
        var right = 0
        val cntX = reg(0x84)
        if ((cntX and 0x80) != 0) {
            val cntL = reg(0x80)
            val cntH = reg(0x82)
            val c1 = squareOut(sq1); val c2 = squareOut(sq2); val c3 = waveOut(); val c4 = noiseOut()
            var psgR = 0; var psgL = 0
            if ((cntL and 0x0100) != 0) psgR += c1
            if ((cntL and 0x0200) != 0) psgR += c2
            if ((cntL and 0x0400) != 0) psgR += c3
            if ((cntL and 0x0800) != 0) psgR += c4
            if ((cntL and 0x1000) != 0) psgL += c1
            if ((cntL and 0x2000) != 0) psgL += c2
            if ((cntL and 0x4000) != 0) psgL += c3
            if ((cntL and 0x8000) != 0) psgL += c4
            psgR *= (cntL and 7) + 1
            psgL *= ((cntL ushr 4) and 7) + 1
            val psgShift = when (cntH and 3) { 0 -> 2; 1 -> 1; else -> 0 }
            right += psgR shr psgShift
            left += psgL shr psgShift
            val a = fifoSample[0] shl (if ((cntH and 4) != 0) 2 else 1)
            val b = fifoSample[1] shl (if ((cntH and 8) != 0) 2 else 1)
            if ((cntH and 0x0100) != 0) right += a
            if ((cntH and 0x0200) != 0) left += a
            if ((cntH and 0x1000) != 0) right += b
            if ((cntH and 0x2000) != 0) left += b
        }
        if (bufferPos + 2 <= buffer.size) {
            buffer[bufferPos++] = (left.coerceIn(-512, 511) * 64).toShort()
            buffer[bufferPos++] = (right.coerceIn(-512, 511) * 64).toShort()
        }
    }

    // ---------------------------------------------------------------- estado guardado

    private fun saveSq(o: DataOutputStream, s: Square) {
        o.writeBoolean(s.on); o.writeInt(s.freq); o.writeInt(s.duty); o.writeInt(s.pos); o.writeInt(s.timer)
        o.writeInt(s.length); o.writeBoolean(s.lengthEn); o.writeInt(s.vol); o.writeInt(s.envDir)
        o.writeInt(s.envPeriod); o.writeInt(s.envTimer); o.writeInt(s.sweepShift); o.writeBoolean(s.sweepDown)
        o.writeInt(s.sweepPeriod); o.writeInt(s.sweepTimer); o.writeInt(s.shadow)
    }

    private fun loadSq(i: DataInputStream, s: Square) {
        s.on = i.readBoolean(); s.freq = i.readInt(); s.duty = i.readInt(); s.pos = i.readInt(); s.timer = i.readInt()
        s.length = i.readInt(); s.lengthEn = i.readBoolean(); s.vol = i.readInt(); s.envDir = i.readInt()
        s.envPeriod = i.readInt(); s.envTimer = i.readInt(); s.sweepShift = i.readInt(); s.sweepDown = i.readBoolean()
        s.sweepPeriod = i.readInt(); s.sweepTimer = i.readInt(); s.shadow = i.readInt()
    }

    internal fun save(o: DataOutputStream) {
        o.writeInt(sampleCycles); o.writeInt(frameSeqStep); o.writeInt(samplesToFrameSeq)
        for (f in 0..1) o.bytes(fifo[f])
        o.ints(fifoRead); o.ints(fifoWrite); o.ints(fifoCount); o.ints(fifoSample)
        saveSq(o, sq1); saveSq(o, sq2)
        o.writeBoolean(wOn); o.writeInt(wFreq); o.writeInt(wTimer); o.writeInt(wPos); o.writeInt(wLength)
        o.writeBoolean(wLengthEn); o.bytes(waveRam)
        o.writeBoolean(nOn); o.writeInt(nTimer); o.writeInt(nLfsr); o.writeInt(nLength); o.writeBoolean(nLengthEn)
        o.writeInt(nVol); o.writeInt(nEnvDir); o.writeInt(nEnvPeriod); o.writeInt(nEnvTimer)
    }

    internal fun load(i: DataInputStream) {
        sampleCycles = i.readInt(); frameSeqStep = i.readInt(); samplesToFrameSeq = i.readInt()
        for (f in 0..1) i.bytes(fifo[f])
        i.ints(fifoRead); i.ints(fifoWrite); i.ints(fifoCount); i.ints(fifoSample)
        loadSq(i, sq1); loadSq(i, sq2)
        wOn = i.readBoolean(); wFreq = i.readInt(); wTimer = i.readInt(); wPos = i.readInt(); wLength = i.readInt()
        wLengthEn = i.readBoolean(); i.bytes(waveRam)
        nOn = i.readBoolean(); nTimer = i.readInt(); nLfsr = i.readInt(); nLength = i.readInt(); nLengthEn = i.readBoolean()
        nVol = i.readInt(); nEnvDir = i.readInt(); nEnvPeriod = i.readInt(); nEnvTimer = i.readInt()
        bufferPos = 0
    }
}
