package com.kgba.app

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.log10

class EmulatorActivity : Activity(), SensorEventListener {
    private var emu: Emulator? = null
    private lateinit var pad: GamepadView
    private lateinit var surface: SurfaceView
    private var romId = ""
    private var dialogs = 0
    private var axisButtons = 0
    private var keyButtons = 0
    private var topInset = 0
    private lateinit var bootView: BootView
    private var bootFinished = false
    private var booting = true
    private var activityResumed = false
    private var slot = 1
    private val prefs by lazy { getSharedPreferences("kgba", MODE_PRIVATE) }
    private val keymap by lazy { Keymap(prefs) }
    private var link: LinkConnection? = null
    private var pendingBt: (() -> Unit)? = null

    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var tiltBaseX = Float.NaN
    private var tiltBaseY = 0f
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.applyTheme(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        pad = GamepadView(this)
        pad.showButtons = prefs.getBoolean("pad", true)
        pad.landscapeAlpha = prefs.getInt("alpha", 60) / 100f
        pad.skin = prefs.getBoolean("skin", true)
        val root = FrameLayout(this)
        root.setBackgroundColor(Emulator.BACKGROUND)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))
        GameWindow.configure(window, root) { pad.refreshStyle(); pad.requestLayout() }
        bootView = BootView(this, { pad.skin }, { prefs.getBoolean("sound", true) }) { bootFinished = true; finishBoot() }
        root.addView(bootView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()

        val uri = intent.data
        if (uri == null) { finish(); return }

        // La ROM puede ocupar 32 MB: se lee fuera del hilo de la interfaz
        Thread {
            try {
                val rom = RomStore.read(this, uri)
                // La biblioteca y el gestor de guardados relacionan así cada ROM con sus estados.
                RomStore.remember(this, uri.toString(), rom.id, RomStore.displayName(this, uri))
                val e = Emulator(rom.data, RomStore.bios(this), RomStore.saveFile(this, rom.id))
                runOnUiThread { start(e, rom.id) }
            } catch (ex: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.rom_error, ex.message ?: ex.toString()), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun start(e: Emulator, id: String) {
        if (isFinishing || isDestroyed) return
        romId = id
        emu = e
        slot = prefs.getInt("slot_$id", 1).coerceIn(1, RomStore.SLOTS)
        pad.slot = slot
        e.topInset = topInset
        e.skin = pad.skin
        e.soundOn = prefs.getBoolean("sound", true)
        e.ffSpeed = prefs.getInt("ffspeed", 3)
        e.rewindEnabled = prefs.getBoolean("rewind", true)
        e.filter = prefs.getInt("filter", 0)
        e.lcdColors = prefs.getBoolean("lcdcolors", false)
        e.onRumble = { on -> rumble(on) }
        pad.onButtons = { e.touchButtons = it }
        pad.onRewind = { e.rewinding = it }
        pad.onAction = { a ->
            when (a) {
                GamepadView.ACT_MENU -> showMenu()
                GamepadView.ACT_SAVE -> quickSave()
                GamepadView.ACT_LOAD -> quickLoad()
                GamepadView.ACT_SLOT -> slotDialog(SlotMode.PICK)
                GamepadView.ACT_FF -> toggleFastForward()
            }
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = e.setSurface(holder, w, h)
            override fun surfaceDestroyed(holder: SurfaceHolder) = e.setSurface(null, 0, 0)
        })
        if (surface.holder.surface?.isValid == true) e.setSurface(surface.holder, surface.width, surface.height)
        updateSolar()
        e.pauseEmu()
        e.start()
        // Abierto desde «Guardados»: carga directamente la ranura elegida.
        intent.getIntExtra(EXTRA_LOAD_SLOT, 0).takeIf { it in 1..RomStore.SLOTS }?.let { n ->
            intent.removeExtra(EXTRA_LOAD_SLOT)
            setSlot(n); loadSlot(n)
        }
        finishBoot()
        registerSensors()
        if (booting || !activityResumed || dialogs > 0 || !hasWindowFocus()) e.pauseEmu()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (!booting && activityResumed && dialogs == 0) emu?.resumeEmu()
        } else emu?.pauseEmu()
    }

    override fun onPause() {
        super.onPause()
        activityResumed = false
        bootView.pause()
        pad.releaseInputs()
        keyButtons = 0; axisButtons = 0; updateHw()
        emu?.rewinding = false
        emu?.pauseEmu()
        sensorManager.unregisterListener(this)
        vibrator?.cancel()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        pad.consoleStyle = ConsoleStyle.load(this)
        if (SkinPack.activeName(this) != pad.packName) pad.reloadPack() else pad.refreshStyle()
        bootView.resume()
        registerSensors()
        if (!booting && dialogs == 0 && hasWindowFocus()) emu?.resumeEmu()
    }

    private fun finishBoot() {
        if (!bootFinished || emu == null || !booting || isFinishing || isDestroyed) return
        booting = false
        (bootView.parent as? ViewGroup)?.removeView(bootView)
        if (activityResumed && hasWindowFocus() && dialogs == 0) emu?.resumeEmu()
    }

    override fun onDestroy() {
        link?.onClosed = null
        link?.close()
        emu?.shutdown()
        emu?.join(2000)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (booting) finish() else showMenu() }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---------------------------------------------------------------- diálogos (pausan el juego)

    private fun show(b: AppDialog.Builder, onDismiss: (() -> Unit)? = null): AppDialog {
        dialogs++
        pad.releaseInputs()
        keyButtons = 0; axisButtons = 0; updateHw()
        emu?.rewinding = false
        emu?.pauseEmu()
        b.setOnDismissListener {
            onDismiss?.invoke()
            dialogs--
            if (dialogs == 0) {
                hideSystemBars()
                if (!isFinishing && !booting && activityResumed && hasWindowFocus()) emu?.resumeEmu()
            }
        }
        return b.show()
    }

    private fun showMenu() {
        val e = emu ?: return
        if (dialogs > 0 || booting) return
        val gpio = e.gba.gpio
        val hasTilt = e.gba.tilt != null || gpio?.hasGyro == true
        val filters = filterNames()
        val speed = if (e.ffSpeed <= 0) getString(R.string.unlimited) else "×${e.ffSpeed}"
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val groups = linkedMapOf<String, MutableList<Pair<String, () -> Unit>>>()
        fun group(name: String) {
            if (items.isNotEmpty()) { groups[name] = items.toMutableList(); items.clear() }
        }
        fun check(on: Boolean) = if (on) "  ✓" else ""
        items += getString(R.string.resume) to {}
        items += getString(R.string.save_state) to { slotDialog(SlotMode.SAVE) }
        items += getString(R.string.load_state) to { slotDialog(SlotMode.LOAD) }
        group("Partida")
        items += getString(R.string.fast_forward) + check(e.fastForward) to { toggleFastForward() }
        items += getString(R.string.ff_speed, speed) to { speedDialog() }
        items += getString(R.string.rewind) + check(e.rewindEnabled) to {
            e.rewindEnabled = !e.rewindEnabled; prefs.edit().putBoolean("rewind", e.rewindEnabled).apply()
        }
        group("Velocidad y rebobinado")
        items += getString(R.string.filter, filters[e.filter.coerceIn(0, 3)]) to { filterDialog() }
        items += getString(R.string.lcd_colors) + check(e.lcdColors) to {
            e.lcdColors = !e.lcdColors; prefs.edit().putBoolean("lcdcolors", e.lcdColors).apply()
            e.post { } // repinta
        }
        items += getString(R.string.screenshot) to { screenshot() }
        items += getString(R.string.toggle_sound) + check(e.soundOn) to {
            e.soundOn = !e.soundOn; prefs.edit().putBoolean("sound", e.soundOn).apply()
        }
        group("Imagen y sonido")
        items += "Personalizar consola" to { startActivity(Intent(this, ConsoleEditorActivity::class.java)) }
        items += getString(R.string.skin) + check(pad.skin) to {
            pad.skin = !pad.skin
            e.skin = pad.skin
            prefs.edit().putBoolean("skin", pad.skin).apply()
        }
        items += getString(R.string.toggle_pad) + check(pad.showButtons) to {
            pad.showButtons = !pad.showButtons
            prefs.edit().putBoolean("pad", pad.showButtons).apply()
            if (!pad.showButtons) e.touchButtons = 0
        }
        items += getString(R.string.opacity, prefs.getInt("alpha", 60)) to { opacityDialog() }
        items += getString(R.string.remap) to { remapDialog() }
        group("Controles")
        items += getString(R.string.link_menu, linkStatus()) to { linkDialog() }
        if (gpio?.hasSolar == true) items += getString(R.string.solar, solarName()) to { solarDialog() }
        if (hasTilt) items += getString(R.string.recalibrate) to { tiltBaseX = Float.NaN }
        group("Conexiones y sensores")
        items += getString(R.string.reset) to {
            show(AppDialog.Builder(this).setTitle(R.string.reset)
                .setMessage("Se perderá el progreso que no hayas guardado. ¿Reiniciar el juego?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset) { _, _ -> e.reset() })
        }
        items += getString(R.string.exit) to { finish() }

        group("Consola")
        showPausePanel(intent.data?.let { CoverStore.officialTitle(this, it.toString()) }
            ?: e.gba.gameTitle.ifEmpty { getString(R.string.menu_title) }, groups)
    }

    private fun showPausePanel(title: String, groups: Map<String, List<Pair<String, () -> Unit>>>) {
        val ui = AppUi(this)
        val body = ui.column()
        lateinit var dialog: AppDialog
        val descriptions = mapOf(
            "Velocidad y rebobinado" to ("Avance rápido y retroceso" to "»"),
            "Imagen y sonido" to ("Filtros, colores, capturas y audio" to "▣"),
            "Controles" to ("Carcasa, botones y mando físico" to "✥"),
            "Conexiones y sensores" to ("Multijugador y cartuchos especiales" to "⇄"),
            "Consola" to ("Reiniciar o volver a la biblioteca" to "↺")
        )
        groups["Partida"]?.let { actions ->
            val resume = actions.first()
            body.addView(ui.button("▶  ${resume.first}", true) { resume.second(); dialog.dismiss() })
            val quick = LinearLayout(this)
            actions.drop(1).forEach { (label, action) ->
                quick.addView(ui.button(label) { action(); dialog.dismiss() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, ui.dp(6), ui.dp(4), ui.dp(6)) })
            }
            body.addView(quick)
        }
        body.addView(ui.section("OPCIONES DEL JUEGO"))
        body.addView(ui.row("Personalizar consola", "Colores, posiciones y tamaño", "✎") {
            startActivity(Intent(this, ConsoleEditorActivity::class.java))
            dialog.dismiss()
        })
        groups.filterKeys { it != "Partida" }.forEach { (category, actions) ->
            val (subtitle, icon) = descriptions[category] ?: ("" to "›")
            body.addView(ui.row(category, subtitle, icon) {
                showPauseCategory(category, actions)
                dialog.dismiss()
            })
        }
        dialog = show(AppDialog.Builder(this).setTitle(title).setView(body)
            .setNegativeButton("Volver al juego", null))
    }

    private fun showPauseCategory(title: String, actions: List<Pair<String, () -> Unit>>) {
        var backToMenu = false
        show(AppDialog.Builder(this).setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Volver al menú") { _, _ -> backToMenu = true }) {
                if (backToMenu) window.decorView.post { showMenu() }
            }
    }

    private fun filterNames() = arrayOf(
        getString(R.string.filter_sharp), getString(R.string.filter_smooth),
        getString(R.string.filter_scanlines), getString(R.string.filter_lcd),
    )

    private fun filterDialog() {
        val e = emu ?: return
        show(AppDialog.Builder(this).setTitle("Filtro de imagen").setSingleChoiceItems(filterNames(), e.filter) { d, which ->
            e.filter = which; prefs.edit().putInt("filter", which).apply(); d.dismiss()
        })
    }

    private fun speedDialog() {
        val e = emu ?: return
        val values = intArrayOf(2, 3, 4, 6, 0)
        val names = values.map { if (it == 0) getString(R.string.unlimited) else "×$it" }.toTypedArray()
        show(AppDialog.Builder(this).setTitle("Velocidad de avance rápido").setSingleChoiceItems(names, values.indexOf(e.ffSpeed)) { d, which ->
            e.ffSpeed = values[which]; prefs.edit().putInt("ffspeed", values[which]).apply(); d.dismiss()
        })
    }

    private fun opacityDialog() {
        val values = intArrayOf(20, 40, 60, 80, 100)
        val names = values.map { "$it %" }.toTypedArray()
        show(AppDialog.Builder(this).setTitle("Opacidad de los controles").setSingleChoiceItems(names, values.indexOf(prefs.getInt("alpha", 60))) { d, which ->
            prefs.edit().putInt("alpha", values[which]).apply()
            pad.landscapeAlpha = values[which] / 100f
            d.dismiss()
        })
    }

    private fun toggleFastForward() {
        val e = emu ?: return
        e.fastForward = !e.fastForward
        pad.fastForward = e.fastForward
    }

    // ---------------------------------------------------------------- estados guardados

    private enum class SlotMode { SAVE, LOAD, PICK }

    private fun quickSave() = saveSlot(slot)
    private fun quickLoad() = loadSlot(slot)

    private fun setSlot(n: Int) {
        slot = n
        pad.slot = n
        prefs.edit().putInt("slot_$romId", n).apply()
    }

    private fun saveSlot(n: Int) {
        val e = emu ?: return
        e.saveStateTo(RomStore.stateFile(this, romId, n), RomStore.thumbFile(this, romId, n)) { err ->
            runOnUiThread {
                if (err == null) toast(getString(R.string.state_saved, n)) else toast(getString(R.string.state_error, err), true)
            }
        }
    }

    private fun loadSlot(n: Int) {
        val e = emu ?: return
        val file = RomStore.stateFile(this, romId, n)
        if (!file.isFile) { toast(getString(R.string.state_missing, n)); return }
        e.loadStateFrom(file) { err ->
            runOnUiThread {
                if (err == null) toast(getString(R.string.state_loaded, n)) else toast(getString(R.string.state_error, err), true)
            }
        }
    }

    private fun slotDialog(mode: SlotMode) {
        if (emu == null) return
        val ui = AppUi(this)
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val body = ui.column()
        body.addView(ui.text(when (mode) {
            SlotMode.SAVE -> "Elige dónde guardar tu progreso."
            SlotMode.LOAD -> "Continúa desde una partida guardada."
            SlotMode.PICK -> "Elige la ranura para guardar y cargar rápidamente."
        }, 14, color = AppUi.MUTED).apply { setPadding(0, 0, 0, ui.dp(16)) })
        lateinit var dialog: AppDialog
        for (n in 1..RomStore.SLOTS) {
            val state = RomStore.stateFile(this, romId, n)
            val thumb = RomStore.thumbFile(this, romId, n)
            val occupied = state.isFile
            val selected = n == slot
            val enabled = mode != SlotMode.LOAD || occupied
            val row = ui.line().apply {
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                minimumHeight = ui.dp(88)
                background = ui.ripple(ui.shape(if (selected) AppUi.SURFACE_HIGH else AppUi.PANEL,
                    18, if (selected) AppUi.PRIMARY else AppUi.BORDER))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
                isSelected = selected
                isEnabled = enabled
                isFocusable = enabled
                alpha = if (enabled) 1f else 0.55f
            }
            val preview = FrameLayout(this).apply {
                background = ui.shape(AppUi.SURFACE_LOWEST, 10, AppUi.BORDER)
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val bitmap = if (occupied && thumb.isFile) BitmapFactory.decodeFile(thumb.path) else null
            if (bitmap != null) preview.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
                drawable?.isFilterBitmap = false
            }, FrameLayout.LayoutParams(-1, -1))
            else preview.addView(ui.icon(if (occupied) R.drawable.ic_nav_saves else R.drawable.ic_add,
                AppUi.OUTLINE, 24), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            row.addView(preview, LinearLayout.LayoutParams(ui.dp(84), ui.dp(56)))
            val labels = ui.column().apply {
                addView(ui.text(getString(R.string.slot_name, n), 16, true))
                if (selected) addView(ui.text("ACTUAL", 10, true, AppUi.PRIMARY))
                addView(ui.text(if (occupied) fmt.format(Date(state.lastModified())) else getString(R.string.slot_empty),
                    12, color = AppUi.MUTED))
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) })
            row.contentDescription = "${getString(R.string.slot_name, n)}, ${if (occupied) fmt.format(Date(state.lastModified())) else getString(R.string.slot_empty)}" +
                if (selected) ", ranura actual" else ""
            if (enabled) row.setOnClickListener {
                fun perform() {
                    setSlot(n)
                    when (mode) {
                        SlotMode.SAVE -> saveSlot(n)
                        SlotMode.LOAD -> loadSlot(n)
                        SlotMode.PICK -> {}
                    }
                }
                if (mode == SlotMode.SAVE && occupied) {
                    show(AppDialog.Builder(this).setTitle("¿Sobrescribir la ranura $n?")
                        .setMessage("Se reemplazará el estado guardado en esta ranura.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Sobrescribir") { _, _ -> perform(); dialog.dismiss() })
                } else { perform(); dialog.dismiss() }
            }
            body.addView(row)
        }
        val title = when (mode) {
            SlotMode.SAVE -> R.string.slot_title_save
            SlotMode.LOAD -> R.string.slot_title_load
            SlotMode.PICK -> R.string.slot_title_pick
        }
        dialog = show(AppDialog.Builder(this).setTitle(title).setView(body)
            .setNegativeButton(R.string.cancel, null))
    }

    // ---------------------------------------------------------------- multijugador (cable link)

    private fun linkStatus(): String {
        val l = link
        return if (l != null && l.connected) getString(R.string.link_connected, l.peerName) else getString(R.string.link_off)
    }

    private fun linkDialog() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += getString(R.string.link_host_wifi) to { hostWifi() }
        items += getString(R.string.link_join_wifi) to { joinWifi() }
        items += getString(R.string.link_host_bt) to { withBluetooth { hostBluetooth(it) } }
        items += getString(R.string.link_join_bt) to { withBluetooth { joinBluetooth(it) } }
        if (link?.connected == true) items += getString(R.string.link_disconnect) to { disconnectLink() }
        show(AppDialog.Builder(this)
            .setTitle(R.string.link_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() })
    }

    private fun myName(): String = Build.MODEL ?: "Android"

    private fun gameCode() = emu?.gba?.gameCode ?: ""

    /** Espera una conexión en segundo plano con un diálogo que se puede cancelar. */
    private fun waitFor(message: String, work: (LinkNet.Waiter) -> LinkConnection) {
        val w = LinkNet.Waiter()
        val dlg = show(AppDialog.Builder(this)
            .setTitle(R.string.link_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel) { _, _ -> w.cancel() }
            .setOnCancelListener { w.cancel() })
        dlg.setCanceledOnTouchOutside(false)
        Thread {
            val result = try { Result.success(work(w)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                val conn = result.getOrNull()
                if (conn == null) {
                    if (!w.cancelled) toast(getString(R.string.link_error, result.exceptionOrNull()?.message ?: "?"), true)
                } else if (w.cancelled || isFinishing) {
                    conn.close()
                } else {
                    attachLink(conn)
                }
                if (dlg.isShowing) dlg.dismiss()
            }
        }.start()
    }

    private fun attachLink(conn: LinkConnection) {
        link?.onClosed = null
        link?.close()
        link = conn
        emu?.link = conn
        conn.onClosed = {
            runOnUiThread {
                if (link === conn) { link = null; emu?.link = null }
                toast(getString(R.string.link_lost), true)
            }
        }
        toast(getString(R.string.link_ok, conn.peerName, if (conn.isParent) 1 else 2), true)
        if (conn.peerGame != gameCode()) toast(getString(R.string.link_other_game, conn.peerGame), true)
    }

    private fun disconnectLink() {
        val l = link ?: return
        l.onClosed = null
        link = null
        emu?.link = null
        l.close()
    }

    private fun hostWifi() {
        val ips = LinkNet.localAddresses()
        val where = if (ips.isEmpty()) getString(R.string.link_no_network) else ips.joinToString(", ")
        waitFor(getString(R.string.link_waiting_wifi, where)) { w -> LinkNet.hostTcp(w, gameCode(), myName()) }
    }

    private fun joinWifi() {
        val found = mutableListOf<LinkNet.Found>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val w = LinkNet.Waiter()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("KGBA")?.apply { setReferenceCounted(false); acquire() }
        show(AppDialog.Builder(this)
            .setTitle(R.string.link_searching)
            .setAdapter(adapter) { _, which -> w.cancel(); connectTcp(found[which].address) }
            .setNeutralButton(R.string.link_manual_ip) { _, _ -> w.cancel(); manualIp() }
            .setNegativeButton(R.string.cancel) { _, _ -> w.cancel() }) {
            w.cancel(); lock?.release()
        }
        Thread {
            try {
                LinkNet.discover(w) { f ->
                    runOnUiThread {
                        found += f
                        adapter.add("${f.name}  ·  ${f.game}  ·  ${f.address}")
                    }
                }
            } catch (e: Exception) {
                if (!w.cancelled) runOnUiThread { toast(getString(R.string.link_error, e.message ?: "?")) }
            }
        }.start()
    }

    private fun manualIp() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "192.168.1.20"
            setText(prefs.getString("last_ip", ""))
        }
        show(AppDialog.Builder(this)
            .setTitle(R.string.link_manual_ip)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ip = edit.text.toString().trim()
                if (ip.isNotEmpty()) { prefs.edit().putString("last_ip", ip).apply(); connectTcp(ip) }
            }
            .setNegativeButton(R.string.cancel, null))
    }

    private fun connectTcp(address: String) {
        waitFor(getString(R.string.link_connecting, address)) { w -> LinkNet.joinTcp(w, address, gameCode(), myName()) }
    }

    /** Comprueba permisos y que el Bluetooth esté encendido antes de [action]. */
    private fun withBluetooth(action: (BluetoothAdapter) -> Unit) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { toast(getString(R.string.link_no_bt)); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBt = { withBluetooth(action) }
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        val enabled = try { adapter.isEnabled } catch (_: SecurityException) { false }
        if (!enabled) {
            toast(getString(R.string.link_bt_off), true)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ON)
            } catch (_: Exception) {}
            return
        }
        action(adapter)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        val p = pendingBt
        pendingBt = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) p?.invoke()
        else toast(getString(R.string.link_bt_denied), true)
    }

    @SuppressLint("MissingPermission")
    private fun hostBluetooth(adapter: BluetoothAdapter) {
        val name = try { adapter.name } catch (_: SecurityException) { null } ?: myName()
        waitFor(getString(R.string.link_waiting_bt, name)) { w -> LinkNet.hostBluetooth(w, adapter, gameCode(), myName()) }
    }

    @SuppressLint("MissingPermission")
    private fun joinBluetooth(adapter: BluetoothAdapter) {
        val devices: List<BluetoothDevice> = try { adapter.bondedDevices.toList() } catch (_: SecurityException) { emptyList() }
        if (devices.isEmpty()) { toast(getString(R.string.link_no_paired), true); return }
        val names = devices.map { d -> (try { d.name } catch (_: SecurityException) { null }) ?: d.address }
        show(AppDialog.Builder(this)
            .setTitle(R.string.link_pick_device)
            .setItems(names.toTypedArray()) { _, which ->
                val d = devices[which]
                waitFor(getString(R.string.link_connecting, names[which])) { w -> LinkNet.joinBluetooth(w, adapter, d, gameCode(), myName()) }
            })
    }

    // ---------------------------------------------------------------- captura de pantalla

    private fun screenshot() {
        val e = emu ?: return
        val name = "KGBA_${romId}_${System.currentTimeMillis()}.png"
        e.capture { px ->
            val where = try {
                val bmp = Bitmap.createBitmap(px, 240, 160, Bitmap.Config.ARGB_8888)
                val big = Bitmap.createScaledBitmap(bmp, 720, 480, false)
                val place = if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KGBA")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("no se pudo crear la imagen")
                    contentResolver.openOutputStream(uri)?.use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Environment.DIRECTORY_PICTURES + "/KGBA"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                    val f = File(dir, name)
                    f.outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    f.parent ?: ""
                }
                bmp.recycle(); big.recycle()
                getString(R.string.screenshot_saved, place)
            } catch (ex: Exception) {
                getString(R.string.state_error, ex.message ?: ex.toString())
            }
            runOnUiThread { toast(where) }
        }
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- sensores y vibración

    private fun registerSensors() {
        val g = emu?.gba ?: return
        sensorManager.unregisterListener(this)
        if (g.tilt != null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            tiltBaseX = Float.NaN
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (g.gpio?.hasGyro == true) sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (g.gpio?.hasSolar == true && prefs.getInt("solar", -1) < 0) {
            sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    override fun onSensorChanged(ev: SensorEvent) {
        val e = emu ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // ejes relativos a la pantalla según la rotación
                val ax = ev.values[0]; val ay = ev.values[1]
                @Suppress("DEPRECATION")
                val rot = windowManager.defaultDisplay.rotation
                val (sx, sy) = when (rot) {
                    Surface.ROTATION_90 -> -ay to ax
                    Surface.ROTATION_180 -> -ax to -ay
                    Surface.ROTATION_270 -> ay to -ax
                    else -> ax to ay
                }
                val x = -sx / SensorManager.GRAVITY_EARTH
                val y = -sy / SensorManager.GRAVITY_EARTH
                if (tiltBaseX.isNaN()) { tiltBaseX = x; tiltBaseY = y } // la postura inicial es el centro
                e.tiltX = ((x - tiltBaseX) * 1.5f).coerceIn(-1f, 1f)
                e.tiltY = ((y - tiltBaseY) * 1.5f).coerceIn(-1f, 1f)
            }
            Sensor.TYPE_GYROSCOPE -> e.gyroZ = (ev.values[2] / 8f).coerceIn(-1f, 1f)
            Sensor.TYPE_LIGHT -> {
                // 10 lux (interior oscuro) = nivel 0 ... 100 000 lux (sol directo) = nivel 10
                val level = ((log10(ev.values[0] + 1f) - 1f) * 2.5f).coerceIn(0f, 10f)
                e.lux = luxFor(level)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun luxFor(level: Float): Int {
        if (level <= 0f) return 0
        val i = level.toInt().coerceIn(0, 9)
        val lo = if (i == 0) 0 else LUX[i - 1]
        return (lo + (LUX[i] - lo) * (level - i)).toInt().coerceIn(0, 255)
    }

    private fun solarName(): String {
        val s = prefs.getInt("solar", -1)
        return if (s < 0) getString(R.string.solar_auto) else getString(R.string.solar_level, s)
    }

    private fun updateSolar() {
        val s = prefs.getInt("solar", -1)
        emu?.lux = if (s < 0) luxFor(5f) else luxFor(s.toFloat())
    }

    private fun solarDialog() {
        val names = arrayOf(getString(R.string.solar_auto)) + (0..10).map { getString(R.string.solar_level, it) }
        show(AppDialog.Builder(this).setTitle("Sensor solar").setSingleChoiceItems(names, prefs.getInt("solar", -1) + 1) { d, which ->
            prefs.edit().putInt("solar", which - 1).apply()
            updateSolar(); registerSensors()
            d.dismiss()
        })
    }

    private fun rumble(on: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (!on) { v.cancel(); return }
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(5000)
    }

    // ---------------------------------------------------------------- mandos físicos y teclado

    private fun remapDialog() {
        val names = Keymap.ACTIONS.map { (a, n) -> "$n:  ${keymap.describe(a)}" }.toMutableList()
        names += getString(R.string.remap_reset)
        show(AppDialog.Builder(this)
            .setTitle(R.string.remap_title)
            .setItems(names.toTypedArray()) { _, which ->
                if (which == Keymap.ACTIONS.size) { keymap.reset(); remapDialog(); return@setItems }
                val (action, name) = Keymap.ACTIONS[which]
                val d = AppDialog.Builder(this)
                    .setMessage(getString(R.string.remap_press, name))
                    .setNegativeButton(R.string.cancel, null)
                d.setOnKeyListener { dlg, code, ev ->
                    if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                    if (code == KeyEvent.KEYCODE_BACK) keymap.clear(action) else keymap.assign(code, action)
                    dlg.dismiss()
                    remapDialog()
                    true
                }
                show(d)
            })
    }

    private fun updateHw() { emu?.hwButtons = keyButtons or axisButtons }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (booting && keyCode != KeyEvent.KEYCODE_BACK) return true
        val a = keymap.action(keyCode)
        if (a < 0) return super.onKeyDown(keyCode, event)
        if (a < 10) { keyButtons = keyButtons or (1 shl a); updateHw(); return true }
        if (event.repeatCount == 0) when (a) {
            Keymap.QUICK_SAVE -> quickSave()
            Keymap.QUICK_LOAD -> quickLoad()
            Keymap.FAST_FORWARD -> toggleFastForward()
            Keymap.REWIND -> emu?.rewinding = true
            Keymap.MENU -> showMenu()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val a = keymap.action(keyCode)
        if (a < 0) return super.onKeyUp(keyCode, event)
        if (a < 10) { keyButtons = keyButtons and (1 shl a).inv(); updateHw() }
        if (a == Keymap.REWIND) emu?.rewinding = false
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (booting) return true
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f) m = m or com.kgba.core.Button.LEFT
            if (x > 0.5f) m = m or com.kgba.core.Button.RIGHT
            if (y < -0.5f) m = m or com.kgba.core.Button.UP
            if (y > 0.5f) m = m or com.kgba.core.Button.DOWN
            axisButtons = m; updateHw()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    companion object {
        /** Ranura (1..10) que se carga nada más abrir el juego. */
        const val EXTRA_LOAD_SLOT = "load_slot"
        private const val REQ_BT = 10
        private const val REQ_BT_ON = 11

        /** Luz que ve el sensor solar en cada nivel (1..10), como en el cartucho de Boktai. */
        private val LUX = intArrayOf(5, 11, 18, 27, 42, 62, 84, 109, 139, 183)
    }
}
