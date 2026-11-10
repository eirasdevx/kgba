package com.kgba.core

/**
 * Conexión del cable link con otra consola (la implementa el frontend: Wi-Fi, Bluetooth...).
 * Los métodos se llaman desde el hilo de emulación y no deben bloquear.
 */
interface LinkPort {
    val connected: Boolean
    /** true en la consola "padre" (jugador 1, la que inicia las transferencias multijugador). */
    val isParent: Boolean
    /** Envía un mensaje ([Sio.MSG_TRANSFER] o [Sio.MSG_REPLY]) a la otra consola. */
    fun send(type: Int, mode: Int, data: Int)
    /** Siguiente mensaje recibido, codificado con [Sio.encode], o -1 si no hay ninguno. */
    fun poll(): Long
}

/**
 * Puerto serie (SIOCNT, SIODATA, SIOMULTI, RCNT) con cable link para 2 consolas.
 *
 * Modelo: la consola que inicia la transferencia (el padre en multijugador, o la de reloj
 * interno en modo normal) envía su dato; la otra lo recibe entre dos líneas de pantalla,
 * responde con el suyo y completa su transferencia; la primera completa la suya al recibir
 * la respuesta. El orden de los datos es el del hardware; la latencia de la red solo hace
 * que cada transferencia tarde más (los juegos esperan la interrupción de fin de transferencia).
 */
class Sio(private val gba: Gba) {
    companion object {
        const val MSG_TRANSFER = 2
        const val MSG_REPLY = 3

        const val MODE_NORMAL8 = 0
        const val MODE_NORMAL32 = 1
        const val MODE_MULTI = 2

        fun encode(type: Int, mode: Int, data: Int): Long =
            (type.toLong() shl 40) or ((mode.toLong() and 0xFF) shl 32) or (data.toLong() and 0xFFFFFFFFL)
    }

    var link: LinkPort? = null

    /** Esperando la respuesta de la otra consola. */
    private var pending = false
    private var pendingMode = 0
    private var sent = 0

    private val io get() = gba.io

    private fun mode(cnt: Int = io.u16(0x128)): Int {
        if ((io.u16(0x134) and 0x8000) != 0) return -1 // RCNT en modo GPIO / JOY Bus
        return (cnt ushr 12) and 3
    }

    private fun linked(): LinkPort? = link?.takeIf { it.connected }

    fun read(off: Int): Int {
        if (off != 0x128) return io.u16(off)
        var v = io.u16(0x128)
        if (mode(v) == MODE_MULTI) {
            val l = linked()
            v = v and 0x000C.inv()
            if (l != null) {
                if (!l.isParent) v = v or 0x4 // SI: 1 = hijo
                v = v or 0x8                  // SD: todas las consolas listas
            }
        }
        return v
    }

    fun writeCnt(value: Int) {
        val old = io.u16(0x128)
        var v = value
        val m = mode(v)
        if (m == MODE_MULTI) v = (v and 0x007C.inv()) or (old and 0x0070) // SI, SD, ID y error son de solo lectura
        val l = linked()
        val start = (v and 0x80) != 0
        if (l == null) {
            if (start) completeAlone(m, v).also { v = it }
            io.w16(0x128, v)
            return
        }
        when (m) {
            MODE_MULTI -> {
                if (start && (old and 0x80) == 0) {
                    if (l.isParent) {
                        if (!pending) begin(l, m, io.u16(0x12A))
                    } else v = v and 0x80.inv() // el hijo no puede iniciar
                }
            }
            MODE_NORMAL8, MODE_NORMAL32 -> {
                // Con reloj interno somos quien manda; con reloj externo esperamos a la otra consola
                if (start && (old and 0x80) == 0 && (v and 1) != 0 && !pending) {
                    begin(l, m, if (m == MODE_NORMAL32) io.u16(0x120) or (io.u16(0x122) shl 16) else io.u16(0x12A) and 0xFF)
                }
            }
            else -> if (start) v = completeAlone(m, v)
        }
        io.w16(0x128, v)
    }

    private fun begin(l: LinkPort, m: Int, data: Int) {
        pending = true
        pendingMode = m
        sent = data
        l.send(MSG_TRANSFER, m, data)
    }

