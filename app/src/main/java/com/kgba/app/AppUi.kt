package com.kgba.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Componentes nativos compartidos (biblioteca, guardados, ajustes y menú de pausa).
 * Estilo «índigo táctico»: fondo índigo muy oscuro, tarjetas redondeadas con borde sutil,
 * acentos violeta y cian, titulares geométricos y rótulos en monoespaciada.
 */
internal class AppUi(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun background(color: Int) = shape(color, 16)

    /** Rectángulo redondeado con borde opcional. */
    fun shape(color: Int, radius: Int, stroke: Int = 0, strokeWidth: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(maxOf(1, dp(strokeWidth)), stroke)
    }

    fun ripple(base: Drawable, mask: Drawable? = null) =
        RippleDrawable(ColorStateList.valueOf(RIPPLE), base, mask)

    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun line(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(context).apply { this.gravity = gravity }

    fun text(label: String, size: Int, bold: Boolean = false, color: Int = TEXT) = TextView(context).apply {
        text = label
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(5))
    }

    /** Titular geométrico (Space Grotesk en el diseño). */
    fun headline(label: String, size: Int, color: Int = TEXT) = TextView(context).apply {
        text = label
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = -0.02f
        includeFontPadding = false
    }

    /** Rótulo técnico en monoespaciada y mayúsculas (Space Mono en el diseño). */
    fun mono(label: String, size: Int = 11, color: Int = MUTED, bold: Boolean = false) = TextView(context).apply {
        text = label.uppercase()
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = 0.08f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    fun icon(res: Int, color: Int = MUTED, size: Int = 24) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Botón redondo con solo un icono. */
    fun iconButton(res: Int, description: String, color: Int = TEXT, bg: Int = 0, size: Int = 44, action: () -> Unit) =
        ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color)
            val pad = dp(size) / 4
            setPadding(pad, pad, pad, pad)
            val oval = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            background = ripple(oval, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
            contentDescription = description
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

    /** Botón en forma de píldora con icono opcional; [filled] usa el violeta principal. */
    fun pill(label: String, iconRes: Int = 0, style: Int = PILL_PRIMARY, height: Int = 48, action: () -> Unit): LinearLayout {
        val (bg, fg, stroke) = when (style) {
            PILL_PRIMARY -> Triple(PRIMARY, ON_PRIMARY, 0)
            PILL_STRONG -> Triple(PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER, 0)
            else -> Triple(SURFACE_HIGH, TEXT, OUTLINE_VARIANT)
        }
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple(shape(bg, 999, stroke))
            isFocusable = true
            contentDescription = label
            setOnClickListener { action() }
            if (iconRes != 0) addView(icon(iconRes, fg, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
            })
            addView(mono(label, 12, fg, true))
            layoutParams = LinearLayout.LayoutParams(-2, dp(height))
        }
    }

    /** Chip de filtro: violeta claro si está activo. */
    fun chip(label: String, selected: Boolean, action: () -> Unit) = mono(label, 12, if (selected) ON_PRIMARY else TEXT).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        background = ripple(shape(if (selected) PRIMARY else SURFACE_LOW, 999, if (selected) 0 else OUTLINE_VARIANT))
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) }
    }

    /** Etiqueta pequeña tipo «LED» (cian por defecto). */
    fun tag(label: String, color: Int = TERTIARY, bg: Int = 0x1F000000 or (TERTIARY and 0xFFFFFF)) =
        mono(label, 10, color, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = shape(bg, 999, (color and 0xFFFFFF) or 0x55000000)
        }

    /** Tarjeta de superficie elevada. */
    fun card(padding: Int = 16, color: Int = PANEL, radius: Int = 20) = column(padding).apply {
        background = shape(color, radius, BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    /** Cabecera de sección: icono violeta, titular y, a la derecha, un rótulo técnico opcional. */
    fun sectionHeader(iconRes: Int, title: String, badge: String? = null) = line().apply {
        setPadding(0, dp(28), 0, dp(4))
        addView(icon(iconRes, PRIMARY, 22).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) })
        addView(headline(title, 20), LinearLayout.LayoutParams(0, -2, 1f))
        if (badge != null) addView(tag(badge, OUTLINE, SURFACE_LOW))
    }

    fun row(title: String, subtitle: String, symbol: String, action: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            minimumHeight = dp(64)
            background = ripple(shape(PANEL, 16, BORDER))
            isFocusable = true
            setOnClickListener { action() }
            contentDescription = "$title. $subtitle"
        }
        row.addView(text(symbol, 22, color = PRIMARY).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(36), -2))
        val labels = column().apply {
            addView(text(title, 16, true).apply { setPadding(0, 0, 0, dp(2)) })
            if (subtitle.isNotEmpty()) addView(text(subtitle, 12, color = MUTED).apply { setPadding(0, 0, 0, 0) })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        row.addView(icon(R.drawable.ic_chevron, OUTLINE, 22))
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        return row
    }

    fun section(label: String) = mono(label, 11, PRIMARY, true).apply {
        setPadding(0, dp(20), 0, dp(8))
    }

    /** Wrap choices according to their measured text and the user's font scale. */
    fun options(labels: List<String>, selected: Int, pick: (Int) -> Unit): View {
        val cells = mutableListOf<TextView>()
        fun paint(index: Int) = cells.forEachIndexed { i, cell ->
            cell.isSelected = i == index
            cell.background = ripple(shape(if (i == index) PRIMARY_CONTAINER else SURFACE_LOWEST,
                14, if (i == index) PRIMARY else BORDER))
            cell.setTextColor(if (i == index) ON_PRIMARY_CONTAINER else MUTED)
            cell.contentDescription = labels[i] + if (i == index) ", seleccionado" else ""
        }
        val group = object : android.view.ViewGroup(context) {
            private val bounds = mutableListOf<android.graphics.Rect>()
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val gap = dp(8)
                val desired = cells.maxOfOrNull { it.paint.measureText(it.text.toString()).toInt() + dp(32) } ?: 1
                val columns = ((width + gap) / (desired + gap)).coerceIn(1, labels.size)
                val cellWidth = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1)
                bounds.clear()
                var y = 0
                cells.chunked(columns).forEach { row ->
                    row.forEach { it.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
                    val height = row.maxOf { it.measuredHeight }
                    row.forEachIndexed { x, cell ->
                        cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                        bounds += android.graphics.Rect(x * (cellWidth + gap), y, x * (cellWidth + gap) + cellWidth, y + height)
                    }
                    y += height + gap
                }
                setMeasuredDimension(width, (y - gap).coerceAtLeast(0))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                cells.forEachIndexed { i, cell -> bounds[i].let { cell.layout(it.left, it.top, it.right, it.bottom) } }
            }
        }
        labels.forEachIndexed { i, label ->
            val cell = text(label, 13, true).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                setOnClickListener { paint(i); pick(i) }
            }
            cells += cell
            group.addView(cell)
        }
        paint(selected)
        return group
    }

    fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(if (primary) ON_PRIMARY else TEXT)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        background = ripple(shape(if (primary) PRIMARY else PANEL, 999, if (primary) 0 else BORDER))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { action() }
    }

    companion object {
        /** Tema activo; lo fija [applyTheme] según el ajuste «Tema» (sistema, claro u oscuro). */
        @Volatile var dark = true
            private set

        fun themeMode(context: Context): String =
            context.getSharedPreferences("kgba", Context.MODE_PRIVATE).getString("theme", "system") ?: "system"

        /** Lee el ajuste de tema y decide si toca la paleta clara o la oscura. */
        fun applyTheme(context: Context): Boolean {
            dark = when (themeMode(context)) {
                "light" -> false
                "dark" -> true
                else -> (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
            return dark
        }

        private fun pick(darkColor: Long, lightColor: Long) = (if (dark) darkColor else lightColor).toInt()

        // Paleta «Tactical Indigo»: oscura y su versión clara.
        val BG get() = pick(0xFF10141A, 0xFFF6F4FA)
        val SURFACE_LOWEST get() = pick(0xFF0A0E14, 0xFFFFFFFF)
        val SURFACE_LOW get() = pick(0xFF181C22, 0xFFEFECF4)
        val PANEL get() = pick(0xFF1C2026, 0xFFFFFFFF)
        val SURFACE_HIGH get() = pick(0xFF262A31, 0xFFE8E4EF)
        val SURFACE_HIGHEST get() = pick(0xFF31353C, 0xFFDED9E7)
        val TEXT get() = pick(0xFFDFE2EB, 0xFF1B1B21)
        val MUTED get() = pick(0xFFCCC3D8, 0xFF4A4455)
        val OUTLINE get() = pick(0xFF958DA1, 0xFF7B7487)
        val OUTLINE_VARIANT get() = pick(0xFF4A4455, 0xFFCCC3D8)
        val BORDER get() = pick(0x14FFFFFF, 0x1A000000)
        val PRIMARY get() = pick(0xFFD2BBFF, 0xFF6D28D9)
        val ON_PRIMARY get() = pick(0xFF3F008E, 0xFFFFFFFF)
        val PRIMARY_CONTAINER = 0xFF7C3AED.toInt()
        val ON_PRIMARY_CONTAINER get() = pick(0xFFEDE0FF, 0xFFFFFFFF)
        val TERTIARY get() = pick(0xFF4CD7F6, 0xFF00687A)
        val ERROR get() = pick(0xFFFFB4AB, 0xFFBA1A1A)
        val RIPPLE = 0x447C3AED
        /** Compatibilidad con el resto de pantallas. */
        val ACCENT get() = PRIMARY

        // Etiquetas sobre carátulas: siempre sobre fondo oscuro translúcido, en ambos temas.
        val OVERLAY_BG = 0xCC0A0E14.toInt()
        val OVERLAY_CYAN = 0xFF4CD7F6.toInt()
        val OVERLAY_VIOLET = 0xFFD2BBFF.toInt()
        val OVERLAY_TEXT = 0xFFDFE2EB.toInt()

        const val PILL_PRIMARY = 0
        const val PILL_STRONG = 1
        const val PILL_GHOST = 2
    }
}
