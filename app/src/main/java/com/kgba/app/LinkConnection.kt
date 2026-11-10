package com.kgba.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.kgba.core.LinkPort
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Cable link sobre un flujo de bytes (socket TCP o Bluetooth RFCOMM).
 * Un hilo lee los mensajes y los deja en una cola; otro escribe para no bloquear la emulación.
 *
 * Protocolo: saludo (magic, versión, código de juego, nombre) y luego mensajes de 6 bytes:
 * tipo (1), modo (1), dato (4).
 */
class LinkConnection(
    input: InputStream,
    output: OutputStream,
    override val isParent: Boolean,
    private val closer: Closeable,
    gameCode: String,
    myName: String,
) : LinkPort {
    companion object {
        private const val MAGIC = 0x4B4C4E4B // "KLNK"
        private const val VERSION = 1
        private const val BYE = 0x7F
        const val TCP_PORT = 5738
        const val DISCOVERY_PORT = 5739
        val BT_UUID: UUID = UUID.fromString("6b676261-6c69-6e6b-8000-00805f9b34fb")
    }

    @Volatile override var connected = true
        private set
    /** Se llama (desde un hilo de red) cuando se corta la conexión. */
    @Volatile var onClosed: (() -> Unit)? = null

    val peerName: String
    val peerGame: String

    private val din = DataInputStream(BufferedInputStream(input))
    private val dout = DataOutputStream(BufferedOutputStream(output))
    private val inbox = ConcurrentLinkedQueue<Long>()
    private val outbox = LinkedBlockingQueue<Long>()

    init {
        // Saludo en los dos sentidos (lo hacen los dos lados a la vez, sin esperar)
        dout.writeInt(MAGIC); dout.writeInt(VERSION); dout.writeUTF(gameCode); dout.writeUTF(myName); dout.flush()
        require(din.readInt() == MAGIC) { "la otra parte no es KGBA" }
        require(din.readInt() == VERSION) { "versión de KGBA distinta en el otro móvil" }
        peerGame = din.readUTF()
        peerName = din.readUTF()
        Thread(::readLoop, "KGBA-link-in").apply { isDaemon = true }.start()
        Thread(::writeLoop, "KGBA-link-out").apply { isDaemon = true }.start()
    }

    override fun send(type: Int, mode: Int, data: Int) {
        if (connected) outbox.add(com.kgba.core.Sio.encode(type, mode, data))
    }

    override fun poll(): Long = inbox.poll() ?: -1L

    private fun readLoop() {
        try {
            while (connected) {
                val type = din.readUnsignedByte()
                if (type == BYE) break
                val mode = din.readUnsignedByte()
                val data = din.readInt()
                inbox.add(com.kgba.core.Sio.encode(type, mode, data))
            }
        } catch (e: Exception) {
            if (connected) Log.i("KGBA", "link cerrado: $e")
        }
        close()
    }

    private fun writeLoop() {
        try {
            while (connected) {
                val m = outbox.poll(250, TimeUnit.MILLISECONDS) ?: continue
                synchronized(dout) {
                    write(m)
                    // agrupa lo que haya pendiente en un solo envío
                    while (true) write(outbox.poll() ?: break)
                    dout.flush()
                }
            }
        } catch (e: Exception) {
            if (connected) Log.i("KGBA", "link cerrado al escribir: $e")
        }
        close()
    }

    private fun write(m: Long) {
        dout.writeByte((m ushr 40).toInt())
        dout.writeByte(((m ushr 32) and 0xFF).toInt())
        dout.writeInt(m.toInt())
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        try { synchronized(dout) { dout.writeByte(BYE); dout.flush() } } catch (_: Exception) {}
        try { closer.close() } catch (_: Exception) {}
        onClosed?.invoke()
    }
}

