package com.kgba.app

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.net.Uri
import android.view.SurfaceView
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import java.io.File

/** Run with :app:connectedDebugAndroidTest. No ROM or network required. */
class ConsoleUiChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        val status = Bundle().apply {
            putString("class", "com.kgba.app.ConsoleUiChecks")
            putString("test", "consoleCustomization")
            putInt("numtests", 1); putInt("current", 1)
            putString("id", "ConsoleUiChecks")
        }
        sendStatus(1, status)
        try {
            runOnMainSync {
                val pad = GamepadView(targetContext)
                pad.consoleStyle = ConsoleStyle()
                fun layout(w: Int, h: Int) {
                    pad.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    pad.layout(0, 0, w, h)
                }
                fun snapshot(name: String) {
                    val bitmap = Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Emulator.BACKGROUND)
                    pad.draw(canvas)
                    File(targetContext.getExternalFilesDir(null), name).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                fun touch(action: Int, x: Float, y: Float) {
                    MotionEvent.obtain(0, 10, action, x, y, 0).also { pad.onTouchEvent(it); it.recycle() }
                }
                // Check the complete image, including every edge, across phone/tablet ratios.
                for ((w, h) in listOf(1920 to 1080, 2400 to 1080, 1280 to 960,
                    960 to 540, 1080 to 2040, 1080 to 1080)) {
                    for (shell in listOf(true, false)) {
                        pad.skin = shell
                        layout(w, h)
                        val screen = Rect()
                        Emulator.layoutScreen(w, h, 0, shell, screen)
                        check(Rect(0, 0, w, h).contains(screen)) { "Display clipped at ${w}x$h: $screen" }
                        check(kotlin.math.abs(screen.width() * 2 - screen.height() * 3) <= 3)
                        if (w >= h) {
                            val oldHeight = minOf(w * 0.56f / 1.5f, h * 0.78f)
                            check(screen.height() > oldHeight * 1.1f) { "Landscape display still too small" }
                        }
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawRect(screen, Paint().apply { color = Color.MAGENTA })
                        pad.draw(canvas)
                        for (y in screen.top + 2 until screen.bottom - 2) {
                            for (x in screen.left + 2 until screen.right - 2) {
                                check(bitmap.getPixel(x, y) == Color.MAGENTA) {
                                    "Console covers game at $x,$y in ${w}x$h (skin=$shell)"
                                }
                            }
                        }
                        bitmap.recycle()
                    }
                }
                pad.skin = true
                layout(1080, 2040)
                snapshot("console-portrait.png")
                pad.editing = true
                pad.selectControl("A")
                pad.resizeSelected(1.2f)
                check(pad.consoleStyle.placements["portrait:A"]?.scale == 1.2f)
                val before = pad.consoleStyle.placements.getValue("portrait:A")
                touch(MotionEvent.ACTION_DOWN, before.x * pad.width, before.y * pad.height)
                touch(MotionEvent.ACTION_MOVE, before.x * pad.width - 24, before.y * pad.height + 24)
                touch(MotionEvent.ACTION_UP, before.x * pad.width - 24, before.y * pad.height + 24)
                val moved = pad.consoleStyle.placements.getValue("portrait:A")
                check(moved.x < before.x && moved.y > before.y) { "Dragging did not move A" }
                pad.editing = false
                var input = 0
                pad.onButtons = { input = it }
                touch(MotionEvent.ACTION_DOWN, moved.x * pad.width, moved.y * pad.height)
                check(input and com.kgba.core.Button.A != 0) { "Customized A does not receive input" }
                touch(MotionEvent.ACTION_CANCEL, 0f, 0f)
                check(input == 0) { "Cancel left a held button" }
                layout(2040, 1080)
                snapshot("console-landscape.png")
                pad.editing = true
                pad.selectControl("B"); pad.resizeSelected(0.85f)
                check(pad.consoleStyle.placements.containsKey("landscape:B"))
                check(pad.consoleStyle.placements.getValue("portrait:A") == moved)
                val restored = ConsoleStyle.decode(pad.consoleStyle.encode())
                check(restored.placements == pad.consoleStyle.placements) { "Layout round trip failed" }
                pad.resetCurrentLayout()
                check(!pad.consoleStyle.placements.containsKey("landscape:B"))
                check(pad.consoleStyle.placements.getValue("portrait:A") == moved)
                layout(1080, 2040)
                pad.consoleStyle = restored; pad.refreshStyle()
                pad.editing = false
                touch(MotionEvent.ACTION_DOWN, moved.x * pad.width, moved.y * pad.height)
                check(input and com.kgba.core.Button.A != 0) { "Restored input position incorrect" }
                pad.releaseInputs(); check(input == 0)
            }
            fun screenshot(name: String) {
                waitForIdleSync()
                Thread.sleep(500)
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(targetContext.getExternalFilesDir(null), name).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            val home = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            screenshot("library.png")
            fun find(view: View, label: String): View? {
                if (view is TextView && view.text.toString() == label) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i), label)?.let { return it }
                }
                return null
            }
            runOnMainSync {
                checkNotNull(find(home.window.decorView, "AJUSTES")).performClick()
                checkNotNull(find(home.window.decorView, "Personalizar consola"))
            }
            screenshot("settings.png")
            runOnMainSync { home.finish() }
            val activity = startActivitySync(Intent(targetContext, ConsoleEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun verifyEditorBounds() {
                runOnMainSync {
                    fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
                    val views = all(activity.window.decorView)
                    val pad = views.filterIsInstance<GamepadView>().single()
                    val panel = views.filterIsInstance<ScrollView>().single()
                    val screen = Rect()
                    Emulator.layoutScreen(pad.width, pad.height, 0, true, screen)
                    check(panel.width == screen.width() && panel.height == screen.height()) {
                        "Editor does not fit game display: ${panel.width}x${panel.height}, expected $screen"
                    }
                    check(panel.left - pad.left == screen.left && panel.top - pad.top == screen.top)
                }
            }
            screenshot("console-editor.png")
            verifyEditorBounds()
            runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            screenshot("console-editor-landscape.png")
            verifyEditorBounds()
            runOnMainSync { activity.finish() }
            // A tiny original mode-3 ROM exercises the real SurfaceView through rotation.
            val rom = File(targetContext.cacheDir, "display-check.gba")
            val bytes = ByteArray(0x100 + 240 * 160 * 2)
            val code = longArrayOf(0xE3A00301, 0xE59F101C, 0xE1C010B0, 0xE3A00406,
                0xE59F1014, 0xE3A02C96, 0xE0D130B2, 0xE0C030B2, 0xE2522001,
                0x1AFFFFFB, 0xEAFFFFFE, 0x00000403, 0x08000100)
            // Literal loads point to the two words following the infinite loop.
            code[1] = 0xE59F1020
            code[4] = 0xE59F1018
            code.forEachIndexed { i, word ->
                repeat(4) { b -> bytes[i * 4 + b] = (word shr (b * 8)).toByte() }
            }
            for (y in 0 until 160) for (x in 0 until 240) {
                val color = when {
                    x < 4 || y < 4 || x >= 236 || y >= 156 -> 0x7FFF
                    x < 24 && y < 24 -> 0x001F
                    x >= 216 && y < 24 -> 0x03E0
                    x < 24 && y >= 136 -> 0x7C00
                    x >= 216 && y >= 136 -> 0x03FF
                    x % 24 == 0 || y % 16 == 0 -> 0x318C
                    else -> 0x2084
                }
                val offset = 0x100 + (y * 240 + x) * 2
                bytes[offset] = color.toByte(); bytes[offset + 1] = (color shr 8).toByte()
            }
            rom.writeBytes(bytes)
            val game = startActivitySync(Intent(targetContext, EmulatorActivity::class.java)
                .setData(Uri.fromFile(rom)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(3000)
            for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                "reverse-landscape" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                "portrait-return" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                runOnMainSync { game.requestedOrientation = orientation }
                Thread.sleep(900)
                screenshot("game-$name.png")
                val rect = Rect()
                runOnMainSync {
                    fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
                    val views = all(game.window.decorView)
                    val pad = views.filterIsInstance<GamepadView>().single()
                    val surface = views.filterIsInstance<SurfaceView>().single()
                    check(surface.width == pad.width && surface.height == pad.height)
                    check(surface.holder.surfaceFrame.width() == pad.width)
                    check(surface.holder.surfaceFrame.height() == pad.height)
                    Emulator.layoutScreen(pad.width, pad.height, 0, pad.skin, rect)
                    val location = IntArray(2)
                    pad.getLocationOnScreen(location)
                    rect.offset(location[0], location[1])
                }
                val image = checkNotNull(uiAutomation.takeScreenshot())
                // The white border must remain visible on all four sides of the actual game.
                for ((x, y) in listOf(rect.left + 2 to rect.centerY(), rect.right - 3 to rect.centerY(),
                    rect.centerX() to rect.top + 2, rect.centerX() to rect.bottom - 3)) {
                    val color = image.getPixel(x, y)
                    check(Color.red(color) > 230 && Color.green(color) > 230 && Color.blue(color) > 230) {
                        "Game border missing after $name rotation at $x,$y: $color"
                    }
                }
                image.recycle()
            }
            runOnMainSync { game.finish() }
            rom.delete()
            result.putString("stream", "Console UI checks passed: full image, aspect ratio, real game rotation, drag, resize, touch, cancel, persistence, reset and editor.\n")
            sendStatus(0, status)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            status.putString("stack", error.stackTraceToString())
            sendStatus(-2, status)
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
