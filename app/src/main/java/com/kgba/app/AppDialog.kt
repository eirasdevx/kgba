package com.kgba.app

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.database.DataSetObserver
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** KGBA owns the entire panel: header, scrolling content, choices and persistent actions. */
internal class AppDialog private constructor(context: Context) : Dialog(context) {
    private val buttons = mutableMapOf<Int, Button>()
    private var outsideCancel = true
    private var canCancel = true
    fun getButton(which: Int): Button = buttons.getValue(which)
    override fun setCancelable(flag: Boolean) { canCancel = flag; super.setCancelable(flag) }
    override fun setCanceledOnTouchOutside(cancel: Boolean) {
        outsideCancel = cancel
        super.setCanceledOnTouchOutside(cancel)
    }

    class Builder(private val context: Context) {
        private var title: CharSequence = "KGBA"
        private var message: CharSequence? = null
        private var view: View? = null
        private var content: ((AppDialog, LinearLayout) -> Unit)? = null
        private val actions = linkedMapOf<Int, Pair<CharSequence, DialogInterface.OnClickListener?>>()
        private var dismiss: DialogInterface.OnDismissListener? = null
        private var cancel: DialogInterface.OnCancelListener? = null
        private var key: DialogInterface.OnKeyListener? = null
        private var cancelable = true

        fun setTitle(value: CharSequence) = apply { title = value }
        fun setTitle(value: Int) = setTitle(context.getText(value))
        fun setMessage(value: CharSequence) = apply { message = value }
        fun setView(value: View) = apply { view = value }
        fun setCancelable(value: Boolean) = apply { cancelable = value }
        fun setOnDismissListener(value: DialogInterface.OnDismissListener) = apply { dismiss = value }
        fun setOnCancelListener(value: DialogInterface.OnCancelListener) = apply { cancel = value }
        fun setOnKeyListener(value: DialogInterface.OnKeyListener) = apply { key = value }
        fun setPositiveButton(label: CharSequence, action: DialogInterface.OnClickListener?) = apply { actions[DialogInterface.BUTTON_POSITIVE] = label to action }
        fun setPositiveButton(label: Int, action: DialogInterface.OnClickListener?) = setPositiveButton(context.getText(label), action)
        fun setNegativeButton(label: CharSequence, action: DialogInterface.OnClickListener?) = apply { actions[DialogInterface.BUTTON_NEGATIVE] = label to action }
        fun setNegativeButton(label: Int, action: DialogInterface.OnClickListener?) = setNegativeButton(context.getText(label), action)
        fun setNeutralButton(label: CharSequence, action: DialogInterface.OnClickListener?) = apply { actions[DialogInterface.BUTTON_NEUTRAL] = label to action }
        fun setNeutralButton(label: Int, action: DialogInterface.OnClickListener?) = setNeutralButton(context.getText(label), action)

        fun setItems(labels: Array<String>, action: DialogInterface.OnClickListener) = apply {
            content = { dialog, body -> labels.forEachIndexed { i, label ->
                body.addView(choice(label, false, false) { action.onClick(dialog, i); dialog.dismiss() })
            } }
        }

        fun setSingleChoiceItems(labels: Array<String>, selected: Int, action: DialogInterface.OnClickListener) = apply {
            content = { dialog, body -> labels.forEachIndexed { i, label ->
                body.addView(choice(label, i == selected, true) { action.onClick(dialog, i) })
            } }
        }

        fun setMultiChoiceItems(labels: Array<String>, checked: BooleanArray, action: DialogInterface.OnMultiChoiceClickListener) = apply {
            content = { dialog, body ->
                fun render() {
                    body.removeAllViews()
                    labels.forEachIndexed { i, label ->
                        body.addView(choice(label, checked[i], true) {
                            checked[i] = !checked[i]
                            action.onClick(dialog, i, checked[i]); render()
                        })
                    }
                }
                render()
            }
        }