/** Crear y unirse a partidas por red local (TCP) y Bluetooth. Todo bloquea: usar en un hilo. */
object LinkNet {
    /** Algo que se puede cancelar mientras se espera una conexión. */
    class Waiter { @Volatile var cancelled = false; @Volatile var closeable: Closeable? = null
        fun cancel() { cancelled = true; try { closeable?.close() } catch (_: Exception) {} }
    }

    /** Direcciones IPv4 de este móvil en la red local (Wi-Fi, zona Wi-Fi, Ethernet). */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    /** Espera a un jugador por TCP anunciando la partida por difusión UDP. */
    fun hostTcp(w: Waiter, gameCode: String, name: String): LinkConnection {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(LinkConnection.TCP_PORT))
        w.closeable = server
        val beacon = Thread {
            try {
                DatagramSocket().use { ds ->
                    ds.broadcast = true
                    val msg = "KGBA|$gameCode|$name".toByteArray()
                    while (!w.cancelled && !server.isClosed) {
                        for (target in broadcastTargets()) {
                            try { ds.send(DatagramPacket(msg, msg.size, target, LinkConnection.DISCOVERY_PORT)) } catch (_: Exception) {}
                        }
                        Thread.sleep(1000)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        try {
            val s = server.accept()
            s.tcpNoDelay = true
            return LinkConnection(s.getInputStream(), s.getOutputStream(), true, s, gameCode, name)
        } finally {
            server.close()
            beacon.interrupt()
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            }
        } catch (_: Exception) {}
        out += InetAddress.getByName("255.255.255.255")
        return out
    }

    /** Partida encontrada en la red local. */
    data class Found(val address: String, val game: String, val name: String)

    /** Escucha los anuncios de partidas durante el tiempo que no se cancele. */
    fun discover(w: Waiter, onFound: (Found) -> Unit) {
        DatagramSocket(null).use { ds ->
            ds.reuseAddress = true
            ds.broadcast = true
            ds.bind(InetSocketAddress(LinkConnection.DISCOVERY_PORT))
            ds.soTimeout = 500
            w.closeable = ds
            val buf = ByteArray(256)
            val seen = HashSet<String>()
            while (!w.cancelled) {
                val p = DatagramPacket(buf, buf.size)
                try { ds.receive(p) } catch (_: SocketTimeoutException) { continue }
                val parts = String(p.data, 0, p.length).split('|')
                if (parts.size < 3 || parts[0] != "KGBA") continue
                val addr = p.address.hostAddress ?: continue
                if (seen.add(addr)) onFound(Found(addr, parts[1], parts[2]))
            }
        }
    }

    fun joinTcp(w: Waiter, address: String, gameCode: String, name: String): LinkConnection {
        val s = Socket()
        w.closeable = s
        s.connect(InetSocketAddress(address, LinkConnection.TCP_PORT), 8000)
        s.tcpNoDelay = true
        return LinkConnection(s.getInputStream(), s.getOutputStream(), false, s, gameCode, name)
    }

    @SuppressLint("MissingPermission")
    fun hostBluetooth(w: Waiter, adapter: BluetoothAdapter, gameCode: String, name: String): LinkConnection {
        val server: BluetoothServerSocket = adapter.listenUsingRfcommWithServiceRecord("KGBA link", LinkConnection.BT_UUID)
        w.closeable = server
        try {
            val s = server.accept()
            return LinkConnection(s.inputStream, s.outputStream, true, s, gameCode, name)
        } finally {
            server.close()
        }
    }

    @SuppressLint("MissingPermission")
    fun joinBluetooth(w: Waiter, adapter: BluetoothAdapter, device: BluetoothDevice, gameCode: String, name: String): LinkConnection {
        adapter.cancelDiscovery()
        val s: BluetoothSocket = device.createRfcommSocketToServiceRecord(LinkConnection.BT_UUID)
        w.closeable = s
        s.connect()
        return LinkConnection(s.inputStream, s.outputStream, false, s, gameCode, name)
    }
}
