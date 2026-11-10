package com.kgba.app

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*

/** The editor occupies the game display; controls keep their actual in-game dimensions. */
class ConsoleEditorActivity : Activity() {
    private lateinit var pad: GamepadView
    private lateinit var label: TextView
    private lateinit var size: SeekBar
    private lateinit var panel: ScrollView
    private var original = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.applyTheme(this)
        val ui = AppUi(this)
        original = savedInstanceState?.getString("original") ?: ConsoleStyle.load(this).encode()
        pad = GamepadView(this).apply {
            editing = true
            consoleStyle = ConsoleStyle.decode(savedInstanceState?.getString("draft") ?: original)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Emulator.BACKGROUND) }
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))
        panel = ScrollView(this).apply { setBackgroundColor(AppUi.BG) }
        val content = ui.column(8)
        val heading = ui.text("EDITAR CONSOLA", 15, true, AppUi.ACCENT)
        content.addView(heading)
        content.addView(ui.text("Arrastra un control. Gira el móvil para editar la otra orientación.", 12, color = AppUi.MUTED))
        label = ui.text("Selecciona un control", 13)
        content.addView(label)
        size = SeekBar(this).apply {
            max = 85; progress = 35; isEnabled = false
            contentDescription = "Tamaño del control seleccionado, de 65 a 150 por ciento"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        pad.resizeSelected((65 + progress) / 100f)
                        updateSelection()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        content.addView(size)
        pad.onSelection = { name, scale ->
            label.text = "$name · ${(scale * 100).toInt()} %"
            size.isEnabled = true
            size.progress = (scale * 100).toInt() - 65
        }
        fun row(first: String, second: String, left: () -> Unit, right: () -> Unit) {
            val row = LinearLayout(this)
            row.addView(ui.button(first, primary = first == "Guardar", action = left), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.button(second, action = right), LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(row)
        }
        row("Carcasa", "Botones", { chooseColor(true) }, { chooseColor(false) })
        row("Seleccionar", "Restablecer", {
            val names = pad.controlNames()
            AppDialog.Builder(this).setTitle("Selecciona un control")
                .setItems(names) { _, index -> pad.selectControl(names[index]) }.show()
        }, {
            AppDialog.Builder(this).setTitle("Restablecer esta orientación")
                .setMessage("Se restablecerán las posiciones y tamaños. Los colores y la otra orientación se conservarán.")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Restablecer") { _, _ ->
                    pad.resetCurrentLayout(); size.isEnabled = false; label.text = "Selecciona un control"
                }.show()
        })
        row("Guardar", "Cancelar", { pad.consoleStyle.save(this); finish() }, { discard() })
        panel.addView(content)
        root.addView(panel, FrameLayout.LayoutParams(1, 1))
        pad.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            heading.text = if (pad.height > pad.width) "CONSOLA · VERTICAL" else "CONSOLA · HORIZONTAL"
            val rect = Rect()
            Emulator.layoutScreen(pad.width, pad.height, 0, true, rect)
            // Request the second measure after this layout pass, not from inside it.
            panel.post {
                val current = panel.layoutParams as FrameLayout.LayoutParams
                if (current.width != rect.width() || current.height != rect.height() ||
                    current.leftMargin != rect.left || current.topMargin != rect.top) {
                    panel.layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                        leftMargin = rect.left; topMargin = rect.top
                        gravity = Gravity.TOP or Gravity.START
                    }
                }
            }
        }
        GameWindow.configure(window, root) { pad.refreshStyle(); pad.requestLayout() }
        setContentView(root)
    }

    private fun updateSelection() {
        pad.selectedControl?.let { pad.selectControl(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel.scrollTo(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", pad.consoleStyle.encode())
        outState.putString("original", original)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 5894
            }
        }
    }

    private fun chooseColor(shell: Boolean) {
        val names = arrayOf("Azul", "Morado", "Rojo", "Verde", "Rosa", "Grafito", "Marfil", "Color personalizado…")
        val colors = intArrayOf(0xFF2378D9.toInt(), 0xFF7752BD.toInt(), 0xFFC63746.toInt(),
            0xFF338978.toInt(), 0xFFE997B6.toInt(), 0xFF484B55.toInt(), 0xFFDADBD8.toInt())
        fun apply(color: Int) {
            if (shell) pad.consoleStyle.shell = color else pad.consoleStyle.buttons = color
            pad.refreshStyle()
        }
        AppDialog.Builder(this).setTitle(if (shell) "Color de la carcasa" else "Color de los botones")
            .setItems(names) { _, index ->
                if (index < colors.size) apply(colors[index]) else {
                    val input = EditText(this).apply {
                        setSingleLine(); hint = "#RRGGBB"
                        setText(String.format("#%06X", (if (shell) pad.consoleStyle.shell else pad.consoleStyle.buttons) and 0xFFFFFF))
                    }
                    val dialog = AppDialog.Builder(this).setTitle("Color hexadecimal")
                        .setView(input).setNegativeButton(R.string.cancel, null).setPositiveButton("Aplicar", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                            val value = input.text.toString().trim()
                            if (!Regex("#[0-9a-fA-F]{6}").matches(value)) input.error = "Usa un color como #2378D9"
                            else { apply(Color.parseColor(value)); dialog.dismiss() }
                        }
                    }
                    dialog.show()
                }
            }.show()
    }

    private fun discard() {
        if (pad.consoleStyle.encode() == original) { finish(); return }
        AppDialog.Builder(this).setTitle("¿Descartar los cambios?")
            .setNegativeButton("Seguir editando", null).setPositiveButton("Descartar") { _, _ -> finish() }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = discard()
}
