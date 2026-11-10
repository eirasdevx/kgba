package com.kgba.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.CornerPathEffect
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.kgba.core.Button
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Mando táctil al estilo de las consolas portátiles: en vertical, la pantalla ocupa el ancho
 * arriba y los accesos rápidos abajo. En horizontal los controles flanquean la pantalla.
 * El editor comparte la geometría y las zonas táctiles con la vista de juego.
 */
class GamepadView(context: Context) : View(context) {
    var consoleStyle = ConsoleStyle.load(context)
    var editing = false
    var selectedControl: String? = null
        private set
    var onSelection: (String, Float) -> Unit = { _, _ -> }
    private val defaults = linkedMapOf<String, RectF>()
    private var dragX = 0f
    private var dragY = 0f
    private val orientationKey get() = if (portrait) "portrait:" else "landscape:"

    fun refreshStyle() { onSizeChanged(width, height, width, height); invalidate() }

    /** Carcasa en imágenes importada (null = carcasa plana de colores). */
    private var pack: SkinPack? = null
    private var packBg: android.graphics.Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val drawRect = RectF()

    /** Vuelve a leer la carcasa activa (tras elegir otra en Ajustes). */
    fun reloadPack() {
        pack = SkinPack.loadActive(context)
        Emulator.frameInsets = RectF().also { pack?.frameInsets(it) }
        Emulator.classicFrame = pack == null
        packBg = null
        if (width > 0) refreshStyle()
    }

    init { reloadPack() }
    private val usingPack get() = skin && pack != null
    val packName: String? get() = pack?.name

    /** Se llama cada vez que cambia la máscara de botones pulsados. */
    var onButtons: (Int) -> Unit = {}
    var onAction: (Int) -> Unit = {}
    /** Rebobinar se mantiene pulsado: true al pulsar, false al soltar. */
    var onRewind: (Boolean) -> Unit = {}

    /** Muestra los botones de la GBA (la barra de herramientas siempre se ve). */
    var showButtons = true
        set(v) { field = v; if (!v) setPressed(0); invalidate() }
    /** Opacidad de los controles en horizontal (0..1). */
    var landscapeAlpha = 0.6f
        set(v) { field = v; invalidate() }
    var topInset = 0
        set(v) { field = v; requestLayout(); onSizeChanged(width, height, width, height); invalidate() }
    var slot = 1
        set(v) { field = v; invalidate() }
    var fastForward = false
        set(v) { field = v; invalidate() }
    /** Carcasa con color configurable y distribución independiente para cada orientación. */
    var skin = true
        set(v) { field = v; onSizeChanged(width, height, width, height); invalidate() }

    companion object {
        const val ACT_MENU = 0
        const val ACT_SAVE = 1
        const val ACT_LOAD = 2
        const val ACT_SLOT = 3
        const val ACT_REWIND = 4
        const val ACT_FF = 5

        private const val ACCENT = 0xFF69B8FF.toInt()
    }

    private class Btn(val mask: Int, val label: String, val rect: RectF, val round: Boolean)
    private class Tool(val action: Int, val rect: RectF)

    private val buttons = mutableListOf<Btn>()
    private val tools = mutableListOf<Tool>()
    private var padX = 0f
    private var padY = 0f
    private var padR = 0f
    private var pressed = 0
    private var toolDown = -1
    private var rewindHeld = false
    private var portrait = true
    private val screen = Rect()
    private var panelTop = 0f

