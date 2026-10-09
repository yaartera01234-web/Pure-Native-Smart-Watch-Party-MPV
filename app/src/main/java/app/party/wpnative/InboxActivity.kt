package app.party.wpnative

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * AGLA PAGE: Messages / Inbox (website-v61 wali #dm-sheet > #dm-view-inbox ka design).
 * Abhi demo data hai. Asli chats, friends aur E2E baad ke steps mein.
 */
class InboxActivity : Activity() {

    private data class Friend(
        val name: String,
        val last: String,
        val time: String,
        val online: Boolean,
        val unread: Boolean,
        val color: Int,
        val pinned: Boolean = false
    )

    // Demo data (asli dost list baad mein)
    private val chats = mutableListOf(
        Friend("Dost 1", "Ok", "12:31 AM", false, false, hex("#f472b6")),
        Friend("Dost 2", "Ok", "Kal", true, true, hex("#38bdf8")),
        Friend("Dost 3", "Hi", "06/10", false, false, hex("#fb7185")),
        Friend("Dost 4", "Ok", "06/10", false, false, hex("#a78bfa"))
    )

    private lateinit var listBox: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun hex(s: String): Int = Color.parseColor(s)
    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        // Website ka dm-sheet radial background
        root.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setColors(intArrayOf(hex("#1a1a2e"), hex("#0f0b20"), hex("#050507")))
            setGradientCenter(0.5f, 0f)
            gradientRadius = resources.displayMetrics.heightPixels * 0.75f
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(buildSearch(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(14))
        }
        list.addView(TextView(this).apply {
            text = "CHATS"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setTextColor(hex("#A78BFA"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), dp(10), 0, dp(7))
        })
        listBox = list
        fillInbox()

        val scroll = ScrollView(this).apply { addView(list) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(buildBottomBar(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
        bar.addView(TextView(this).apply {
            text = "💬 Messages"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(squareBtn("👤+", gradient = true), lp(dp(40), dp(40)).apply { leftMargin = dp(6) })
        val menuBtn = squareBtn("☰", gradient = true)
        menuBtn.setOnClickListener { showChatMenu(menuBtn) }
        bar.addView(menuBtn, lp(dp(40), dp(40)).apply { leftMargin = dp(6) })
        return bar
    }

    private fun squareBtn(label: String, gradient: Boolean): TextView = TextView(this).apply {
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
        setOnClickListener {
            Toast.makeText(this@InboxActivity, "Agle step mein", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSearch(): View {
        val wrap = FrameLayout(this).apply { setPadding(dp(12), dp(9), dp(12), dp(6)) }
        val input = EditText(this).apply {
            hint = "Naam ya code se dhoondo..."
            setHintTextColor(hex("#8b84a8"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), 0, dp(12), 0)
            background = roundBox(Color.argb(15, 255, 255, 255), Color.argb(20, 255, 255, 255), 11, 1)
        }
        wrap.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        return wrap
    }

    private fun buildRow(f: Friend): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(14), dp(12))
            background = roundBox(
                if (f.unread) Color.argb(20, 139, 114, 255) else Color.argb(10, 255, 255, 255),
                if (f.unread) Color.argb(46, 139, 114, 255) else Color.argb(15, 255, 255, 255),
                16, 1
            )
            setOnClickListener {
                startActivity(Intent(this@InboxActivity, ChatActivity::class.java).putExtra("name", f.name))
            }
        }

        row.setOnLongClickListener { showRowMenu(row, f); true }

        // Avatar (letter) + online dot
        val avatar = FrameLayout(this)
        avatar.addView(TextView(this).apply {
            text = f.name.first().toString().uppercase()
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(f.color) }
        }, FrameLayout.LayoutParams(dp(48), dp(48)))
        avatar.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (f.online) hex("#22c55e") else hex("#6b7280"))
                setStroke(dp(2), hex("#0f0b20"))
            }
        }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.END or Gravity.BOTTOM))
        row.addView(avatar, lp(dp(48), dp(48)))

        // Naam + last message pill
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = if (f.pinned) "📌 ${f.name}" else f.name
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        })
        info.addView(TextView(this).apply {
            text = f.last
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(11), dp(4), dp(11), dp(4))
            background = if (f.unread) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(hex("#7c3aed"), hex("#a78bfa")))
                    .apply { cornerRadius = dp(12).toFloat() }
            } else {
                roundBox(Color.argb(60, 12, 10, 28), Color.argb(70, 255, 255, 255), 12, 1)
            }
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })

        // Time + lock
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        meta.addView(TextView(this).apply {
            text = f.time
            textSize = 11f
            setTextColor(Color.argb(102, 255, 255, 255))
        })
        meta.addView(TextView(this).apply {
            text = "🔒"
            textSize = 13f
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        row.addView(meta, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return row.also {
            it.layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    }

    /** Neeche ka nav: shared BottomNav.kt, Chat active. */
    private fun buildBottomBar(): View = buildBottomNav(this, "chat")

    /** Chat ☰ menu: Pinned chats + Chat clear (confirm ke saath). */
    private fun showChatMenu(anchor: View) {
        showDropMenu(anchor, listOf(
            "📌  Pinned chats" to { showPinSheet() },
            "🗑️  Chat clear" to {
                confirmThen(this, "Chat clear karein?", "Chat ki history clear hogi.") {
                    Toast.makeText(this, "Chat clear (demo)", Toast.LENGTH_SHORT).show()
                }
            }
        ))
    }

    /** List ko chats ke hisaab se bharta hai (pinned upar). Label (index 0) rehta hai. */
    private fun fillInbox() {
        val keep = 1
        while (listBox.childCount > keep) listBox.removeViewAt(keep)
        chats.sortedByDescending { it.pinned }.forEach { listBox.addView(buildRow(it)) }
    }

    /** Chat par zor se press: Pin / Unpin aur Chat clear. */
    private fun showRowMenu(anchor: View, f: Friend) {
        showDropMenu(anchor, listOf(
            (if (f.pinned) "📌  Unpin karo" else "📌  Pin karo") to { togglePin(f.name) },
            "🧹  Chat clear karo" to {
                confirmThen(this, "${f.name} ka chat clear karein?", "Is chat ki history clear hogi.") { clearChat(f.name) }
            }
        ))
    }

    private fun togglePin(name: String) {
        val i = chats.indexOfFirst { it.name == name }
        if (i >= 0) chats[i] = chats[i].copy(pinned = !chats[i].pinned)
        fillInbox()
    }

    private fun clearChat(name: String) {
        val i = chats.indexOfFirst { it.name == name }
        if (i >= 0) chats[i] = chats[i].copy(last = "Chat khali", unread = false)
        fillInbox()
        Toast.makeText(this, "Chat clear ho gayi", Toast.LENGTH_SHORT).show()
    }

    /** Pinned chats: neeche se sheet khulti hai, chats select karke Save karte hain. */
    private fun showPinSheet() {
        val picked = chats.filter { it.pinned }.map { it.name }.toMutableSet()
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(hex("#1b1433"))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        sheet.addView(TextView(this).apply {
            text = "Konsi chat pin karni hai?"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        sheet.addView(TextView(this).apply {
            text = "Tap karke select karo, phir Save dabao"
            textSize = 12f
            setTextColor(hex("#a291c6"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(10) })

        chats.forEach { f ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = roundBox(Color.argb(10, 255, 255, 255), Color.argb(15, 255, 255, 255), 14, 1)
            }
            val dot = View(this)
            fun paintDot() {
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (picked.contains(f.name)) hex("#a477ff") else Color.TRANSPARENT)
                    setStroke(dp(2), hex("#a477ff"))
                }
            }
            paintDot()
            item.addView(dot, lp(dp(20), dp(20)))
            item.addView(TextView(this).apply {
                text = f.name
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            item.setOnClickListener {
                if (picked.contains(f.name)) picked.remove(f.name) else picked.add(f.name)
                paintDot()
            }
            sheet.addView(item, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        sheet.addView(TextView(this).apply {
            text = "Save"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#0d0716"))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(hex("#54e8ff"), hex("#8b72ff")))
                .apply { cornerRadius = dp(12).toFloat() }
            setOnClickListener {
                for (i in chats.indices) chats[i] = chats[i].copy(pinned = picked.contains(chats[i].name))
                fillInbox()
                dlg.dismiss()
            }
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })

        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
    }

    private fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }
}
