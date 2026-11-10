package com.kgba.app

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager

/**
 * The renderer, controls and editor share one edge-to-edge viewport. The console shell is drawn
 * under the camera cutout too; only the game image and the controls avoid it
 * (see [Emulator.cutout]).
 */
internal object GameWindow {
    fun configure(window: Window, root: View, onCutout: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            // Hidden system bars must not shrink the game. Transient bars overlay it.
            val safe = when {
                Build.VERSION.SDK_INT >= 30 -> insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                    .let { Rect(it.left, it.top, it.right, it.bottom) }
                Build.VERSION.SDK_INT >= 28 -> insets.displayCutout
                    ?.let { Rect(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom) } ?: Rect()
                else -> Rect()
            }
            view.setPadding(0, 0, 0, 0)
            if (safe != Emulator.cutout) {
                Emulator.cutout = safe
                onCutout()
            }
            insets
        }
        root.requestApplyInsets()
    }
}