        fun setAdapter(adapter: ListAdapter, action: DialogInterface.OnClickListener) = apply {
            content = { dialog, body ->
                fun render() {
                    body.removeAllViews()
                    for (i in 0 until adapter.count) body.addView(choice(adapter.getItem(i).toString(), false, false) {
                        action.onClick(dialog, i); dialog.dismiss()
                    })
                }
                val observer = object : DataSetObserver() { override fun onChanged() = render() }
                body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { adapter.registerDataSetObserver(observer); render() }
                    override fun onViewDetachedFromWindow(v: View) { adapter.unregisterDataSetObserver(observer) }
                })
                render()
            }
        }

        private fun choice(label: String, selected: Boolean, selectable: Boolean, action: () -> Unit): View {
            val ui = AppUi(context)
            return ui.line().apply {
                minimumHeight = ui.dp(56)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                background = ui.ripple(ui.shape(if (selected) AppUi.PRIMARY_CONTAINER else AppUi.PANEL,
                    16, if (selected) AppUi.PRIMARY else AppUi.BORDER))
                isFocusable = true
                isSelected = selected
                contentDescription = label + if (selected) ", seleccionado" else ""
                addView(ui.text(label, 15, selected, if (selected) AppUi.ON_PRIMARY_CONTAINER else AppUi.TEXT),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.text(if (selectable) { if (selected) "✓" else "○" } else "›", 20,
                    color = if (selected) AppUi.ON_PRIMARY_CONTAINER else AppUi.PRIMARY),
                    LinearLayout.LayoutParams(ui.dp(28), -2).apply { marginStart = ui.dp(8) })
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
            }
        }

        fun create(): AppDialog {
            val ui = AppUi(context)
            val dialog = AppDialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(cancelable)
            dialog.setOnDismissListener(dismiss)
            dialog.setOnCancelListener(cancel)
            dialog.setOnKeyListener(key)
            val root = FrameLayout(context).apply {
                setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
                setOnClickListener { if (dialog.canCancel && dialog.outsideCancel) dialog.cancel() }
            }
            val panel = object : LinearLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), ui.dp(560)), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST))
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(16))
                background = ui.shape(AppUi.BG, 28, AppUi.OUTLINE_VARIANT)
                elevation = ui.dp(16).toFloat()
                setOnClickListener { /* Consume taps inside the panel. */ }
            }
            panel.addView(ui.mono("KGBA  /  ${if (context is EmulatorActivity) "EN PAUSA" else "OPCIONES"}", 10, AppUi.TERTIARY))
            panel.addView(ui.headline(title.toString(), 23).apply {
                setPadding(0, ui.dp(10), 0, ui.dp(18))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                accessibilityHeadingCompat()
            })
            val body = ui.column()
            message?.let { body.addView(ui.text(it.toString(), 15, color = AppUi.MUTED).apply { setPadding(0, 0, 0, ui.dp(12)) }) }
            view?.let {
                if (it is EditText) { it.setTextColor(AppUi.TEXT); it.setHintTextColor(AppUi.OUTLINE); it.backgroundTintList = android.content.res.ColorStateList.valueOf(AppUi.PRIMARY) }
                body.addView(it)
            }
            content?.invoke(dialog, body)
            val scroll = ScrollView(context).apply { isFillViewport = false; addView(body); clipToPadding = false }
            panel.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
            val footer = ui.column()
            actions.forEach { (which, entry) ->
                val button = ui.button(entry.first.toString(), which == DialogInterface.BUTTON_POSITIVE) {
                    entry.second?.onClick(dialog, which); dialog.dismiss()
                }
                dialog.buttons[which] = button
                footer.addView(button)
            }
            if (actions.isEmpty() && cancelable) footer.addView(ui.button("Cerrar") { dialog.cancel() })
            panel.addView(footer)
            root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.64f)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            return dialog
        }

        fun show(): AppDialog = create().also { it.show(); it.window?.setLayout(-1, -1) }
    }
}

private fun View.accessibilityHeadingCompat() {
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}
