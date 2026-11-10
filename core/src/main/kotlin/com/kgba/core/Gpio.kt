package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Calendar

/**
 * Valores de los sensores que rellena el frontend (se leen desde el hilo de emulación).
 * - [lux]: luz solar para Boktai, 0 (oscuro) .. 255 (sol fuerte).
 * - [tiltX]/[tiltY]: inclinación de la consola, -1..1 (Yoshi Topsy-Turvy, Koro Koro Puzzle).
 * - [gyroZ]: giro sobre el eje de la pantalla, -1..1 (WarioWare: Twisted!).
 */
class Sensors {
    @Volatile var lux = 0
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    @Volatile var gyroZ = 0f
}

/**
 * Puerto GPIO del cartucho (0x080000C4..0x080000C9) y los chips que cuelgan de él:
 * reloj de tiempo real S-3511 (Pokémon Rubí/Zafiro/Esmeralda, Boktai...), sensor solar (Boktai),
 * giroscopio (WarioWare: Twisted!) y motor de vibración (WarioWare: Twisted!, Drill Dozer).
 */
class Gpio(
    private val sensors: Sensors,
    val hasRtc: Boolean,
    val hasSolar: Boolean,
    val hasGyro: Boolean,
    val hasRumble: Boolean,
) {
    private var readable = false
    private var direction = 0
    private var pinState = 0

    /** Estado del motor de vibración (lo consulta el frontend). */
    @Volatile var rumble = false
        private set

    // --- RTC S-3511
    private var rtcStep = 0
    private var rtcBits = 0
    private var rtcBitsRead = 0
    private var rtcBytesLeft = 0
    private var rtcActive = false
    private var rtcCommand = 0
    private var rtcControl = 0x40 // modo 24 horas
    private val rtcTime = IntArray(7)

    // --- sensor solar
    private var lightCounter = 0
    private var lightSample = 0xFF
    private var lightEdge = false

    // --- giroscopio
    private var gyroSample = 0
    private var gyroEdge = false

    fun read(off: Int): Int {
        if (!readable) return 0
        return when (off and 0xE) {
            0x4 -> pinState
            0x6 -> direction
            0x8 -> 1
            else -> 0
        }
    }

    fun write(off: Int, value: Int) {
        when (off and 0xE) {
            0x4 -> {
                pinState = (pinState and direction.inv()) or (value and direction and 0xF)
                readPins()
            }
            0x6 -> direction = value and 0xF
            0x8 -> readable = (value and 1) != 0
        }
    }

    private fun output(pins: Int) {
        if (!readable) return
        pinState = (pinState and direction) or (pins and direction.inv() and 0xF)
    }

    private fun readPins() {
        if (hasRtc) rtcPins()
        if (hasGyro) gyroPins()
        if (hasRumble) rumble = (direction and 8) != 0 && (pinState and 8) != 0
        if (hasSolar) lightPins()
    }

    // ---------------------------------------------------------------- RTC

    private fun rtcPins() {
        when (rtcStep) {
            0 -> if ((pinState and 5) == 1) rtcStep = 1
            1 -> rtcStep = when {
                (pinState and 5) == 5 -> 2
                (pinState and 5) != 1 -> 0
                else -> 1
            }
            2 -> if ((pinState and 1) == 0) {
                rtcBits = (rtcBits and (1 shl rtcBitsRead).inv()) or (((pinState and 2) ushr 1) shl rtcBitsRead)
            } else if ((pinState and 4) != 0) {
                if ((rtcCommand and 0x80) == 0) {
                    rtcBitsRead++
                    if (rtcBitsRead == 8) rtcProcessByte()
                } else {
                    output(5 or (rtcOutput() shl 1))
                    rtcBitsRead++
                    if (rtcBitsRead == 8) {
                        rtcBytesLeft--
                        if (rtcBytesLeft <= 0) { rtcActive = false; rtcCommand = 0 }
                        rtcBitsRead = 0
                    }
                }
            } else {
                rtcBitsRead = 0; rtcBytesLeft = 0; rtcActive = false; rtcCommand = 0
                rtcStep = pinState and 1
                output(1)
            }
        }
    }

    private fun rtcProcessByte() {
        rtcBytesLeft--
        if (!rtcActive) {
            val cmd = rtcBits
            if ((cmd and 0xF) == 0x6) {
                rtcCommand = cmd
                rtcBytesLeft = RTC_BYTES[(cmd ushr 4) and 7]
                rtcActive = rtcBytesLeft > 0
                when ((cmd ushr 4) and 7) {
                    0 -> rtcControl = 0
                    2, 6 -> updateClock()
                }
            }
        } else if (((rtcCommand ushr 4) and 7) == 4) {
            rtcControl = rtcBits
        }
        rtcBits = 0
        rtcBitsRead = 0
        if (rtcBytesLeft <= 0) { rtcActive = false; rtcCommand = 0 }
    }

    private fun rtcOutput(): Int {
        if (!rtcActive) return 0
        val byte = when ((rtcCommand ushr 4) and 7) {
            4 -> rtcControl
            2, 6 -> rtcTime[(7 - rtcBytesLeft).coerceIn(0, 6)]
            else -> 0
        }
        return (byte ushr rtcBitsRead) and 1
    }

    private fun bcd(x: Int) = ((x / 10) shl 4) or (x % 10)

    private fun updateClock() {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        rtcTime[0] = bcd(c.get(Calendar.YEAR) % 100)
        rtcTime[1] = bcd(c.get(Calendar.MONTH) + 1)
        rtcTime[2] = bcd(c.get(Calendar.DAY_OF_MONTH))
        rtcTime[3] = bcd(c.get(Calendar.DAY_OF_WEEK) - 1)
        rtcTime[4] = if ((rtcControl and 0x40) != 0) bcd(hour) else bcd(hour % 12)
        rtcTime[5] = bcd(c.get(Calendar.MINUTE))
        rtcTime[6] = bcd(c.get(Calendar.SECOND))
    }

    // ---------------------------------------------------------------- sensor solar

    private fun lightPins() {
        if ((pinState and 4) != 0) return
        if ((pinState and 2) != 0) {
            lightCounter = 0
            lightSample = 0xFF - sensors.lux.coerceIn(0, 0xFF)
        }
        if ((pinState and 1) != 0 && lightEdge) lightCounter++
        lightEdge = (pinState and 1) == 0
        output(if (lightCounter >= lightSample) 8 else 0)
    }

    // ---------------------------------------------------------------- giroscopio

    private fun gyroPins() {
        if ((pinState and 1) != 0) {
            gyroSample = (0x6C0 + (sensors.gyroZ.coerceIn(-1f, 1f) * 0x500).toInt()) and 0xFFFF
        }
        if (gyroEdge && (pinState and 2) == 0) {
            val bit = (gyroSample ushr 15) and 1
            gyroSample = (gyroSample shl 1) and 0xFFFF
            output(bit shl 2)
        }
        gyroEdge = (pinState and 2) != 0
    }

    // ---------------------------------------------------------------- estados guardados

    internal fun save(o: DataOutputStream) {
        o.writeBoolean(readable); o.writeInt(direction); o.writeInt(pinState)
        o.writeInt(rtcStep); o.writeInt(rtcBits); o.writeInt(rtcBitsRead); o.writeInt(rtcBytesLeft)
        o.writeBoolean(rtcActive); o.writeInt(rtcCommand); o.writeInt(rtcControl); o.ints(rtcTime)
        o.writeInt(lightCounter); o.writeInt(lightSample); o.writeBoolean(lightEdge)
        o.writeInt(gyroSample); o.writeBoolean(gyroEdge)
    }

    internal fun load(i: DataInputStream) {
        readable = i.readBoolean(); direction = i.readInt(); pinState = i.readInt()
        rtcStep = i.readInt(); rtcBits = i.readInt(); rtcBitsRead = i.readInt(); rtcBytesLeft = i.readInt()
        rtcActive = i.readBoolean(); rtcCommand = i.readInt(); rtcControl = i.readInt(); i.ints(rtcTime)
        lightCounter = i.readInt(); lightSample = i.readInt(); lightEdge = i.readBoolean()
        gyroSample = i.readInt(); gyroEdge = i.readBoolean()
    }

    companion object {
        private val RTC_BYTES = intArrayOf(0, 0, 7, 0, 1, 0, 3, 0)
    }
}

