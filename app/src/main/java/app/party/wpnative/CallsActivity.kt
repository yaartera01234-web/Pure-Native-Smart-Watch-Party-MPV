package app.party.wpnative

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Calls page (website #yp-calls ka design). Demo data hai, nav mein Call active.
 */
class CallsActivity : Activity() {

    private data class CallItem(
        val name: String,
        val dir: String,
        val arrow: String,
        val missed: Boolean,
        val date: String,
        val time: String,
        val color: Int
    )

    // Demo data (asli call history baad mein)
    private val calls = mutableListOf(
        CallItem("Dost 1", "Incoming · 2:37", "↙", false, "7 Oct", "6:02 PM", Color.parseColor("#f472b6")),
        CallItem("Dost 2", "Outgoing · 43:32", "↗", false, "7 Oct", "5:59 PM", Color.parseColor("#38bdf8")),
        CallItem("Dost 3", "Outgoing · 7:34", "↗", false, "7 Oct", "1:09 AM", Color.parseColor("#fb7185")),
        CallItem("Dost 4", "Call nahi lagi", "↗", true, "6 Oct", "2:36 PM", Color.parseColor("#a78bfa")),
        CallItem("Dost 3", "Outgoing · 16:28", "↗", false, "6 Oct", "11:01 PM", Color.parseColor("#fb7185")),
    )

    private lateinit var listBox: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun hex(s: String): Int = Color.parseColor(s)
    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        // Website ka dm-sheet radial background (Messages jaisa)
        root.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setColors(intArrayOf(hex("#1a1a2e"), hex("#0f0b20"), hex("#050507")))
            setGradientCenter(0.5f, 0f)
            gradientRadius = resources.displayMetrics.heightPixels * 0.75f
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(14))
        }
        fillList()
        val scroll = ScrollView(this).apply { addView(listBox) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(buildNav(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundBox(Color.argb(8, 255, 255, 255), Color.argb(15, 255, 255, 255), 0, 1)
        }
        bar.addView(squareBtn("←", gradient = false) { finish() }, lp(dp(40), dp(40)))

        // 📞 emoji ki jagah wahi vector phone icon jo chat ke header mein hai
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(WpIcon(this@CallsActivity, "phone"), lp(dp(18), dp(18)))
            addView(TextView(this@CallsActivity).apply {
                text = "Calls"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(7) })
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        val menuBtn = squareBtn("☰", gradient = true) {}
        menuBtn.setOnClickListener { showCallMenu(menuBtn) }
        bar.addView(menuBtn, lp(dp(40), dp(40)).apply { leftMargin = dp(8) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        return bar
    }

    private fun squareBtn(label: String, gradient: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (gradient) hex("#0d0716") else Color.WHITE)
        background = if (gradient) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(hex("#54e8ff"), hex("#8b72ff")))
                .apply { cornerRadius = dp(12).toFloat() }
        } else {
            roundBox(Color.argb(31, 255, 255, 255), Color.argb(0, 255, 255, 255), 12, 0)
        }
        setOnClickListener { onClick() }
    }

    private fun buildRow(c: CallItem): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(11), dp(6), dp(11))
        }

        row.setOnLongClickListener { showItemMenu(row, c); true }

        // Avatar: asli DP (image aaane tak pehla harf)
        row.addView(DpStore.circle(this, c.name, c.color, 48), lp(dp(48), dp(48)))

        // Naam + direction
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        info.addView(TextView(this).apply {
            text = c.name
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        info.addView(TextView(this).apply {
            text = "${c.arrow} ${c.dir}"
            textSize = 12f
            setTextColor(if (c.missed) hex("#fb7185") else hex("#a291c6"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Date + time
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(TextView(this).apply {
            text = c.date
            textSize = 11f
            gravity = Gravity.END
            setTextColor(hex("#8b78b3"))
        })
        right.addView(TextView(this).apply {
            text = c.time
            textSize = 11f
            gravity = Gravity.END
            setTextColor(hex("#8b78b3"))
        })
        row.addView(right, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Green call button — andar wahi vector phone icon (chat wale jaisa, emoji nahi)
        row.addView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#22c55e"), hex("#10b981"))).apply { shape = GradientDrawable.OVAL }
            addView(WpIcon(this@CallsActivity, "phone"), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { toast("Call agle step mein") }
        }, lp(dp(42), dp(42)).apply { leftMargin = dp(12) })

        wrap.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(View(this).apply { setBackgroundColor(Color.argb(14, 255, 255, 255)) },
            lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrap
    }

    /** Neeche ka nav: shared BottomNav.kt, Call active. */
    private fun buildNav(): View = buildBottomNav(this, "call")

    /** List ko calls ke hisaab se dobara bharta hai (khali ho to message). */
    private fun fillList() {
        listBox.removeAllViews()
        if (calls.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Koi call nahi"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(hex("#8f7cb5"))
                setPadding(0, dp(40), 0, 0)
            }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            calls.forEach { listBox.addView(buildRow(it)) }
        }
    }

    /** Call par zor se press: is ek history ko clear karo. */
    private fun showItemMenu(anchor: View, c: CallItem) {
        showDropMenu(anchor, listOf(
            "🗑️  Ye history clear karo" to {
                confirmThen(this, "Ye call history clear karein?", "${c.name} ki ye ek call hat jayegi.") {
                    calls.remove(c)
                    fillList()
                    toast("Call history clear ho gayi")
                }
            }
        ))
    }

    /** Call ☰ menu: Call history clear + Clear missed calls only (confirm ke saath). */
    private fun showCallMenu(anchor: View) {
        showDropMenu(anchor, listOf(
            "🗑️  Call history clear" to {
                confirmThen(this, "Call history clear karein?", "Saari calls hat jayengi.") {
                    calls.clear()
                    fillList()
                    toast("Call history clear ho gayi")
                }
            },
            "📵  Clear missed calls only" to {
                confirmThen(this, "Missed calls clear karein?", "Sirf missed calls hatengi.") {
                    calls.removeAll { it.missed }
                    fillList()
                    toast("Missed calls clear ho gayi")
                }
            }
        ))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }
}