    private val panelPaint = Paint()
    private val sepPaint = Paint().apply { color = 0xFF2A2A33.toInt() }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A5A68.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9C9D3.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7E7E8C.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB4B4C0.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4B4C0.toInt() }
    private val toolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22222A.toInt() }
    private val toolOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A46.toInt() }
    private val path = Path()
    private val capRect = RectF()
    private val capFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB141417.toInt() }
    private val capEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x24FFFFFF; strokeWidth = 2f }
    private val capShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val capMenu = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPress = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- carcasa
    private val bodyPath = Path()      // cuerpo principal (con el hueco de la pantalla)
    private val bezelPath = Path()     // marco oscuro de la pantalla (con hueco)
    private val skinBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26232F.toInt() }


    /**
     * Accesos en fila compacta (una cápsula): botones redondos de diámetro [size] centrados en
     * (cx, cy), con el menú hacia el centro.
     */
    private fun toolRow(cx: Float, cy: Float, size: Float, actions: IntArray) {
        val step = size * 1.22f
        val left = cx - (step * (actions.size - 1) + size) / 2
        actions.forEachIndexed { i, a ->
            val x = left + size / 2 + i * step
            tools += Tool(a, RectF(x - size / 2, cy - size / 2, x + size / 2, cy + size / 2))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        setPressed(0); onRewind(false); rewindHeld = false; toolDown = -1
        buttons.clear(); tools.clear()
        if (w == 0 || h == 0) return
        portrait = h > w
        val bg = packBg
        if (!skin || pack == null) packBg = null
        else if (bg == null || bg.width != w || bg.height != h) packBg = pack?.background(w, h)
        Emulator.layoutScreen(w, h, topInset, skin, screen)
        // Los controles se colocan en la zona libre de la cámara; la carcasa ocupa toda la vista.
        val safe = Emulator.safeInsets(w, h)
        val dx = safe.left.toFloat(); val dy = safe.top.toFloat()
        screen.offset(-safe.left, -safe.top)
        layoutConsole(w - safe.left - safe.right, h - safe.top - safe.bottom)
        screen.offset(safe.left, safe.top)
        buttons.forEach { it.rect.offset(dx, dy) }
        tools.forEach { it.rect.offset(dx, dy) }
        padX += dx; padY += dy; ledX += dx; ledY += dy; hingeY += dy; panelTop += dy
        if (grillX > 0f) { grillX += dx; grillY += dy }
        bodyPath.offset(dx, dy); bezelPath.offset(dx, dy)
        rememberAndApplyControls()
        val bodyColor = consoleStyle.buttons
        bodyPaint.shader = null
        bodyPaint.color = bodyColor
        bodyOn.color = shade(bodyColor, 0.72f)
        textPaint.color = if (Color.red(bodyColor) * 0.299f + Color.green(bodyColor) * 0.587f + Color.blue(bodyColor) * 0.114f > 145) 0xFF66686B.toInt() else Color.WHITE
        labelPaint.color = textPaint.color
        panelPaint.color = 0xFF10141A.toInt()
        // Sobre una carcasa en imágenes, la barra de accesos es translúcida para no tapar el diseño.
        toolPaint.color = if (skin) 0x30000000 else 0xFF22222A.toInt()
        toolOn.color = if (skin) 0x60000000 else 0xFF3A3A46.toInt()
        toolIcon = if (classic && luminance(consoleStyle.shell) > 160) 0xFF3C3F46.toInt() else 0xFFE4E4EA.toInt()
        if (!skin) toolIcon = 0xFFB4B4C0.toInt()
        if (classic) setupClassic(w.toFloat(), h.toFloat())
    }

    /** Shared geometry keeps touch targets outside the 3:2 display in both orientations. */
    private fun layoutConsole(w: Int, h: Int) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val gap = minOf(wf, hf) * 0.025f
        val frameBottom = Emulator.insets(w, h, skin).bottom * screen.height()
        panelTop = if (portrait) screen.bottom + maxOf(gap * 3, frameBottom + gap * 1.2f) else hf
        bodyPath.reset(); bezelPath.reset()
        bodyPath.fillType = Path.FillType.EVEN_ODD
        bodyPath.addRoundRect(RectF(gap / 2, gap / 2, wf - gap / 2, hf - gap / 2), gap * 2, gap * 2, Path.Direction.CW)
        bodyPath.addRect(RectF(screen), Path.Direction.CW)
        bezelPath.fillType = Path.FillType.EVEN_ODD
        bezelPath.addRoundRect(RectF(screen.left - gap / 2, screen.top - gap / 2,
            screen.right + gap / 2, screen.bottom + gap / 2), gap * 1.5f, gap * 1.5f, Path.Direction.CW)
        bezelPath.addRect(RectF(screen), Path.Direction.CW)
        skinBody.color = consoleStyle.shell
        hingeH = 0f; ledR = 0f
        if (portrait && classic) {
            layoutClassicPortrait(w, h)
        } else if (portrait) {
            val area = hf - panelTop
            val toolbarH = minOf(48 * resources.displayMetrics.density, area * 0.15f)
            val cell = (wf - gap * 4) / 6
            toolRow(wf / 2, hf - toolbarH / 2 - gap * 1.5f, minOf(toolbarH, cell * 0.8f), intArrayOf(ACT_SLOT, ACT_REWIND, ACT_SAVE, ACT_MENU, ACT_LOAD, ACT_FF))
            val controlTop = panelTop + gap * 1.5f
            val controlH = hf - controlTop - toolbarH - gap * 3
            val shoulderH = minOf(48 * resources.displayMetrics.density, controlH * 0.17f)
            buttons += Btn(Button.L, "L", RectF(gap * 2, controlTop, wf * 0.28f, controlTop + shoulderH), false)
            buttons += Btn(Button.R, "R", RectF(wf * 0.72f, controlTop, wf - gap * 2, controlTop + shoulderH), false)
            padX = wf * 0.25f; padY = controlTop + controlH * 0.53f
            padR = minOf(wf * 0.18f, controlH * 0.24f)
            val r = minOf(wf * 0.09f, controlH * 0.14f)
            circle(Button.A, "A", wf * 0.83f, controlTop + controlH * 0.45f, r)
            circle(Button.B, "B", wf * 0.63f, controlTop + controlH * 0.59f, r)
            val u = minOf(wf * 0.14f, controlH * 0.21f)
            pill(Button.SELECT, "SELECT", wf * 0.385f, controlTop + controlH * 0.89f, u)
            pill(Button.START, "START", wf * 0.615f, controlTop + controlH * 0.89f, u)
            grillLen = minOf(wf * 0.075f, controlH * 0.11f)
            grillX = wf * 0.865f; grillY = controlTop + controlH * 0.88f
        } else if (classic) {
            layoutClassicLandscape(w, h)
        } else {
            grillX = -1f
            // Los controles laterales empiezan donde acaba el marco de la pantalla, no el LCD.
            val frameLeft = screen.left - Emulator.insets(w, h, skin).left * screen.width()
            val side = minOf(frameLeft - gap / 2, hf * 0.40f)
            val lx = side / 2; val rx = wf - lx
            val shoulderH = hf * 0.13f
            buttons += Btn(Button.L, "L", RectF(gap, gap, side - gap, gap + shoulderH), false)
            buttons += Btn(Button.R, "R", RectF(wf - side + gap, gap, wf - gap, gap + shoulderH), false)
            val cell = (side - gap * 2) / 3
            val toolH = minOf(cell, hf * 0.14f)
            val toolD = minOf(toolH, (side - gap * 2) / 3.8f)
            toolRow(lx, hf * 0.22f + toolH / 2, toolD, intArrayOf(ACT_SAVE, ACT_MENU, ACT_LOAD))
            toolRow(rx, hf * 0.22f + toolH / 2, toolD, intArrayOf(ACT_SLOT, ACT_REWIND, ACT_FF))
            padX = lx; padY = hf * 0.60f; padR = minOf(side * 0.34f, hf * 0.19f)
            val r = minOf(side * 0.19f, hf * 0.105f)
            circle(Button.A, "A", rx + side * 0.23f, hf * 0.51f, r)
            circle(Button.B, "B", rx - side * 0.23f, hf * 0.65f, r)
            pill(Button.SELECT, "SELECT", lx, hf * 0.89f, side * 0.42f)
            pill(Button.START, "START", rx, hf * 0.89f, side * 0.42f)
        }
    }

    /**
     * Horizontal con la carcasa clásica: consola apaisada. L/R arriba en las esquinas, cruceta a la
     * izquierda con START/SELECT (texto + botón redondo) debajo, A/B en diagonal a la derecha con el
     * LED POWER encima y el altavoz abajo. Los accesos rápidos van en la franja sobre el marco.
     */
    private fun layoutClassicLandscape(w: Int, h: Int) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val fi = Emulator.insets(w, h, skin)
        val bl = screen.left - fi.left * screen.width()
        val br = screen.right + fi.right * screen.width()
        val bt = screen.top - fi.top * screen.height()
        // Tamaño de referencia: el alto, salvo que los laterales sean estrechos.
        val k = minOf(hf, bl / 0.42f)
        val lx = bl / 2; val rx = (br + wf) / 2
        kUnit = k
        // gatillos
        val shW = k * 0.27f; val shH = k * 0.09f; val shY = hf * 0.085f
        buttons += Btn(Button.L, "L", RectF(lx - shW / 2, shY, lx + shW / 2, shY + shH), false)
        buttons += Btn(Button.R, "R", RectF(rx - shW / 2, shY, rx + shW / 2, shY + shH), false)
        // LED de encendido bajo R
        ledX = rx + k * 0.12f; ledY = shY + shH + k * 0.065f; ledR = k * 0.014f
        // cruceta
        padX = lx; padY = hf * 0.45f; padR = k * 0.165f
        // A y B en diagonal
        val r = k * 0.075f
        circle(Button.A, "A", rx + k * 0.085f, hf * 0.42f, r)
        circle(Button.B, "B", rx - k * 0.09f, hf * 0.55f, r)
        // START / SELECT: rótulo con botón redondo a la derecha
        val sh = k * 0.085f
        buttons += Btn(Button.START, "START", RectF(lx - k * 0.10f, hf * 0.75f - sh / 2, lx + k * 0.14f, hf * 0.75f + sh / 2), false)
        buttons += Btn(Button.SELECT, "SELECT", RectF(lx - k * 0.10f, hf * 0.86f - sh / 2, lx + k * 0.14f, hf * 0.86f + sh / 2), false)
        // altavoz
        grillX = rx + k * 0.06f; grillY = hf * 0.80f; grillLen = k * 0.16f
        // accesos rápidos en la franja superior, sobre el marco
        val shellTop = minOf(wf, hf) * 0.025f
        val toolH = minOf((bt - shellTop) * 0.72f, 44 * resources.displayMetrics.density)
        val toolCy = (shellTop + bt) / 2
        val cell = (br - bl) / 6
        val toolW = minOf(cell * 0.82f, toolH * 1.5f)
        toolRow((bl + br) / 2, toolCy, minOf(toolH, toolW), intArrayOf(ACT_SLOT, ACT_REWIND, ACT_SAVE, ACT_MENU, ACT_LOAD, ACT_FF))
    }

    private var kUnit = 0f
    private var ledX = 0f
    private var ledY = 0f
    private var ledR = 0f

    private fun shade(color: Int, factor: Float) = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255))

    /** Excluye la imagen del juego de lo que se dibuja a continuación. */
    private fun clipOutScreen(canvas: Canvas) {
        val r = RectF(screen)
        if (android.os.Build.VERSION.SDK_INT >= 26) canvas.clipOutRect(r)
        else @Suppress("DEPRECATION") canvas.clipRect(r, android.graphics.Region.Op.DIFFERENCE)
    }

    private fun drawPackShell(canvas: Canvas, p: SkinPack) {
        val save = canvas.save()
        clipOutScreen(canvas)
        val bg = packBg
        if (bg != null) canvas.drawBitmap(bg, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
        else canvas.drawPath(bodyPath, skinBody)
        val frame = p.frame
        if (frame != null) {
            val inner = p.frameInner
            val sx = screen.width() / inner.width(); val sy = screen.height() / inner.height()
            drawRect.set(screen.left - inner.left * sx, screen.top - inner.top * sy,
                screen.left + (frame.width - inner.left) * sx, screen.top + (frame.height - inner.top) * sy)
            canvas.drawBitmap(frame, null, drawRect, bitmapPaint)
        } else canvas.drawPath(bezelPath, bezelPaint)
        canvas.restoreToCount(save)
    }

    /** Dibuja [bmp] centrada en [r] conservando su proporción. */
    private fun fitBitmap(canvas: Canvas, bmp: android.graphics.Bitmap, r: RectF) {
        val s = minOf(r.width() / bmp.width, r.height() / bmp.height)
        val w = bmp.width * s; val h = bmp.height * s
        drawRect.set(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
        canvas.drawBitmap(bmp, null, drawRect, bitmapPaint)
    }

    private fun drawSkin(canvas: Canvas) {
        val p = pack
        if (usingPack && p != null) drawPackShell(canvas, p) else drawClassicShell(canvas)
        drawToolbar(canvas)
        if (!showButtons && !editing) return
        drawDpad(canvas)
        for (b in buttons) drawButton(canvas, b)
    }

    // ------------------------------------------------ carcasa clásica KGBA (color a elegir)
    // Inspirada en las carcasas en imágenes: cuerpo con volumen y canto brillante, marco oscuro
    // con reflejo y logotipo, LED, altavoz y botones en relieve. Todo dibujado: no pesa nada y
    // sigue los colores de «Carcasa» y «Botones» del editor.

    private val classic get() = skin && pack == null
    private var toolIcon = 0xFFB4B4C0.toInt()
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val classicBezel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x10FFFFFF }
    private val lcdEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF0B0B0F.toInt() }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD2D2DA.toInt()
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC)
    }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4B3E.toInt() }
    private val ledGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grillDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val grillLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val faceFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dropShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x45000000 }
    private val pressShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2A000000 }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val shellText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    private val shellTextShadow = Paint(shellText)
    private val engrave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    private val engraveLight = Paint(engrave)
    private val shellRect = RectF()
    private val bezelRect = RectF()
    private val bezelShape = Path()
    private val shape = Path()
    private var shellRadius = 0f
    private var unit = 0f
    private var grillX = -1f
    private var grillY = 0f
    private var grillLen = 0f

    private fun luminance(c: Int) = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
    private fun lighten(c: Int, t: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(), (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt())

    private fun setupClassic(w: Float, h: Float) {
        val shell = consoleStyle.shell
        val base = consoleStyle.buttons
        unit = minOf(w, h) * 0.025f
        shellRect.set(unit * 0.5f, unit * 0.5f, w - unit * 0.5f, h - unit * 0.5f)
        shellRadius = unit * 2.6f
        shellPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, lighten(shell, 0.12f), shade(shell, 0.84f),
            android.graphics.Shader.TileMode.CLAMP)
        rimPaint.color = shade(shell, 0.62f); rimPaint.strokeWidth = unit * 0.35f
        glossPaint.strokeWidth = unit * 0.3f
        glossPaint.shader = android.graphics.LinearGradient(0f, shellRect.top, 0f, shellRect.top + h * 0.45f,
            0x70FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        val fi = Emulator.insets(w.toInt(), h.toInt(), skin)
        bezelRect.set(screen.left - fi.left * screen.width(), screen.top - fi.top * screen.height(),
            screen.right + fi.right * screen.width(), screen.bottom + fi.bottom * screen.height())
        val rs = bezelRect.height() * (if (portrait) 0.07f else 0.08f)
        val rb = bezelRect.height() * (if (portrait) 0.13f else 0.15f)
        bezelShape.reset()
        bezelShape.addRoundRect(bezelRect, floatArrayOf(rs, rs, rs, rs, rb, rb, rb, rb), Path.Direction.CW)
        classicBezel.shader = android.graphics.LinearGradient(0f, bezelRect.top, 0f, bezelRect.bottom,
            0xFF1E1D23.toInt(), 0xFF0B0B0E.toInt(), android.graphics.Shader.TileMode.CLAMP)
        lcdEdge.strokeWidth = maxOf(2f, unit * 0.3f)
        lcdEdge.color = 0xFF2E2E34.toInt()
        // LED verde de encendido con su halo
        ledPaint.color = 0xFF5BE35B.toInt()
        ledGlow.shader = if (ledR > 0f) android.graphics.RadialGradient(ledX, ledY, ledR * 3.4f,
            0x8855FF55.toInt(), 0x0055FF55, android.graphics.Shader.TileMode.CLAMP) else null
        // ranuras del altavoz oscuras como el canto de la carcasa
        grillDark.color = shade(shell, 0.42f); grillDark.strokeWidth = kUnit * 0.014f
        grillLight.color = (lighten(shell, 0.3f) and 0x00FFFFFF) or 0x70000000; grillLight.strokeWidth = kUnit * 0.014f
        // canto grueso y oscuro alrededor de la carcasa
        rimPaint.color = shade(shell, 0.42f); rimPaint.strokeWidth = unit * 0.9f
        shellRadius = unit * 3.4f
        shellText.color = lighten(shell, 0.55f)
        shellTextShadow.color = (shade(shell, 0.45f) and 0x00FFFFFF) or 0xA0000000.toInt()
        // bisagra de la consola plegable (solo en vertical)
        if (portrait && hingeH > 0f) {
            hingePaint.shader = android.graphics.LinearGradient(0f, hingeY - hingeH / 2, 0f, hingeY + hingeH / 2,
                intArrayOf(shade(shell, 0.55f), lighten(shell, 0.22f), shell, shade(shell, 0.5f)),
                floatArrayOf(0f, 0.3f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            seamPaint.color = shade(shell, 0.4f); seamPaint.strokeWidth = unit * 0.45f
        }
        arrowPaint.color = shade(base, 0.66f)
        arrowStroke.color = shade(base, 0.62f); arrowStroke.strokeWidth = maxOf(1.5f, padR * 0.035f)
        faceEdge.strokeWidth = maxOf(1.5f, unit * 0.16f)
    }

    private fun drawClassicShell(canvas: Canvas) {
        val save = canvas.save()
        clipOutScreen(canvas)
        canvas.drawRoundRect(shellRect, shellRadius, shellRadius, shellPaint)
        canvas.drawRoundRect(shellRect, shellRadius, shellRadius, rimPaint)
        val inset = unit * 0.6f
        drawRect.set(shellRect.left + inset, shellRect.top + inset, shellRect.right - inset, shellRect.bottom - inset)
        canvas.drawRoundRect(drawRect, shellRadius * 0.8f, shellRadius * 0.8f, glossPaint)
        // vertical: tapa y base unidas por la bisagra, como una portátil plegable
        if (portrait && hingeH > 0f) {
            canvas.drawLine(shellRect.left + rimPaint.strokeWidth, hingeY, shellRect.right - rimPaint.strokeWidth, hingeY, seamPaint)
            val hl = shellRect.width() * 0.31f; val hr = shellRect.width() * 0.69f + shellRect.left
            drawRect.set(shellRect.left + hl, hingeY - hingeH / 2, hr, hingeY + hingeH / 2)
            canvas.save(); canvas.translate(0f, unit * 0.25f)
            canvas.drawRoundRect(drawRect, hingeH / 2, hingeH / 2, dropShadow); canvas.restore()
            canvas.drawRoundRect(drawRect, hingeH / 2, hingeH / 2, hingePaint)
            canvas.drawRoundRect(drawRect, hingeH / 2, hingeH / 2, seamPaint)
        }
        // marco de la pantalla con sombra y reflejo diagonal
        canvas.save(); canvas.translate(0f, unit * 0.2f); canvas.drawPath(bezelShape, dropShadow); canvas.restore()
        canvas.drawPath(bezelShape, classicBezel)
        canvas.save()
        canvas.clipPath(bezelShape)
        shape.reset()
        shape.moveTo(bezelRect.left + bezelRect.width() * 0.52f, bezelRect.top)
        shape.lineTo(bezelRect.right, bezelRect.top)
        shape.lineTo(bezelRect.right, bezelRect.top + bezelRect.height() * 0.42f)
        shape.close()
        canvas.drawPath(shape, bezelShine)
        canvas.restore()
        drawRect.set(screen)
        drawRect.inset(-lcdEdge.strokeWidth / 2, -lcdEdge.strokeWidth / 2)
        canvas.drawRect(drawRect, lcdEdge)
        // logotipo en la franja inferior del marco
        val strip = bezelRect.bottom - screen.bottom
        if (strip > unit) {
            brandPaint.textSize = strip * 0.46f
            val x = bezelRect.centerX() - brandPaint.measureText("KGBA") / 2
            val y = screen.bottom + strip * 0.52f + brandPaint.textSize * 0.36f
            canvas.drawText("KGBA", x, y, brandPaint)
        }
        // LED verde de encendido con el rótulo POWER, bajo el gatillo R
        if (ledR > 0f) {
            canvas.drawCircle(ledX, ledY, ledR * 3.4f, ledGlow)
            canvas.drawCircle(ledX, ledY, ledR, ledPaint)
            val size = kUnit * 0.032f
            shellText.textSize = size; shellTextShadow.textSize = size
            shellText.textAlign = Paint.Align.RIGHT; shellTextShadow.textAlign = Paint.Align.RIGHT
            val tx = ledX - ledR * 2.4f; val ty = ledY + size * 0.36f
            canvas.drawText("POWER", tx, ty + maxOf(1f, size * 0.06f), shellTextShadow)
            canvas.drawText("POWER", tx, ty, shellText)
            shellText.textAlign = Paint.Align.CENTER; shellTextShadow.textAlign = Paint.Align.CENTER
        }
        canvas.restoreToCount(save)
        if (grillX > 0f) drawLandscapeGrill(canvas)
    }

    private val hingePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var hingeY = 0f
    private var hingeH = 0f

    /**
     * Vertical al estilo de una portátil plegable (tipo SP): arriba la tapa con la pantalla en su
     * marco negro, en medio la bisagra y abajo la base con L/R en las esquinas junto a la bisagra,
     * cruceta a la izquierda con START/SELECT debajo, A/B en diagonal a la derecha, LED POWER,
     * altavoz abajo a la derecha y la barra de accesos rápidos al pie.
     */
    private fun layoutClassicPortrait(w: Int, h: Int) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val gap = minOf(wf, hf) * 0.025f
        // tapa y base del mismo alto, con la bisagra justo en medio (igual que en Emulator.layoutScreen)
        hingeH = wf * Emulator.HINGE
        hingeY = topInset + (hf - topInset) / 2
        panelTop = hingeY + hingeH / 2
        // barra de accesos rápidos al pie de la base
        val toolbarH = minOf(48 * resources.displayMetrics.density, (hf - panelTop) * 0.12f)
        val cell = (wf - gap * 4) / 6
        toolRow(wf / 2, hf - toolbarH / 2 - gap * 1.5f, minOf(toolbarH, cell * 0.8f), intArrayOf(ACT_SLOT, ACT_REWIND, ACT_SAVE, ACT_MENU, ACT_LOAD, ACT_FF))
        val top = panelTop + gap * 0.6f
        val ch = hf - toolbarH - gap * 2.5f - top
        val k = minOf(wf, ch * 1.05f)
        kUnit = k
        // gatillos en las esquinas, a la altura de la bisagra
        val shW = k * 0.25f; val shH = k * 0.09f
        buttons += Btn(Button.L, "L", RectF(gap * 1.6f, top, gap * 1.6f + shW, top + shH), false)
        buttons += Btn(Button.R, "R", RectF(wf - gap * 1.6f - shW, top, wf - gap * 1.6f, top + shH), false)
        ledX = wf - gap * 1.6f - shW * 0.25f; ledY = top + shH + k * 0.06f; ledR = k * 0.014f
        val lx = wf * 0.25f
        padX = lx; padY = top + ch * 0.36f; padR = minOf(k * 0.17f, ch * 0.2f)
        val r = minOf(k * 0.085f, ch * 0.1f)
        circle(Button.A, "A", wf * 0.82f, top + ch * 0.31f, r)
        circle(Button.B, "B", wf * 0.63f, top + ch * 0.42f, r)
        val sh = minOf(k * 0.085f, ch * 0.09f)
        buttons += Btn(Button.START, "START", RectF(lx - k * 0.10f, top + ch * 0.69f - sh / 2, lx + k * 0.14f, top + ch * 0.69f + sh / 2), false)
        buttons += Btn(Button.SELECT, "SELECT", RectF(lx - k * 0.10f, top + ch * 0.81f - sh / 2, lx + k * 0.14f, top + ch * 0.81f + sh / 2), false)
        grillX = wf * 0.76f; grillY = top + ch * 0.75f; grillLen = k * 0.18f
    }

    /** Altavoz apaisado: ranuras largas, paralelas y ligeramente inclinadas hacia arriba. */
    private fun drawLandscapeGrill(canvas: Canvas) {
        val l = grillLen
        val step = kUnit * 0.032f
        for (i in 0 until 6) {
            val y = grillY + (i - 2.5f) * step
            val x0 = grillX - l / 2; val y0 = y + l * 0.14f
            val x1 = grillX + l / 2; val y1 = y - l * 0.14f
            val o = grillLight.strokeWidth * 0.6f
            canvas.drawLine(x0, y0 + o, x1, y1 + o, grillLight)
            canvas.drawLine(x0, y0, x1, y1, grillDark)
        }
    }

    /** Rejilla del altavoz: ranuras inclinadas hundidas en la carcasa. */
    private fun drawGrill(canvas: Canvas) {
        val l = grillLen
        for (i in 0 until 6) {
            val dx = (i - 2.5f) * l * 0.3f
            val x0 = grillX + dx - l * 0.22f; val y0 = grillY + l * 0.5f
            val x1 = grillX + dx + l * 0.22f; val y1 = grillY - l * 0.5f
            canvas.drawLine(x0, y0 + unit * 0.12f, x1, y1 + unit * 0.12f, grillLight)
            canvas.drawLine(x0, y0, x1, y1, grillDark)
        }
    }

    /** Pieza en relieve: sombra, cara con degradado y canto oscuro; al pulsar se hunde. */
    private inline fun bevel(canvas: Canvas, bounds: RectF, down: Boolean, corner: Float, content: () -> Unit) {
        val base = consoleStyle.buttons
        val depth = unit * 0.28f
        val effect = if (corner > 0f) CornerPathEffect(corner) else null
        dropShadow.pathEffect = effect; faceFill.pathEffect = effect; faceEdge.pathEffect = effect
        if (!down) { canvas.save(); canvas.translate(0f, depth); canvas.drawPath(shape, dropShadow); canvas.restore() }
        canvas.save()
        if (down) canvas.translate(0f, depth * 0.6f)
        faceFill.shader = android.graphics.LinearGradient(0f, bounds.top, 0f, bounds.bottom,
            if (down) shade(base, 0.84f) else lighten(base, 0.16f), if (down) shade(base, 0.76f) else shade(base, 0.86f),
            android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(shape, faceFill)
        faceEdge.color = shade(base, 0.55f)
        canvas.drawPath(shape, faceEdge)
        dropShadow.pathEffect = null; faceFill.pathEffect = null; faceEdge.pathEffect = null
        content()
        canvas.restore()
    }

    /** Texto grabado: sombra clara debajo y trazo oscuro encima. */
    private fun engraved(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float) {
        val base = consoleStyle.buttons
        engrave.textSize = size; engraveLight.textSize = size
        engrave.color = shade(base, 0.58f)
        engraveLight.color = (lighten(base, 0.5f) and 0x00FFFFFF) or 0xB0000000.toInt()
        val y = cy + size * 0.36f
        canvas.drawText(label, cx, y + maxOf(1f, size * 0.05f), engraveLight)
        canvas.drawText(label, cx, y, engrave)
    }

    private fun drawClassicDpad(canvas: Canvas) {
        val arm = padR * 0.34f
        shape.reset()
        shape.moveTo(padX - arm, padY - padR); shape.lineTo(padX + arm, padY - padR)
        shape.lineTo(padX + arm, padY - arm); shape.lineTo(padX + padR, padY - arm)
        shape.lineTo(padX + padR, padY + arm); shape.lineTo(padX + arm, padY + arm)
        shape.lineTo(padX + arm, padY + padR); shape.lineTo(padX - arm, padY + padR)
        shape.lineTo(padX - arm, padY + arm); shape.lineTo(padX - padR, padY + arm)
        shape.lineTo(padX - padR, padY - arm); shape.lineTo(padX - arm, padY - arm)
        shape.close()
        drawRect.set(padX - padR, padY - padR, padX + padR, padY + padR)
        bevel(canvas, drawRect, false, arm * 0.35f) {
            val cr = arm * 0.35f
            if ((pressed and Button.UP) != 0) canvas.drawRoundRect(padX - arm, padY - padR, padX + arm, padY - arm * 0.3f, cr, cr, pressShade)
            if ((pressed and Button.DOWN) != 0) canvas.drawRoundRect(padX - arm, padY + arm * 0.3f, padX + arm, padY + padR, cr, cr, pressShade)
            if ((pressed and Button.LEFT) != 0) canvas.drawRoundRect(padX - padR, padY - arm, padX - arm * 0.3f, padY + arm, cr, cr, pressShade)
            if ((pressed and Button.RIGHT) != 0) canvas.drawRoundRect(padX + arm * 0.3f, padY - arm, padX + padR, padY + arm, cr, cr, pressShade)
            val a = arm * 0.5f
            val off = padR * 0.68f
            // en horizontal, flechas huecas y hundimiento circular en el centro
            triangle(canvas, padX, padY - off, a, 0, arrowStroke)
            triangle(canvas, padX, padY + off, a, 2, arrowStroke)
            triangle(canvas, padX - off, padY, a, 3, arrowStroke)
            triangle(canvas, padX + off, padY, a, 1, arrowStroke)
            canvas.drawCircle(padX, padY, arm * 0.62f, pressShade)
            canvas.drawCircle(padX, padY, arm * 0.62f, arrowStroke)
        }
    }

    private fun drawClassicButton(canvas: Canvas, b: Btn, on: Boolean) {
        val r = b.rect
        if (b.mask == Button.START || b.mask == Button.SELECT) {
            // rótulo sobre la carcasa y botón redondo pequeño a su derecha
            val dotR = r.height() * 0.36f
            val dotX = r.right - dotR * 1.3f
            val size = r.height() * 0.42f
            shellText.textSize = size; shellTextShadow.textSize = size
            val tx = (r.left + dotX - dotR) / 2; val ty = r.centerY() + size * 0.36f
            canvas.drawText(b.label, tx, ty + maxOf(1f, size * 0.06f), shellTextShadow)
            canvas.drawText(b.label, tx, ty, shellText)
            shape.reset()
            shape.addCircle(dotX, r.centerY(), dotR, Path.Direction.CW)
            drawRect.set(dotX - dotR, r.centerY() - dotR, dotX + dotR, r.centerY() + dotR)
            bevel(canvas, RectF(drawRect), on, 0f) {}
            return
        }
        shape.reset()
        if (b.round) shape.addCircle(r.centerX(), r.centerY(), r.width() / 2, Path.Direction.CW)
        else shape.addRoundRect(r, r.height() / 2, r.height() / 2, Path.Direction.CW)
        bevel(canvas, r, on, 0f) {
            val size = when {
                b.round -> r.width() * 0.42f
                b.mask == Button.START || b.mask == Button.SELECT -> r.height() * 0.36f
                else -> r.height() * 0.5f
            }
            engraved(canvas, b.label, r.centerX(), r.centerY(), size)
        }
    }


    private fun circle(mask: Int, label: String, cx: Float, cy: Float, r: Float) {
        buttons += Btn(mask, label, RectF(cx - r, cy - r, cx + r, cy + r), true)
    }

    private fun pill(mask: Int, label: String, cx: Float, cy: Float, u: Float) {
        buttons += Btn(mask, label, RectF(cx - u * 0.75f, cy - u * 0.30f, cx + u * 0.75f, cy + u * 0.30f), false)
    }

    // ---------------------------------------------------------------- dibujo

    override fun onDraw(canvas: Canvas) {
        if (skin) {
            drawSkin(canvas)
        } else if (portrait) {
            canvas.drawRect(0f, panelTop, width.toFloat(), height.toFloat(), panelPaint)
            canvas.drawRect(0f, panelTop, width.toFloat(), panelTop + 3f, sepPaint)
            drawAll(canvas)
        } else {
            val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (landscapeAlpha * 255).toInt())
            drawAll(canvas)
            canvas.restoreToCount(save)
        }
        if (editing) {
            val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 2
                color = 0xFFFFCF70.toInt()
            }
            controls().forEach { (id, rect) ->
                highlight.alpha = if (id == selectedControl) 255 else 80
                canvas.drawRoundRect(rect, 10f, 10f, highlight)
            }
        }
    }

    private fun drawAll(canvas: Canvas) {
        drawToolbar(canvas)
        if (!showButtons) return
        drawDpad(canvas)
        for (b in buttons) drawButton(canvas, b)
    }

    private fun drawDpad(canvas: Canvas) {
        if (classic) { drawClassicDpad(canvas); return }
        if (usingPack) {
            val u = (pressed and Button.UP) != 0; val d = (pressed and Button.DOWN) != 0
            val l = (pressed and Button.LEFT) != 0; val r = (pressed and Button.RIGHT) != 0
            val dir = (if (u) "u" else if (d) "d" else "") + (if (l) "l" else if (r) "r" else "")
            val img = pack?.image(if (dir.isEmpty()) "cross" else "cross_$dir") ?: pack?.image("cross")
            if (img != null) { fitBitmap(canvas, img, RectF(padX - padR, padY - padR, padX + padR, padY + padR)); return }
        }
        val arm = padR * 0.34f
        val cr = arm * 0.35f
        cross(canvas, padX, padY, arm, cr, bodyPaint)
        bodyPaint.shader = null
        val dirs = arrayOf(
            Button.UP to RectF(padX - arm, padY - padR, padX + arm, padY - arm),
            Button.DOWN to RectF(padX - arm, padY + arm, padX + arm, padY + padR),
            Button.LEFT to RectF(padX - padR, padY - arm, padX - arm, padY + arm),
            Button.RIGHT to RectF(padX + arm, padY - arm, padX + padR, padY + arm),
        )
        for ((mask, r) in dirs) {
            if ((pressed and mask) != 0) canvas.drawRoundRect(r, cr, cr, bodyOn)
        }
        // flechas
        val a = arm * 0.45f
        val off = padR * 0.70f
        triangle(canvas, padX, padY - off, a, 0)
        triangle(canvas, padX, padY + off, a, 2)
        triangle(canvas, padX - off, padY, a, 3)
        triangle(canvas, padX + off, padY, a, 1)
        // hueco central
        canvas.drawCircle(padX, padY, arm * 0.45f, shadowPaint)
    }

    private fun cross(canvas: Canvas, x: Float, y: Float, arm: Float, cr: Float, p: Paint) {
        // One continuous silhouette: no seams or intersecting borders in the middle.
        path.reset()
        path.moveTo(x - arm, y - padR)
        path.lineTo(x + arm, y - padR)
        path.lineTo(x + arm, y - arm)
        path.lineTo(x + padR, y - arm)
        path.lineTo(x + padR, y + arm)
        path.lineTo(x + arm, y + arm)
        path.lineTo(x + arm, y + padR)
        path.lineTo(x - arm, y + padR)
        path.lineTo(x - arm, y + arm)
        path.lineTo(x - padR, y + arm)
        path.lineTo(x - padR, y - arm)
        path.lineTo(x - arm, y - arm)
        path.close()
        p.pathEffect = CornerPathEffect(cr)
        canvas.drawPath(path, p)
        p.pathEffect = null
    }

    /** Triángulo relleno; dir: 0 arriba, 1 derecha, 2 abajo, 3 izquierda. */
    private fun triangle(canvas: Canvas, cx: Float, cy: Float, s: Float, dir: Int, p: Paint = labelPaint) {
        path.reset()
        when (dir) {
            0 -> { path.moveTo(cx, cy - s * 0.6f); path.lineTo(cx + s * 0.6f, cy + s * 0.4f); path.lineTo(cx - s * 0.6f, cy + s * 0.4f) }
            2 -> { path.moveTo(cx, cy + s * 0.6f); path.lineTo(cx + s * 0.6f, cy - s * 0.4f); path.lineTo(cx - s * 0.6f, cy - s * 0.4f) }
            1 -> { path.moveTo(cx + s * 0.6f, cy); path.lineTo(cx - s * 0.4f, cy - s * 0.6f); path.lineTo(cx - s * 0.4f, cy + s * 0.6f) }
            else -> { path.moveTo(cx - s * 0.6f, cy); path.lineTo(cx + s * 0.4f, cy - s * 0.6f); path.lineTo(cx + s * 0.4f, cy + s * 0.6f) }
        }
        path.close()
        canvas.drawPath(path, p)
    }

    private fun drawButton(canvas: Canvas, b: Btn) {
        val on = (pressed and b.mask) != 0
        val r = b.rect
        if (classic) { drawClassicButton(canvas, b, on); return }
        if (usingPack) {
            val key = b.label.lowercase()
            val img = (if (on) pack?.image("${key}_pressed") else null) ?: pack?.image(key)
            if (img != null) { fitBitmap(canvas, img, r); return }
        }
        if (b.round) {
            val rad = r.width() / 2
            val p = if (on) bodyOn else bodyPaint
            canvas.drawCircle(r.centerX(), r.centerY(), rad, p)
            textPaint.textSize = rad * 0.8f
            canvas.drawText(b.label, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
        } else {
            val rr = r.height() / 2
            val small = b.mask == Button.START || b.mask == Button.SELECT
            canvas.drawRoundRect(r, rr, rr, if (on) bodyOn else bodyPaint)
            if (small) {
                textPaint.textSize = r.height() * 0.33f
                canvas.drawText(b.label, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
            } else {
                textPaint.textSize = r.height() * 0.5f
                canvas.drawText(b.label, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
            }
        }
    }

    /**
     * Barra de accesos en cápsula: los accesos que están en fila (aunque se hayan movido en el
     * editor) comparten una píldora oscura; cada uno es un botón redondo y el menú, un círculo
     * blanco con el icono oscuro.
     */
    private fun drawToolbar(canvas: Canvas) {
        val sorted = tools.sortedBy { it.rect.centerX() }
        val used = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (used[i]) continue
            used[i] = true
            val first = sorted[i]
            val d = minOf(first.rect.width(), first.rect.height())
            var right = first; var top = first.rect.centerY() - d / 2; var bottom = first.rect.centerY() + d / 2
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val t = sorted[j]
                val dj = minOf(t.rect.width(), t.rect.height())
                val sameRow = abs(t.rect.centerY() - first.rect.centerY()) < d * 0.5f
                val near = t.rect.centerX() - right.rect.centerX() < (d + dj) * 1.05f
                if (sameRow && near) {
                    used[j] = true; right = t
                    top = minOf(top, t.rect.centerY() - dj / 2); bottom = maxOf(bottom, t.rect.centerY() + dj / 2)
                }
            }
            val pad = d * 0.16f
            val rd = minOf(right.rect.width(), right.rect.height())
            capRect.set(first.rect.centerX() - d / 2 - pad, top - pad, right.rect.centerX() + rd / 2 + pad, bottom + pad)
            val rr = capRect.height() / 2
            canvas.save(); canvas.translate(0f, capRect.height() * 0.06f)
            canvas.drawRoundRect(capRect, rr, rr, capShadow); canvas.restore()
            canvas.drawRoundRect(capRect, rr, rr, capFill)
            canvas.drawRoundRect(capRect, rr, rr, capEdge)
        }
        for (t in tools) drawTool(canvas, t)
    }

    private fun drawTool(canvas: Canvas, t: Tool) {
        val r = t.rect
        val active = (t.action == ACT_FF && fastForward) || (t.action == ACT_REWIND && rewindHeld)
        val down = toolDown == t.action
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = minOf(r.width(), r.height()) / 2
        val col = if (t.action == ACT_MENU) {
            capMenu.color = if (down) 0xFFCFCFD6.toInt() else 0xFFF4F4F7.toInt()
            canvas.drawCircle(cx, cy, rad, capMenu)
            0xFF17171A.toInt()
        } else {
            if (down || active) { capPress.color = if (active) 0x40D2BBFF else 0x30FFFFFF; canvas.drawCircle(cx, cy, rad, capPress) }
            if (active) ACCENT else 0xFFF2F2F5.toInt()
        }
        iconPaint.color = col; iconFill.color = col
        val s = rad * 0.95f
        iconPaint.strokeWidth = s * 0.13f
        when (t.action) {
            ACT_MENU -> for (k in -1..1) {
                val y = cy + k * s * 0.32f
                canvas.drawLine(cx - s * 0.45f, y, cx + s * 0.45f, y, iconPaint)
            }
            ACT_SAVE, ACT_LOAD -> {
                // bandeja con flecha hacia dentro (guardar) o hacia fuera (cargar)
                val tw = s * 0.42f
                path.reset()
                path.moveTo(cx - tw, cy + s * 0.05f); path.lineTo(cx - tw, cy + s * 0.45f)
                path.lineTo(cx + tw, cy + s * 0.45f); path.lineTo(cx + tw, cy + s * 0.05f)
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx, cy - s * 0.5f, cx, cy + s * 0.2f, iconPaint)
                if (t.action == ACT_SAVE) {
                    canvas.drawLine(cx - s * 0.22f, cy - s * 0.02f, cx, cy + s * 0.2f, iconPaint)
                    canvas.drawLine(cx + s * 0.22f, cy - s * 0.02f, cx, cy + s * 0.2f, iconPaint)
                } else {
                    canvas.drawLine(cx - s * 0.22f, cy - s * 0.28f, cx, cy - s * 0.5f, iconPaint)
                    canvas.drawLine(cx + s * 0.22f, cy - s * 0.28f, cx, cy - s * 0.5f, iconPaint)
                }
            }
            ACT_SLOT -> {
                textPaint.textSize = s * 0.75f
                val old = textPaint.color
                textPaint.color = col
                canvas.drawText(slot.toString(), cx, cy + s * 0.12f, textPaint)
                textPaint.color = old
                labelPaint.textSize = s * 0.32f
                val oldLabel = labelPaint.color
                labelPaint.color = (col and 0x00FFFFFF) or 0xB0000000.toInt()
                canvas.drawText("RANURA", cx, cy + s * 0.62f, labelPaint)
                labelPaint.color = oldLabel
            }
            ACT_REWIND -> {
                triangle(canvas, cx - s * 0.2f, cy, s * 0.55f, 3, iconFill)
                triangle(canvas, cx + s * 0.25f, cy, s * 0.55f, 3, iconFill)
            }
            ACT_FF -> {
                triangle(canvas, cx - s * 0.25f, cy, s * 0.55f, 1, iconFill)
                triangle(canvas, cx + s * 0.2f, cy, s * 0.55f, 1, iconFill)
            }
        }
    }

    // ---------------------------------------------------------------- toques

    private fun toolName(action: Int) = when (action) {
        ACT_MENU -> "Menú"
        ACT_SAVE -> "Guardar"
        ACT_LOAD -> "Cargar"
        ACT_SLOT -> "Ranura"
        ACT_REWIND -> "Rebobinar"
        else -> "Avance rápido"
    }

    private fun controls(): Map<String, RectF> = linkedMapOf<String, RectF>().apply {
        put("Cruceta", RectF(padX - padR, padY - padR, padX + padR, padY + padR))
        buttons.forEach { put(it.label, RectF(it.rect)) }
        tools.forEach { put(toolName(it.action), RectF(it.rect)) }
    }

    private fun setControl(id: String, rect: RectF) {
        if (id == "Cruceta") { padX = rect.centerX(); padY = rect.centerY(); padR = rect.width() / 2 }
        else buttons.find { it.label == id }?.rect?.set(rect)
            ?: tools.find { toolName(it.action) == id }?.rect?.set(rect)
    }

    private fun rememberAndApplyControls() {
        defaults.clear(); defaults.putAll(controls())
        defaults.forEach { (id, base) ->
            consoleStyle.placements[orientationKey + id]?.let { p ->
                val hw = base.width() * p.scale / 2
                val hh = base.height() * p.scale / 2
                val x = (p.x * width).coerceIn(hw, width - hw)
                val y = (p.y * height).coerceIn(hh, height - hh)
                val rect = RectF(x - hw, y - hh, x + hw, y + hh)
                if (!RectF.intersects(rect, RectF(screen))) setControl(id, rect)
            }
        }
        selectedControl?.let { id ->
            onSelection(id, consoleStyle.placements[orientationKey + id]?.scale ?: 1f)
        }
    }

    fun selectControl(id: String) {
        selectedControl = id
        onSelection(id, consoleStyle.placements[orientationKey + id]?.scale ?: 1f)
        invalidate()
    }

    fun controlNames() = controls().keys.toTypedArray()

    fun resizeSelected(scale: Float) {
        val id = selectedControl ?: return
        val current = controls()[id] ?: return
        place(id, current.centerX(), current.centerY(), scale)
    }

    private fun place(id: String, cx: Float, cy: Float, scale: Float) {
        val base = defaults[id] ?: return
        val hw = base.width() * scale / 2; val hh = base.height() * scale / 2
        val margin = minOf(width, height) * 0.02f
        if (hw * 2 + margin * 2 > width || hh * 2 + margin * 2 > height) return
        val x = cx.coerceIn(hw + margin, width - hw - margin)
        val y = cy.coerceIn(hh + margin, height - hh - margin)
        val rect = RectF(x - hw, y - hh, x + hw, y + hh)
        if (RectF.intersects(rect, RectF(screen))) return
        setControl(id, rect)
        consoleStyle.placements[orientationKey + id] = ConsoleStyle.Placement(x / width, y / height, scale)
        invalidate()
    }

    fun resetCurrentLayout() {
        consoleStyle.placements.keys.removeAll { it.startsWith(orientationKey) }
        selectedControl = null
        refreshStyle()
    }

    private fun editTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = controls().entries.lastOrNull { it.value.contains(event.x, event.y) }
                if (target != null) {
                    selectControl(target.key)
                    dragX = event.x - target.value.centerX(); dragY = event.y - target.value.centerY()
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else selectedControl = null
            }
            MotionEvent.ACTION_MOVE -> selectedControl?.let { id ->
                place(id, event.x - dragX, event.y - dragY,
                    consoleStyle.placements[orientationKey + id]?.scale ?: 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        invalidate()
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        var mask = 0
        val dx = x - padX
        val dy = y - padY
        val d = hypot(dx, dy)
        if (d < padR * 1.35f && d > padR * 0.15f) {
            if (abs(dx) > d * 0.38f) mask = mask or (if (dx < 0) Button.LEFT else Button.RIGHT)
            if (abs(dy) > d * 0.38f) mask = mask or (if (dy < 0) Button.UP else Button.DOWN)
            return mask
        }
        for (b in buttons) {
            val slop = b.rect.height() * (if (b.round) 0.2f else 0.35f)
            if (x >= b.rect.left - slop && x <= b.rect.right + slop && y >= b.rect.top - slop && y <= b.rect.bottom + slop) {
                mask = mask or b.mask
            }
        }
        return mask
    }

    private fun toolAt(x: Float, y: Float): Int {
        for (t in tools) {
            val sl = t.rect.height() * 0.15f
            if (x >= t.rect.left - sl && x <= t.rect.right + sl && y >= t.rect.top - sl && y <= t.rect.bottom + sl) return t.action
        }
        return -1
    }

    fun releaseInputs() {
        setPressed(0)
        onRewind(false)
        rewindHeld = false
        toolDown = -1
        invalidate()
    }

    private fun setPressed(mask: Int) {
        if (mask == pressed) return
        if ((mask and pressed.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        pressed = mask
        onButtons(mask)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return editTouch(e)
        val action = e.actionMasked
        var mask = 0
        var tool = -1
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val x = e.getX(i); val y = e.getY(i)
                val t = toolAt(x, y)
                if (t >= 0) tool = t else if (showButtons) mask = mask or hit(x, y)
            }
        }
        // Los iconos actúan al soltar; rebobinar funciona mientras se mantiene
        if (tool != toolDown) {
            val prev = toolDown
            val released = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
            if (prev >= 0 && prev != ACT_REWIND && released) onAction(prev)
            if (prev == ACT_REWIND) { rewindHeld = false; onRewind(false) }
            if (tool == ACT_REWIND) { rewindHeld = true; onRewind(true) }
            if (tool >= 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toolDown = tool
        }
        setPressed(mask)
        invalidate()
        return true
    }
}
