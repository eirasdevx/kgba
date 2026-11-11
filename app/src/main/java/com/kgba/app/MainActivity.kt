package com.kgba.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * Menú principal con barra de navegación inferior:
 * Biblioteca (última partida + colección de cartuchos), Continuar, Guardados y Ajustes.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_ROM = 1
        private const val REQ_BIOS = 2
        private const val REQ_SKIN = 3
        private const val REQ_FOLDER = 4
        private const val MAX_RECENT = 2000
        private const val LIBRARY = "library"
        private const val SAVES = "saves"
        private const val SETTINGS = "settings"
    }

    private val prefs by lazy { getSharedPreferences("kgba", MODE_PRIVATE) }
    private lateinit var ui: AppUi
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var grid: LinearLayout? = null
    private var page = LIBRARY
    private var filter = "all"
    private var query = ""
    private var recent = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("page") ?: LIBRARY
        filter = savedInstanceState?.getString("filter") ?: "all"
        loadRecent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("filter", filter)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        render()
        syncFolder(announce = false)
    }

    // ------------------------------------------------------------------ carpeta de juegos

    private var scanning = false

    /**
     * Recorre la carpeta de juegos y pone la biblioteca al día: añade los nuevos (al final, sin
     * jugar) y quita los que ya no están. Con [announce] avisa del resultado.
     */
    private fun syncFolder(announce: Boolean) {
        val tree = RomFolder.get(this) ?: return
        if (scanning) return
        scanning = true
        Thread {
            val result = runCatching { RomFolder.scan(this, tree) }
            runOnUiThread {
                scanning = false
                if (isDestroyed) return@runOnUiThread
                result.onFailure {
                    if (announce) toast("No se puede leer la carpeta. Vuelve a elegirla en Ajustes.")
                }.onSuccess { games ->
                    val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
                    val found = games.map { it.first }.toSet()
                    val before = recent.size
                    val removed = recent.removeAll { RomFolder.contains(tree, it.first) && it.first !in found }
                    val known = recent.map { it.first }.toSet()
                    val added = games.filter { it.first !in known && it.first !in hidden }
                    recent.addAll(added)
                    if (removed || added.isNotEmpty()) { storeRecent(); render(true) }
                    if (announce) toast(when {
                        games.isEmpty() -> "No hay juegos .gba ni .zip en «${RomFolder.label(this, tree)}»"
                        added.isEmpty() -> "${games.size} juegos en la carpeta · nada nuevo"
                        else -> "${added.size} juegos nuevos añadidos (${games.size} en la carpeta)"
                    })
                    if (before == 0 && added.isNotEmpty() && page != LIBRARY) { page = LIBRARY; render() }
                }
            }
        }.start()
    }

    private fun pickFolder() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)

    // ------------------------------------------------------------------ estructura

    private fun render(keepScroll: Boolean = false) {
        val y = if (keepScroll && ::scroll.isInitialized) scroll.scrollY else 0
        applySystemBars(AppUi.applyTheme(this))
        ui = AppUi(this)
        val root = ui.column()
        root.setBackgroundColor(AppUi.BG)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        root.addView(header())
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        content = ui.column().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomBar())
        setContentView(root)
        root.requestApplyInsets()
        grid = null
        when (page) {
            SAVES -> renderSaves()
            SETTINGS -> renderSettings()
            else -> renderLibrary()
        }
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    /** Barras del sistema del color del tema, con iconos oscuros en el tema claro. */
    @Suppress("DEPRECATION")
    private fun applySystemBars(dark: Boolean) {
        window.statusBarColor = AppUi.BG
        window.navigationBarColor = AppUi.SURFACE_LOWEST
        var flags = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        if (!dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
    }

    /** Diálogos con el tema claro u oscuro activo. */
    private fun dialog() = AppDialog.Builder(this)

    /** Al cambiar el modo oscuro del sistema con el tema «Sistema», se repinta. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        render(true)
    }

    private fun header(): View {
        val bar = ui.line().apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(10), ui.dp(8)) }
        val titles = ui.column()
        val title = ui.line()
        title.addView(View(this).apply {
            background = ui.shape(AppUi.TERTIARY, 999)
        }, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply { marginEnd = ui.dp(8) })
        title.addView(ui.headline("KGBA", 28))
        titles.addView(title)
        if (page != LIBRARY) titles.addView(ui.mono(if (page == SAVES) "Gestor de guardados" else "Ajustes del emulador",
            10, AppUi.TERTIARY).apply { setPadding(ui.dp(17), ui.dp(4), 0, 0) })
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.iconButton(R.drawable.ic_add, "Añadir juego") { openRom() })
        bar.addView(ui.iconButton(R.drawable.ic_nav_settings, "Ajustes") { go(SETTINGS) })
        return bar
    }

    private fun bottomBar(): View {
        val bar = ui.line(Gravity.CENTER).apply {
            setBackgroundColor(AppUi.SURFACE_LOWEST)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(10))
        }
        fun item(key: String?, iconRes: Int, label: String, action: () -> Unit) {
            val on = key == page
            val cell = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                isSelected = on
                contentDescription = label
                background = ui.ripple(ui.shape(0, 16))
                setOnClickListener { action() }
            }
            val pillBg = FrameLayout(this).apply {
                background = ui.shape(if (on) 0x557C3AED else 0, 999)
                addView(ui.icon(iconRes, if (on) AppUi.PRIMARY else AppUi.OUTLINE, 24),
                    FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            }
            cell.addView(pillBg, LinearLayout.LayoutParams(ui.dp(56), ui.dp(30)))
            cell.addView(ui.text(label, 11, on, if (on) AppUi.TEXT else AppUi.OUTLINE).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0)
            })
            bar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        item(LIBRARY, R.drawable.ic_nav_library, "Biblioteca") { go(LIBRARY) }
        item(null, R.drawable.ic_nav_play, "Continuar") {
            recent.firstOrNull()?.let { launch(Uri.parse(it.first)) } ?: toast("Todavía no has jugado a nada")
        }
        item(SAVES, R.drawable.ic_nav_saves, "Guardados") { go(SAVES) }
        item(SETTINGS, R.drawable.ic_nav_settings, "Ajustes") { go(SETTINGS) }
        return bar
    }

    private fun go(target: String) {
        if (page == target) { scroll.smoothScrollTo(0, 0); return }
        page = target; query = ""; render()
    }

    // ------------------------------------------------------------------ biblioteca

    private fun renderLibrary() {
        content.addView(searchRow())
        content.addView(filterChips())
        if (recent.isEmpty()) { content.addView(emptyLibrary()); return }
        if (filter == "all" && query.isEmpty()) {
            val first = recent.first()
            content.addView(continueCard(first, RomStore.idFor(this, first.first)))
        }
        val head = ui.line().apply { setPadding(ui.dp(2), ui.dp(28), ui.dp(2), ui.dp(4)) }
        val asList = prefs.getBoolean("view_list", true)
        head.addView(ui.icon(if (asList) R.drawable.ic_list else R.drawable.ic_grid, AppUi.PRIMARY, 22).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = ui.dp(10)
        })
        head.addView(ui.headline("Colección de cartuchos", 21), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(head)
        // barra de vista: orden a la izquierda, lista/cuadrícula a la derecha
        val tools = ui.line().apply { setPadding(ui.dp(2), 0, 0, 0) }
        val az = prefs.getBoolean("sort_az", false)
        tools.addView(ui.mono(if (az) "Orden: A–Z ▾" else "Orden: reciente ▾", 10, AppUi.MUTED).apply {
            setPadding(0, ui.dp(12), ui.dp(8), ui.dp(12))
            isFocusable = true
            contentDescription = "Cambiar orden"
            setOnClickListener { prefs.edit().putBoolean("sort_az", !az).apply(); render(true) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = ui.line().apply {
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            background = ui.shape(AppUi.SURFACE_LOW, 999, AppUi.BORDER)
        }
        fun viewButton(list: Boolean, res: Int, label: String) = toggle.addView(
            ui.iconButton(res, label, if (asList == list) AppUi.ON_PRIMARY_CONTAINER else AppUi.MUTED,
                if (asList == list) AppUi.PRIMARY_CONTAINER else 0, 34) {
                if (asList != list) { prefs.edit().putBoolean("view_list", list).apply(); render(true) }
            })
        viewButton(true, R.drawable.ic_list, "Ver en lista")
        viewButton(false, R.drawable.ic_grid, "Ver en cuadrícula")
        tools.addView(toggle)
        content.addView(tools)
        grid = ui.column().also { content.addView(it) }
        fillGrid()
    }

    private fun searchRow(): View {
        val row = ui.line().apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) } }
        val box = ui.line().apply {
            setPadding(ui.dp(14), 0, ui.dp(10), 0)
            background = ui.shape(AppUi.PANEL, 999, AppUi.BORDER)
        }
        box.addView(ui.icon(R.drawable.ic_search, AppUi.MUTED, 22))
        val field = EditText(this).apply {
            hint = "Buscar por título"
            setHintTextColor(AppUi.OUTLINE)
            setTextColor(AppUi.TEXT)
            textSize = 15f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(ui.dp(10), 0, 0, 0)
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim() ?: ""
                    if (q == query) return
                    val heroChanged = q.isEmpty() != query.isEmpty()
                    query = q
                    if (heroChanged) { render(); focusSearch() } else fillGrid()
                }
            })
        }
        box.addView(field, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
        row.addView(box, LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { marginEnd = ui.dp(10) })
        row.addView(ui.pill("ROMs", R.drawable.ic_add, AppUi.PILL_STRONG, 52) { openRom() })
        return row
    }

    private fun focusSearch() {
        val field = findEdit(content) ?: return
        field.requestFocus(); field.setSelection(field.text.length)
    }

    private fun findEdit(v: View): EditText? {
        if (v is EditText) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findEdit(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun filterChips(): View {
        val row = ui.line()
        row.addView(ui.chip("Todos (${recent.size})", filter == "all") { filter = "all"; render() })
        row.addView(ui.chip("★ Favoritos (${favorites().size})", filter == "fav") { filter = "fav"; render() })
        row.addView(ui.chip("Con guardados", filter == "saves") { filter = "saves"; render() })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) }
        }
    }

    private fun emptyLibrary(): View {
        val card = ui.card(28).apply { gravity = Gravity.CENTER_HORIZONTAL }
        (card.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(24)
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cartridge)
            imageTintList = ColorStateList.valueOf(AppUi.PRIMARY)
        }, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
        card.addView(ui.headline("Inserta tu primer cartucho", 20).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(8))
        })
        card.addView(ui.text("Elige la carpeta donde guardas tus ROMs y KGBA añadirá todos los juegos .gba y .zip que haya dentro, también en subcarpetas.",
            14, color = AppUi.MUTED).apply { gravity = Gravity.CENTER })
        card.addView(ui.pill("Elegir carpeta de juegos", R.drawable.ic_grid) { pickFolder() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(16)
        })
        card.addView(ui.pill("Abrir un archivo suelto", R.drawable.ic_add, AppUi.PILL_GHOST, 44) {
            startActivityForResult(openDocument(), REQ_ROM)
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(10) })
        return card
    }

    private fun coverInto(view: ImageView, game: Pair<String, String>, pad: Int) {
        val bmp = CoverStore.file(this, game.first)?.let { BitmapFactory.decodeFile(it.path) }
        if (bmp != null) { view.setImageBitmap(bmp); view.imageTintList = null; view.setPadding(0, 0, 0, 0) }
        else {
            view.setImageResource(R.drawable.ic_cartridge)
            view.imageTintList = ColorStateList.valueOf(AppUi.OUTLINE_VARIANT)
            view.setPadding(pad, pad, pad, pad)
        }
        // Pide lo que falte (carátula y/o nombre oficial); no hace nada si ya está todo.
        CoverStore.request(this, game.first, game.second) { if (!isFinishing && page == LIBRARY) render(true) }
    }

    /** Franja compacta para retomar la última partida: carátula pequeña, título y botón de jugar. */
    private fun continueCard(game: Pair<String, String>, id: String?): View {
        val card = ui.line().apply {
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = ui.ripple(ui.shape(AppUi.SURFACE_LOW, 20, 0x557C3AED))
            isFocusable = true
            contentDescription = "Seguir jugando a ${name(game)}"
            setOnClickListener { launch(Uri.parse(game.first)) }
            setOnLongClickListener { gameOptions(game); true }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) }
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.shape(AppUi.SURFACE_LOWEST, 12, AppUi.BORDER)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        coverInto(cover, game, ui.dp(14))
        card.addView(cover, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { marginEnd = ui.dp(14) })
        val labels = ui.column()
        labels.addView(ui.mono("Seguir jugando", 10, AppUi.PRIMARY, true))
        labels.addView(ui.text(name(game), 16, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, ui.dp(2), 0, ui.dp(2))
        })
        val slot = if (id != null) " · Ranura #%02d".format(prefs.getInt("slot_$id", 1)) else ""
        labels.addView(ui.mono(playedLabel(game.first) + slot, 10, AppUi.MUTED).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        card.addView(ui.iconButton(R.drawable.ic_play, "Jugar ahora", AppUi.ON_PRIMARY_CONTAINER, AppUi.PRIMARY_CONTAINER, 48) {
            launch(Uri.parse(game.first))
        })
        return card
    }

    private fun visibleGames(): List<Pair<String, String>> {
        var list: List<Pair<String, String>> = recent
        if (filter == "fav") list = list.filter { isFavorite(it.first) }
        if (filter == "saves") list = list.filter { RomStore.stateCount(this, RomStore.idFor(this, it.first)) > 0 }
        if (query.isNotEmpty()) list = list.filter { name(it).contains(query, ignoreCase = true) }
        if (prefs.getBoolean("sort_az", false)) list = list.sortedBy { name(it).lowercase() }
        return list
    }

    private fun fillGrid() {
        val g = grid ?: return
        g.removeAllViews()
        val games = visibleGames()
        if (games.isEmpty()) {
            g.addView(ui.text(when {
                query.isNotEmpty() -> "Ningún cartucho coincide con «$query»."
                filter == "fav" -> "Aún no tienes favoritos. Mantén pulsado un juego para marcarlo."
                else -> "Ningún juego tiene estados guardados todavía."
            }, 14, color = AppUi.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(24), 0, ui.dp(24)) })
            return
        }
        if (prefs.getBoolean("view_list", true)) {
            // En tablets, la lista va en dos columnas.
            val cols = if (resources.configuration.screenWidthDp >= 600) 2 else 1
            games.chunked(cols).forEach { chunk ->
                val line = LinearLayout(this)
                for (col in 0 until cols) {
                    val game = chunk.getOrNull(col)
                    val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { if (col > 0) marginStart = ui.dp(12) }
                    line.addView(if (game == null) View(this) else gameRow(game), lp)
                }
                g.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            }
            return
        }
        val columns = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        games.chunked(columns).forEach { chunk ->
            val line = LinearLayout(this)
            for (col in 0 until columns) {
                val game = chunk.getOrNull(col)
                val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { if (col > 0) marginStart = ui.dp(12) }
                line.addView(if (game == null) View(this) else gameCard(game), lp)
            }
            g.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        }
    }

    /** Fila de la lista: carátula pequeña, título, código del cartucho y estado, favorito y menú. */
    private fun gameRow(game: Pair<String, String>): View {
        val id = RomStore.idFor(this, game.first)
        val first = recent.firstOrNull() == game
        val fav = isFavorite(game.first)
        val played = prefs.getLong("played_${game.first}", 0L)
        val states = RomStore.stateCount(this, id)
        val row = ui.line().apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(4), ui.dp(8))
            background = ui.ripple(ui.shape(AppUi.SURFACE_LOW, 18, if (first) 0x557C3AED else AppUi.BORDER))
            isFocusable = true
            contentDescription = "Jugar a ${name(game)}"
            setOnClickListener { launch(Uri.parse(game.first)) }
            setOnLongClickListener { gameOptions(game); true }
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.shape(AppUi.SURFACE_LOWEST, 12, AppUi.BORDER)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        coverInto(cover, game, ui.dp(16))
        row.addView(cover, LinearLayout.LayoutParams(ui.dp(68), ui.dp(68)).apply { marginEnd = ui.dp(14) })
        val labels = ui.column()
        val nameRow = ui.line()
        nameRow.addView(ui.text(name(game), 16, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (fav) nameRow.addView(ui.icon(R.drawable.ic_star, AppUi.PRIMARY, 16).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = ui.dp(6)
        })
        labels.addView(nameRow)
        labels.addView(ui.mono(RomStore.cartCode(id) + if (states > 0) "  ·  ▣ $states slots" else "", 10,
            if (first) AppUi.TERTIARY else AppUi.PRIMARY).apply { setPadding(0, ui.dp(6), 0, ui.dp(4)) })
        labels.addView(ui.mono(if (played == 0L) "Nuevo · sin jugar" else ago(played), 9, AppUi.OUTLINE))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.iconButton(R.drawable.ic_play, "Jugar a ${name(game)}", AppUi.ON_PRIMARY_CONTAINER,
            AppUi.PRIMARY_CONTAINER, 40) { launch(Uri.parse(game.first)) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = ui.dp(8)
        })
        row.addView(ui.iconButton(R.drawable.ic_more, "Opciones de ${name(game)}", AppUi.MUTED, 0, 40) { gameOptions(game) })
        return row
    }

    private fun gameCard(game: Pair<String, String>): View {
        val id = RomStore.idFor(this, game.first)
        val first = recent.firstOrNull() == game
        val fav = isFavorite(game.first)
        val card = ui.column().apply {
            background = ui.ripple(ui.shape(AppUi.SURFACE_LOW, 18, if (first) 0x557C3AED else AppUi.BORDER))
            clipToOutline = true
            isFocusable = true
            contentDescription = "Jugar a ${name(game)}"
            setOnClickListener { launch(Uri.parse(game.first)) }
            setOnLongClickListener { gameOptions(game); true }
        }
        // franja superior del cartucho: muescas, código y menú
        val top = ui.line().apply { setPadding(ui.dp(10), 0, 0, 0) }
        top.addView(ui.mono("▮▮", 8, AppUi.OUTLINE_VARIANT))
        top.addView(ui.mono(RomStore.cartCode(id), 10, if (first) AppUi.TERTIARY else AppUi.OUTLINE).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.iconButton(R.drawable.ic_more, "Opciones de ${name(game)}", AppUi.MUTED, 0, 36) { gameOptions(game) })
        card.addView(top)
        val art = FrameLayout(this)
        val cover = object : ImageView(this) {
            // Las cajas de GBA son casi cuadradas: celda cuadrada y la imagen entera, sin recortes.
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
        }.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(AppUi.SURFACE_LOWEST)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        coverInto(cover, game, ui.dp(36))
        art.addView(cover, FrameLayout.LayoutParams(-1, -2))
        val played = prefs.getLong("played_${game.first}", 0L)
        if (played == 0L) art.addView(ui.tag("Nuevo", AppUi.OVERLAY_VIOLET, AppUi.OVERLAY_BG),
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(8), ui.dp(8), 0, 0) })
        val states = RomStore.stateCount(this, id)
        if (states > 0) art.addView(ui.tag("▣ Slots: $states", AppUi.OVERLAY_TEXT, AppUi.OVERLAY_BG),
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(8), ui.dp(8)) })
        card.addView(art)
        val info = ui.column().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(12)) }
        val nameRow = ui.line()
        nameRow.addView(ui.text(name(game), 15, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (fav) nameRow.addView(ui.icon(R.drawable.ic_star, AppUi.PRIMARY, 16))
        info.addView(nameRow)
        info.addView(ui.mono(if (played == 0L) "Sin jugar" else ago(played), 9, AppUi.OUTLINE).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        card.addView(info)
        return card
    }

    private fun gameOptions(game: Pair<String, String>) {
        val fav = isFavorite(game.first)
        dialog().setTitle(name(game))
            .setItems(arrayOf("Jugar", if (fav) "Quitar de favoritos" else "Añadir a favoritos",
                "Buscar carátula de nuevo", "Quitar de la biblioteca")) { _, which ->
                when (which) {
                    0 -> launch(Uri.parse(game.first))
                    1 -> { setFavorite(game.first, !fav); render(true) }
                    2 -> { CoverStore.forget(this, game.first); render(true) }
                    else -> {
                        recent.remove(game); setFavorite(game.first, false)
                        // Si viene de la carpeta, que no vuelva a aparecer al escanear.
                        if (RomFolder.get(this)?.let { RomFolder.contains(it, game.first) } == true)
                            prefs.edit().putStringSet("hidden", (prefs.getStringSet("hidden", emptySet()) ?: emptySet()) + game.first).apply()
                        CoverStore.forget(this, game.first); storeRecent(); render(true)
                    }
                }
            }.show()
    }

    // ------------------------------------------------------------------ guardados

    private fun renderSaves() {
        val states = RomStore.allStates(this)
        val head = ui.line().apply { setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(4)) }
        head.addView(ui.headline("Estados guardados", 24), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(head)
        content.addView(ui.text("Toca «Cargar» para abrir el juego justo en ese momento.", 13, color = AppUi.MUTED))
        if (states.isEmpty()) {
            val card = ui.card(24).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = ui.shape(AppUi.SURFACE_LOW, 24, AppUi.OUTLINE_VARIANT)
            }
            card.addView(ui.icon(R.drawable.ic_nav_saves, AppUi.PRIMARY, 40))
            card.addView(ui.mono("Ninguna ranura ocupada", 12, AppUi.TEXT, true).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, ui.dp(8))
            })
            card.addView(ui.text("Dentro del juego, abre el menú o pulsa el icono de guardar para capturar el estado en una de sus 10 ranuras.",
                13, color = AppUi.MUTED).apply { gravity = Gravity.CENTER })
            content.addView(card)
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        states.forEachIndexed { i, s -> content.addView(stateCard(s, i == 0, fmt)) }
        // motor de estados
        content.addView(ui.sectionHeader(R.drawable.ic_chip, "Motor de estados"))
        val card = ui.card(4)
        toggle(card, "Rebobinado", "Mantén pulsado el botón de rebobinar para volver unos segundos atrás", "rewind", true)
        content.addView(card)
    }

    private fun stateCard(s: RomStore.SavedState, latest: Boolean, fmt: DateFormat): View {
        val rom = RomStore.romFor(this, s.id)
        val card = ui.card(14, AppUi.PANEL, 24).apply {
            if (latest) background = ui.shape(AppUi.PANEL, 24, 0x667C3AED)
        }
        val top = ui.line()
        top.addView(ui.tag("Slot #%02d".format(s.slot) + if (latest) " · último" else "",
            if (latest) AppUi.TERTIARY else AppUi.MUTED, if (latest) 0x264CD7F6 else AppUi.SURFACE_HIGH))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.mono(fmt.format(Date(s.file.lastModified())), 10, AppUi.OUTLINE))
        card.addView(top)
        val body = ui.line().apply { setPadding(0, ui.dp(12), 0, ui.dp(12)) }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ui.shape(AppUi.SURFACE_LOWEST, 10)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val bmp = s.thumb.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
            if (bmp != null) setImageBitmap(bmp) else {
                setImageResource(R.drawable.ic_cartridge)
                imageTintList = ColorStateList.valueOf(AppUi.OUTLINE_VARIANT)
                setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
            }
        }
        body.addView(thumb, LinearLayout.LayoutParams(ui.dp(132), ui.dp(88)).apply { marginEnd = ui.dp(14) })
        val labels = ui.column()
        labels.addView(ui.headline(rom?.let { name(it) } ?: "Juego desconocido", 18).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        labels.addView(ui.mono(RomStore.cartCode(s.id), 10, AppUi.PRIMARY).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        labels.addView(ui.mono("%.1f KB".format(s.file.length() / 1024f), 10, AppUi.OUTLINE))
        body.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(body)
        val actions = ui.line(Gravity.CENTER_VERTICAL or Gravity.END)
        actions.addView(ui.pill("Borrar", R.drawable.ic_delete, AppUi.PILL_GHOST, 40) { deleteState(s, rom) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = ui.dp(10)
        })
        actions.addView(ui.pill("Cargar", R.drawable.ic_play, AppUi.PILL_STRONG, 40) {
            if (rom == null) toast("Abre una vez el juego desde la biblioteca para poder cargar este estado")
            else launch(Uri.parse(rom.first), s.slot)
        })
        card.addView(actions)
        return card
    }

    private fun deleteState(s: RomStore.SavedState, rom: Pair<String, String>?) {
        dialog().setTitle("¿Borrar la ranura ${s.slot}?")
            .setMessage(rom?.let { name(it) } ?: s.id)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Borrar") { _, _ -> s.file.delete(); s.thumb.delete(); render(true) }
            .show()
    }

    // ------------------------------------------------------------------ ajustes

    private fun renderSettings() {
        content.addView(ui.headline("Ajustes", 26).apply { setPadding(ui.dp(2), ui.dp(8), 0, 0) })

        content.addView(ui.sectionHeader(R.drawable.ic_theme, "Apariencia", if (AppUi.dark) "Oscuro" else "Claro"))
        val look = ui.card(4)
        val modes = listOf("system", "light", "dark")
        segmented(look, "Tema de la app", listOf("Sistema", "Claro", "Oscuro"),
            modes.indexOf(AppUi.themeMode(this)).coerceAtLeast(0)) {
            prefs.edit().putString("theme", modes[it]).apply()
            scroll.post { render(true) }
        }
        content.addView(look)

        content.addView(ui.sectionHeader(R.drawable.ic_palette, "Consola y pantalla", "Carcasa"))
        val activeSkin = SkinPack.activeName(this)
        content.addView(ui.row("Carcasa", activeSkin ?: "KGBA Clásica (color a tu gusto)", "▤") { skinDialog() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12)
        })
        content.addView(ui.row("Personalizar consola", "Colores, posición y tamaño de los botones", "✥") {
            startActivity(Intent(this, ConsoleEditorActivity::class.java))
        })
        val video = ui.card(4).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(4) }
        toggle(video, "Mostrar carcasa", "El cuerpo de la consola alrededor de la pantalla", "skin", true)
        val filters = listOf(getString(R.string.filter_sharp), getString(R.string.filter_smooth),
            getString(R.string.filter_scanlines), getString(R.string.filter_lcd))
        segmented(video, "Filtro de imagen", filters.map { it.replaceFirstChar(Char::uppercase) }, prefs.getInt("filter", 0).coerceIn(0, 3)) {
            prefs.edit().putInt("filter", it).apply()
        }
        toggle(video, "Colores de LCD", "Imita los tonos apagados de la pantalla original", "lcdcolors", false)
        content.addView(video)

        content.addView(ui.sectionHeader(R.drawable.ic_audio, "Audio"))
        val audio = ui.card(4)
        toggle(audio, "Sonido", "Audio del juego y del arranque", "sound", true)
        content.addView(audio)

        content.addView(ui.sectionHeader(R.drawable.ic_nav_library, "Controles táctiles"))
        val touch = ui.card(4)
        toggle(touch, "Botones en pantalla", "Cruceta, A/B, L/R, START y SELECT", "pad", true)
        val alphas = intArrayOf(20, 40, 60, 80, 100)
        segmented(touch, "Opacidad sin carcasa (horizontal)", alphas.map { "$it%" },
            alphas.indexOf(prefs.getInt("alpha", 60)).coerceAtLeast(0)) { prefs.edit().putInt("alpha", alphas[it]).apply() }
        content.addView(touch)

        content.addView(ui.sectionHeader(R.drawable.ic_bolt, "Rendimiento", "Kernel"))
        val perf = ui.card(4)
        val speeds = intArrayOf(2, 3, 4, 6, 0)
        segmented(perf, "Velocidad de avance rápido", speeds.map { if (it == 0) "MAX" else "×$it" },
            speeds.indexOf(prefs.getInt("ffspeed", 3)).coerceAtLeast(0)) { prefs.edit().putInt("ffspeed", speeds[it]).apply() }
        toggle(perf, "Rebobinado", "Volver unos segundos atrás mientras juegas", "rewind", true)
        content.addView(perf)

        content.addView(ui.sectionHeader(R.drawable.ic_nav_saves, "Biblioteca"))
        val folder = RomFolder.get(this)
        content.addView(ui.row("Carpeta de juegos",
            if (folder == null) "Ninguna · Elige dónde guardas tus ROMs" else "${RomFolder.label(this, folder)} · Cambiar", "▦") {
            pickFolder()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) })
        if (folder != null) {
            content.addView(ui.row("Volver a escanear", "Busca juegos nuevos o borrados en la carpeta", "⟳") { syncFolder(true) })
            content.addView(ui.row("Dejar de usar la carpeta", "Quita de la biblioteca los juegos de la carpeta", "✕") {
                recent.removeAll { RomFolder.contains(folder, it.first) }; storeRecent()
                RomFolder.clear(this); prefs.edit().remove("hidden").apply(); render(true)
            })
        }

        content.addView(ui.sectionHeader(R.drawable.ic_chip, "Sistema"))
        val hasBios = RomStore.bios(this) != null
        content.addView(ui.row("BIOS", if (hasBios) "BIOS externa instalada · Cambiar" else "BIOS integrada · No necesitas ninguna", "▣") {
            startActivityForResult(openDocument(), REQ_BIOS)
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) })
        if (hasBios) content.addView(ui.row("Usar BIOS integrada", "Retirar la BIOS externa", "↺") {
            RomStore.biosFile(this).delete(); render(true)
        })
        content.addView(ui.mono("Creado por eiras.devx", 10, AppUi.OUTLINE).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(28), 0, ui.dp(8))
        })
    }


    /** Fila con interruptor violeta. */
    private fun toggle(parent: LinearLayout, title: String, subtitle: String, key: String, default: Boolean) {
        val row = ui.line().apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12)); minimumHeight = ui.dp(64) }
        val labels = ui.column()
        labels.addView(ui.text(title, 16, true).apply { setPadding(0, 0, 0, ui.dp(2)) })
        labels.addView(ui.text(subtitle, 12, color = AppUi.MUTED).apply { setPadding(0, 0, 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            thumbTintList = ColorStateList(states, intArrayOf(AppUi.ON_PRIMARY_CONTAINER, AppUi.OUTLINE))
            trackTintList = ColorStateList(states, intArrayOf(AppUi.PRIMARY_CONTAINER, AppUi.SURFACE_HIGHEST))
            contentDescription = title
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
        row.addView(sw)
        row.isFocusable = false
        row.setOnClickListener { sw.toggle() }
        row.background = ui.ripple(ui.shape(0, 16))
        if (parent.childCount > 0) parent.addView(divider())
        parent.addView(row)
    }

    /** Selector segmentado (como «3:2 Original / Completo / Estirado» del diseño). */
    private fun segmented(parent: LinearLayout, title: String, options: List<String>, selected: Int, pick: (Int) -> Unit) {
        if (parent.childCount > 0) parent.addView(divider())
        val box = ui.column().apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)) }
        box.addView(ui.text(title, 16, true).apply { setPadding(0, 0, 0, ui.dp(10)) })
        box.addView(ui.options(options, selected) { pick(it) })
        parent.addView(box)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(AppUi.BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, ui.dp(1))).apply { leftMargin = ui.dp(14); rightMargin = ui.dp(14) }
    }

    // ------------------------------------------------------------------ carcasas

    private fun skinDialog() {
        val installed = SkinPack.installed(this)
        val active = SkinPack.activeName(this)
        val labels = listOf("KGBA Clásica (color a tu gusto)") + installed + listOf("＋ Importar carcasa (.zip)")
        val checked = if (active == null) 0 else installed.indexOf(active) + 1
        val builder = dialog().setTitle("Carcasa")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> { SkinPack.setActive(this, null); render(true) }
                    labels.size - 1 -> startActivityForResult(openDocument(), REQ_SKIN)
                    else -> { SkinPack.setActive(this, installed[which - 1]); render(true) }
                }
            }
        if (installed.isNotEmpty()) builder.setNeutralButton("Borrar…") { _, _ ->
            dialog().setTitle("Borrar carcasa")
                .setItems(installed.toTypedArray()) { _, i -> SkinPack.delete(this, installed[i]); render(true) }
                .show()
        }
        builder.show()
    }

    /** Un .zip con imágenes es una carcasa; uno con otros .zip dentro es un paquete y se eligen cuáles. */
    @Suppress("DEPRECATION")
    private fun importSkin(uri: Uri) {
        val progress = dialog().setTitle("Importar carcasa").setMessage("Leyendo el archivo…").setCancelable(false).show()
        fun done(result: () -> Unit) = runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            progress.dismiss(); result()
        }
        Thread {
            try {
                val pack = SkinPack.listPack(this, uri)
                if (pack.isEmpty()) {
                    val name = SkinPack.importSingle(this, uri)
                    done { SkinPack.setActive(this, name); render(true); toast("Carcasa «$name» instalada") }
                } else done { choosePackSkins(uri, pack) }
            } catch (e: Exception) {
                done { toast("No se pudo importar: ${e.message}") }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun choosePackSkins(uri: Uri, entries: List<String>) {
        val names = entries.map { SkinPack.cleanName(it) }
        val picked = BooleanArray(entries.size)
        dialog().setTitle("Elige carcasas (${entries.size})")
            .setMultiChoiceItems(names.toTypedArray(), picked) { _, i, on -> picked[i] = on }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Importar") { _, _ ->
                val chosen = entries.filterIndexed { i, _ -> picked[i] }
                if (chosen.isEmpty()) return@setPositiveButton
                val progress = dialog().setTitle("Importar carcasas").setMessage("Importando ${chosen.size} carcasa(s)…").setCancelable(false).show()
                Thread {
                    val result = runCatching { SkinPack.importFromPack(this, uri, chosen) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        progress.dismiss()
                        result.onSuccess { ok ->
                            ok.firstOrNull()?.let { SkinPack.setActive(this, it) }
                            toast("Carcasas instaladas: ${ok.size}"); render(true)
                        }.onFailure { toast("No se pudo importar: ${it.message}") }
                    }
                }.start()
            }.show()
    }

    // ------------------------------------------------------------------ utilidades

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun title(name: String) = name.substringBeforeLast('.', name).replace('_', ' ')

    /** Nombre oficial del juego si ya se conoce; si no, el del archivo de la ROM. */
    private fun name(game: Pair<String, String>) = CoverStore.officialTitle(this, game.first) ?: title(game.second)

    private fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    private fun isFavorite(uri: String) = uri in favorites()
    private fun setFavorite(uri: String, on: Boolean) {
        val set = favorites().toMutableSet()
        if (on) set += uri else set -= uri
        prefs.edit().putStringSet("favorites", set).apply()
    }

    private fun playedLabel(uri: String): String {
        val t = prefs.getLong("played_$uri", 0L)
        return if (t == 0L) "Listo para jugar" else "Jugado ${ago(t).lowercase()}"
    }

    /** «Hace 5 min», «Hace 2 h», «Ayer», «Hace 3 días» o la fecha. */
    private fun ago(time: Long): String {
        val min = (System.currentTimeMillis() - time) / 60_000
        return when {
            min < 1 -> "Ahora mismo"
            min < 60 -> "Hace $min min"
            min < 60 * 24 -> "Hace ${min / 60} h"
            min < 60 * 48 -> "Ayer"
            min < 60 * 24 * 7 -> "Hace ${min / (60 * 24)} días"
            else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))
        }
    }

    private fun openRom() {
        val folder = RomFolder.get(this)
        val options = listOf(
            (if (folder == null) "Elegir carpeta de juegos" else "Cambiar carpeta de juegos") to { pickFolder() },
            "Añadir un archivo suelto" to { startActivityForResult(openDocument(), REQ_ROM) },
        ) + (if (folder != null) listOf("Volver a escanear «${RomFolder.label(this, folder)}»" to { syncFolder(true) }) else emptyList())
        dialog().setTitle("Añadir juegos")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun openDocument() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_ROM -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
                val name = RomStore.displayName(this, uri)
                recent.removeAll { it.first == uri.toString() }
                recent.add(0, uri.toString() to name)
                while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
                storeRecent()
                launch(uri)
            }
            REQ_SKIN -> importSkin(uri)
            REQ_FOLDER -> {
                // Los juegos de la carpeta anterior dejan de ser accesibles: se quitan.
                RomFolder.get(this)?.takeIf { it != uri }?.let { old ->
                    recent.removeAll { RomFolder.contains(old, it.first) }; storeRecent()
                }
                try {
                    RomFolder.set(this, uri)
                    prefs.edit().remove("hidden").apply()
                    syncFolder(announce = true)
                } catch (e: SecurityException) {
                    toast("No se pudo usar esa carpeta")
                }
                render(true)
            }
            REQ_BIOS -> {
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
                if (bytes == null || bytes.size != 0x4000) {
                    Toast.makeText(this, R.string.bios_bad, Toast.LENGTH_LONG).show()
                } else {
                    RomStore.biosFile(this).writeBytes(bytes)
                }
                render(true)
            }
        }
    }

    private fun launch(uri: Uri, loadSlot: Int = 0) {
        recent.find { it.first == uri.toString() }?.let { game ->
            recent.remove(game)
            recent.add(0, game)
            storeRecent()
        }
        prefs.edit().putLong("played_$uri", System.currentTimeMillis()).apply()
        startActivity(Intent(this, EmulatorActivity::class.java).setData(uri)
            .putExtra(EmulatorActivity.EXTRA_LOAD_SLOT, loadSlot)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun loadRecent() {
        recent = (prefs.getString("recent", "") ?: "").lines().filter { '\t' in it }
            .map { it.substringBefore('\t') to it.substringAfter('\t') }.toMutableList()
    }

    private fun storeRecent() {
        prefs.edit().putString("recent", recent.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }
}
