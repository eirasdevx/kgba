package com.kgba.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Arranque propio de KGBA, independiente de la BIOS emulada. Se dibuja dentro de la pantalla de
 * la consola (la carcasa queda visible alrededor) y a la resolución real del LCD, 240×160,
 * ampliada sin suavizado para que se vea pixelada como en la portátil:
 * la pantalla se enciende en blanco, un destello recorre el logotipo de izquierda a derecha
 * dejando las letras con degradado violeta → azul, y una estrella brilla al final.
 */
internal class BootView(
    context: Context,
    private val skin: () -> Boolean,
    private val soundOn: () -> Boolean,
    private val complete: () -> Unit,
) : View(context) {
    private val sound = BootSound()
    private val lcd = Bitmap.createBitmap(LCD_W, LCD_H, Bitmap.Config.ARGB_8888)
    private val lcdCanvas = Canvas(lcd)
    private val blit = Paint().apply { isFilterBitmap = false }
    private val screen = Rect()
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        textSize = 62f; textAlign = Paint.Align.LEFT; letterSpacing = -0.04f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Path()
    private var progress = 0f

    // Geometría del logotipo en coordenadas del LCD.
    private val logoW = logoPaint.measureText(LOGO)
    private val logoX = (LCD_W - logoW) / 2f
    private val logoY = 102f
    private val logoGradient = LinearGradient(logoX, 0f, logoX + logoW, 0f,
        intArrayOf(VIOLET, PURPLE, BLUE, BLUE), floatArrayOf(0f, 0.35f, 0.62f, 1f), Shader.TileMode.CLAMP)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
            if (it.animatedFraction >= 1f) complete()
        }
    }

    init {
        isClickable = true
        contentDescription = "KGBA: cargando juego"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (soundOn()) sound.start()
        animator.start()
    }
    fun pause() { animator.pause(); sound.pause() }
    fun resume() { animator.resume(); sound.resume() }
    override fun onDetachedFromWindow() { animator.cancel(); sound.release(); super.onDetachedFromWindow() }

    private fun phase(from: Float, to: Float) = ((progress - from) / (to - from)).coerceIn(0f, 1f)
    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onDraw(canvas: Canvas) {
        Emulator.layoutScreen(width, height, 0, skin(), screen)
        if (screen.isEmpty) return
        drawLcd()
        canvas.drawBitmap(lcd, null, screen, blit)
    }

    private fun drawLcd() {
        val c = lcdCanvas
        // 1. la pantalla se enciende: de apagada a blanco
        c.drawColor(mix(LCD_OFF, Color.WHITE, ease(phase(0f, 0.10f))))
        // 2. el destello recorre el logotipo y lo va dejando a la vista
        val sweep = ease(phase(0.10f, 0.55f))
        val margin = 26f
        val sx = logoX - margin + (logoW + margin * 2) * sweep
        if (sweep > 0f) {
            // letras ya reveladas: primero claras, luego toman el color definitivo
            val settle = ease(phase(0.45f, 0.70f))
            c.save()
            c.clipRect(0f, 0f, sx, LCD_H.toFloat())
            logoPaint.shader = logoGradient
            logoPaint.alpha = (110 + 145 * settle).toInt()
            c.drawText(LOGO, logoX, logoY, logoPaint)
            logoPaint.shader = null
            c.restore()
            // halo brillante en el borde del barrido
            if (sweep < 1f) {
                glowPaint.shader = LinearGradient(sx - 22f, 0f, sx + 4f, 0f,
                    intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
                c.save()
                c.clipRect(sx - 22f, 0f, sx + 4f, LCD_H.toFloat())
                logoPaint.color = 0xFFB9A8FF.toInt()
                c.drawText(LOGO, logoX, logoY, logoPaint)
                c.restore()
                c.drawRect(sx - 22f, logoY - 56f, sx + 4f, logoY + 10f, glowPaint)
                // estrella que acompaña al destello, en arco sobre las letras
                val arc = kotlin.math.sin(sweep * Math.PI).toFloat()
                drawStar(c, sx, logoY - 38f - arc * 16f, 6f + arc * 3f, 1f)
            }
        }
        // 3. destello final sobre la última letra
        val twinkle = phase(0.55f, 0.72f)
        if (twinkle > 0f && twinkle < 1f) {
            val s = kotlin.math.sin(twinkle * Math.PI).toFloat()
            drawStar(c, logoX + logoW - 4f, logoY - 50f, 4f + 8f * s, s)
        }
    }

    /** Estrella de cuatro puntas con núcleo blanco. */
    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float, alpha: Float) {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        val k = r * 0.22f
        star.reset()
        star.moveTo(x, y - r); star.lineTo(x + k, y - k); star.lineTo(x + r, y); star.lineTo(x + k, y + k)
        star.lineTo(x, y + r); star.lineTo(x - k, y + k); star.lineTo(x - r, y); star.lineTo(x - k, y - k)
        star.close()
        starPaint.color = (STAR and 0x00FFFFFF) or (a shl 24)
        c.drawPath(star, starPaint)
        starPaint.color = (0x00FFFFFF) or (a shl 24)
        c.drawCircle(x, y, r * 0.22f, starPaint)
    }

    companion object {
        private const val LCD_W = 240
        private const val LCD_H = 160
        private const val LOGO = "KGBA"
        private const val LCD_OFF = 0xFF8E9A92.toInt()
        private const val VIOLET = 0xFFB04AF0.toInt()
        private const val PURPLE = 0xFF7A2CF0.toInt()
        private const val BLUE = 0xFF1F2FE6.toInt()
        private const val STAR = 0xFFFFE27A.toInt()
    }
}