    /** Sin cable: la transferencia termina al instante y no hay nadie al otro lado. */
    private fun completeAlone(m: Int, cnt: Int): Int {
        var v = cnt
        when (m) {
            MODE_MULTI -> {
                io.w16(0x120, io.u16(0x12A))
                io.w16(0x122, 0xFFFF); io.w16(0x124, 0xFFFF); io.w16(0x126, 0xFFFF)
                v = v and 0x0030.inv()
            }
            MODE_NORMAL32 -> if ((v and 1) != 0) { io.w16(0x120, 0xFFFF); io.w16(0x122, 0xFFFF) }
            MODE_NORMAL8 -> if ((v and 1) != 0) io.w16(0x12A, (io.u16(0x12A) and 0xFF00) or 0xFF)
        }
        if (m == MODE_NORMAL8 || m == MODE_NORMAL32) {
            if ((v and 1) == 0) return v // reloj externo sin cable: nunca termina, como en la consola
        }
        v = v and 0x80.inv()
        if ((v and 0x4000) != 0) gba.requestIrq(Irq.SERIAL)
        return v
    }

    /** Procesa los mensajes recibidos. Se llama una vez por línea de pantalla. */
    fun poll() {
        val l = link ?: return
        if (!l.connected) {
            if (pending) finish(pendingMode, 0xFFFFFFFF.toInt(), sent) // la otra consola se ha ido
            return
        }
        while (true) {
            val msg = l.poll()
            if (msg < 0) return
            val type = (msg ushr 40).toInt()
            val m = ((msg ushr 32) and 0xFF).toInt()
            val data = msg.toInt()
            when (type) {
                MSG_TRANSFER -> receive(l, m, data)
                MSG_REPLY -> if (pending) finish(pendingMode, data, sent)
            }
        }
    }

    /** La otra consola ha iniciado una transferencia: respondemos con nuestro dato. */
    private fun receive(l: LinkPort, m: Int, data: Int) {
        var cnt = io.u16(0x128)
        val ours = mode(cnt)
        when {
            m == MODE_MULTI -> {
                val mine = io.u16(0x12A)
                l.send(MSG_REPLY, m, mine)
                if (ours != MODE_MULTI) return
                io.w16(0x120, data and 0xFFFF)
                io.w16(0x122, mine)
                io.w16(0x124, 0xFFFF); io.w16(0x126, 0xFFFF)
                cnt = (cnt and 0x0070.inv()) or 0x10 // somos la consola 1
            }
            m == MODE_NORMAL32 -> {
                val mine = io.u16(0x120) or (io.u16(0x122) shl 16)
                l.send(MSG_REPLY, m, mine)
                if (ours != MODE_NORMAL32 && ours != MODE_NORMAL8) return
                io.w16(0x120, data and 0xFFFF); io.w16(0x122, data ushr 16)
                if ((cnt and 0x80) == 0) { io.w16(0x128, cnt); return }
            }
            else -> {
                val mine = io.u16(0x12A) and 0xFF
                l.send(MSG_REPLY, m, mine)
                if (ours != MODE_NORMAL32 && ours != MODE_NORMAL8) return
                io.w16(0x12A, (io.u16(0x12A) and 0xFF00) or (data and 0xFF))
                if ((cnt and 0x80) == 0) { io.w16(0x128, cnt); return }
            }
        }
        cnt = cnt and 0x80.inv()
        io.w16(0x128, cnt)
        if ((cnt and 0x4000) != 0) gba.requestIrq(Irq.SERIAL)
    }

    /** Llega la respuesta a nuestra transferencia. */
    private fun finish(m: Int, data: Int, mine: Int) {
        pending = false
        var cnt = io.u16(0x128)
        when (m) {
            MODE_MULTI -> {
                io.w16(0x120, mine and 0xFFFF)
                io.w16(0x122, data and 0xFFFF)
                io.w16(0x124, 0xFFFF); io.w16(0x126, 0xFFFF)
                cnt = cnt and 0x0070.inv() // somos la consola 0
            }
            MODE_NORMAL32 -> { io.w16(0x120, data and 0xFFFF); io.w16(0x122, data ushr 16) }
            else -> io.w16(0x12A, (io.u16(0x12A) and 0xFF00) or (data and 0xFF))
        }
        cnt = cnt and 0x80.inv()
        io.w16(0x128, cnt)
        if ((cnt and 0x4000) != 0) gba.requestIrq(Irq.SERIAL)
    }
}
