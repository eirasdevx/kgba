package com.kgba.app

import android.content.SharedPreferences
import android.view.KeyEvent
import com.kgba.core.Button

/**
 * Asignación de teclas del mando físico / teclado a botones de la GBA y a atajos.
 * Se guarda en las preferencias como "código:acción,código:acción".
 */
class Keymap(private val prefs: SharedPreferences) {
    companion object {
        // Acciones 0..9: bit del botón de la GBA. A partir de 100: atajos.
        const val QUICK_SAVE = 100
        const val QUICK_LOAD = 101
        const val FAST_FORWARD = 102
        const val REWIND = 103
        const val MENU = 104

        /** Acciones que se pueden asignar, con su nombre para la interfaz. */
        val ACTIONS = listOf(
            bit(Button.A) to "A", bit(Button.B) to "B", bit(Button.L) to "L", bit(Button.R) to "R",
            bit(Button.START) to "Start", bit(Button.SELECT) to "Select",
            bit(Button.UP) to "Arriba", bit(Button.DOWN) to "Abajo",
            bit(Button.LEFT) to "Izquierda", bit(Button.RIGHT) to "Derecha",
            QUICK_SAVE to "Guardado rápido", QUICK_LOAD to "Carga rápida",
            FAST_FORWARD to "Avance rápido", REWIND to "Rebobinar (mantener)", MENU to "Menú",
        )

        private fun bit(mask: Int) = Integer.numberOfTrailingZeros(mask)

        private val DEFAULT: Map<Int, Int> = buildMap {
            fun bind(action: Int, vararg codes: Int) { for (c in codes) put(c, action) }
            bind(bit(Button.UP), KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W)
            bind(bit(Button.DOWN), KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S)
            bind(bit(Button.LEFT), KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A)
            bind(bit(Button.RIGHT), KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D)
            bind(bit(Button.A), KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_X)
            bind(bit(Button.B), KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_Z)
            bind(bit(Button.L), KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q)
            bind(bit(Button.R), KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_E)
            bind(bit(Button.START), KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER)
            bind(bit(Button.SELECT), KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL)
            bind(QUICK_SAVE, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_F5)
            bind(QUICK_LOAD, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_F8)
            bind(FAST_FORWARD, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_TAB)
            bind(REWIND, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_R)
            bind(MENU, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_ESCAPE)
        }
    }

    private val map = HashMap<Int, Int>()

    init { load() }

    private fun load() {
        map.clear()
        val s = prefs.getString("keymap", null)
        if (s == null) { map.putAll(DEFAULT); return }
        for (part in s.split(',')) {
            val code = part.substringBefore(':').toIntOrNull() ?: continue
            val action = part.substringAfter(':').toIntOrNull() ?: continue
            map[code] = action
        }
    }

    private fun store() {
        prefs.edit().putString("keymap", map.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    /** Acción asignada a la tecla, o -1. */
    fun action(code: Int): Int = map[code] ?: -1

    /** Asigna [code] a [action] (una tecla solo puede tener una acción). */
    fun assign(code: Int, action: Int) { map[code] = action; store() }

    fun clear(action: Int) { map.entries.removeAll { it.value == action }; store() }

    fun reset() { map.clear(); map.putAll(DEFAULT); store() }

    /** Nombres de las teclas asignadas a [action], para mostrarlas. */
    fun describe(action: Int): String =
        map.filterValues { it == action }.keys.sorted().joinToString(", ") {
            KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_").removePrefix("BUTTON_")
        }.ifEmpty { "—" }
}
