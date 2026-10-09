package app.party.wpnative

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** ☰ wala dropdown menu (anchor ke neeche, right side se align). */
fun showDropMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
    val ctx = anchor.context
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * d).toInt()
    val width = dp(230)

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.argb(245, 30, 20, 58))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.argb(60, 255, 255, 255))
        }
    }
    val popup = PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    popup.elevation = dp(8).toFloat()

    items.forEach { (label, action) ->
        box.addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener {
                popup.dismiss()
                action()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    popup.showAsDropDown(anchor, anchor.width - width, dp(6))
}

/** Pakka karne ka dialog. Haan dabane par action chalega. */
fun confirmThen(ctx: Context, title: String, msg: String, action: () -> Unit) {
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton("Haan, clear karo") { _, _ -> action() }
        .setNegativeButton("Nahi", null)
        .show()
}

/** Naam poochhne ka dialog (apna naam badlo / naya dost jodo). */
fun askTextDialog(ctx: Context, title: String, hint: String, current: String, onOk: (String) -> Unit) {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * d).toInt()

    val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(18))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#21132f"))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.parseColor("#6f4686"))
        }
    }
    panel.addView(TextView(ctx).apply {
        text = title
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })

    val input = EditText(ctx).apply {
        setText(current)
        setSelection(current.length)
        hint = hint
        setHintTextColor(Color.parseColor("#8b7fb0"))
        setTextColor(Color.WHITE)
        textSize = 14f
        setSingleLine(true)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#150f26"))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.parseColor("#85619a"))
        }
    }
    panel.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(14); bottomMargin = dp(16)
    })

    val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    fun btn(label: String, bg: String, fn: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.parseColor("#85619a"))
        }
        setOnClickListener { fn() }
    }
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(btn("Cancel", "#342040") { dlg.dismiss() },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(btn("Save", "#a855f7") { dlg.dismiss(); onOk(input.text.toString().trim()) },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
    panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val wrap = FrameLayout(ctx).apply {
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(panel)
    }
    dlg.setContentView(wrap)
    dlg.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dlg.show()
}

/**
 * Website wala "Remove <peer>?" dialog (party-final1.html ka #dm-remove-dialog).
 * Panel: #21132f bg, #6f4686 border, radius 22, padding 24.
 * Buttons: Cancel (#342040) aur Remove Friend (#9c2853) — bilkul website jaisa.
 */
fun removeFriendDialog(ctx: Context, name: String, onRemove: () -> Unit) {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * d).toInt()

    val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#21132f"))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.parseColor("#6f4686"))
        }
    }
    panel.addView(TextView(ctx).apply {
        text = "Remove $name?"
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
    })
    panel.addView(TextView(ctx).apply {
        text = "Friend aur uski poori chat, photo/GIF/voice-note aur call history tumhare phone se delete hogi. " +
               "Dobara add karne par purani history wapas nahi aayegi. Ye block nahi hai."
        textSize = 13f
        setTextColor(Color.parseColor("#c5b4d3"))
        setLineSpacing(0f, 1.6f)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(14); bottomMargin = dp(20)
    })

    val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

    fun btn(label: String, bg: String, fn: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.parseColor("#85619a"))
        }
        setOnClickListener { fn() }
    }

    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(btn("Cancel", "#342040") { dlg.dismiss() },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(btn("Remove Friend", "#9c2853") { dlg.dismiss(); onRemove() },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
    panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val wrap = FrameLayout(ctx).apply {
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(panel)
    }
    dlg.setContentView(wrap)
    dlg.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dlg.show()
}
