package app.party.wpnative

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
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
