package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Ayudas para serializar el estado de la máquina (estados guardados). */
internal fun DataOutputStream.ints(a: IntArray) { writeInt(a.size); for (x in a) writeInt(x) }
internal fun DataOutputStream.bytes(a: ByteArray) { writeInt(a.size); write(a) }
internal fun DataOutputStream.bools(a: BooleanArray) { writeInt(a.size); for (x in a) writeBoolean(x) }

internal fun DataInputStream.ints(a: IntArray) {
    val n = readInt(); require(n == a.size) { "estado incompatible" }
    for (i in a.indices) a[i] = readInt()
}

internal fun DataInputStream.bytes(a: ByteArray) {
    val n = readInt(); require(n == a.size) { "estado incompatible" }
    readFully(a)
}

internal fun DataInputStream.bools(a: BooleanArray) {
    val n = readInt(); require(n == a.size) { "estado incompatible" }
    for (i in a.indices) a[i] = readBoolean()
}