/** Sensor de inclinación de 2 ejes mapeado en 0x0E008000 (Yoshi Topsy-Turvy, Koro Koro Puzzle). */
class TiltSensor(private val sensors: Sensors) {
    private var armed = false
    private var x = 0x3A0
    private var y = 0x3A0

    fun write(addr: Int, value: Int) {
        when (addr) {
            0x8000 -> armed = value == 0x55
            0x8100 -> if (armed && value == 0xAA) {
                armed = false
                x = (0x3A0 + (sensors.tiltX.coerceIn(-1f, 1f) * 0x200).toInt()) and 0xFFF
                y = (0x3A0 + (sensors.tiltY.coerceIn(-1f, 1f) * 0x200).toInt()) and 0xFFF
            }
        }
    }

    /** Devuelve -1 si la dirección no es del sensor. */
    fun read(addr: Int): Int = when (addr) {
        0x8200 -> x and 0xFF
        0x8300 -> ((x ushr 8) and 0xF) or 0x80
        0x8400 -> y and 0xFF
        0x8500 -> (y ushr 8) and 0xF
        else -> -1
    }

    internal fun save(o: DataOutputStream) { o.writeBoolean(armed); o.writeInt(x); o.writeInt(y) }
    internal fun load(i: DataInputStream) { armed = i.readBoolean(); x = i.readInt(); y = i.readInt() }
}

/** Qué hardware extra lleva el cartucho, según el código de juego y las cadenas de la ROM. */
internal object CartHardware {
    private val RTC = setOf("AXV", "AXP", "BPE", "U3I", "U32", "U33", "BKA", "BR4")
    private val SOLAR = setOf("U3I", "U32", "U33")
    private val GYRO = setOf("RZW")
    private val RUMBLE = setOf("RZW", "V49")
    private val TILT = setOf("KYG", "KHP")

    fun gpio(code: String, rom: ByteArray, sensors: Sensors): Gpio? {
        val c = code.take(3)
        val rtc = c in RTC || containsString(rom, "SIIRTC_V")
        val solar = c in SOLAR
        val gyro = c in GYRO
        val rumble = c in RUMBLE
        if (!rtc && !solar && !gyro && !rumble) return null
        return Gpio(sensors, rtc, solar, gyro, rumble)
    }

    fun tilt(code: String, sensors: Sensors): TiltSensor? =
        if (code.take(3) in TILT) TiltSensor(sensors) else null

    fun containsString(rom: ByteArray, s: String): Boolean {
        val p = s.toByteArray()
        var i = 0
        while (i + p.size <= rom.size) {
            if (rom[i] == p[0]) {
                var ok = true
                for (j in 1 until p.size) if (rom[i + j] != p[j]) { ok = false; break }
                if (ok) return true
            }
            i += 4
        }
        return false
    }
}
