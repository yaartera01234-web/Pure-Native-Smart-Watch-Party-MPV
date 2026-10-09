package app.party.wpnative

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * CHAT SCREEN = website ke #dm-view-chat > #dm-thread ka design (P44 "Insta Vanish" wala look).
 *
 * Website se liya gaya (exact):
 *  - Screen background: radial gradient (upar se #241456 -> #130933 -> #05030d -> #000)
 *  - Header (.dm-bar): ← back, avatar (cyan->purple ring), naam + sub, 📞 call, ☰ menu
 *    (E2E badge aur ✕ dono website ne chhupaye hain -> yahan bhi nahi hain)
 *  - Apna bubble: right, gradient #5b21b6 -> #9333ea -> #db2777, radius 22, safed text
 *  - Doosre ka bubble: left, halka glassy #ffffff17 + 1dp border, radius 22
 *  - Bubble ke neeche: time + ✓✓ (parha hua = #5bdcff)
 *  - Neeche composer (.dm-inbar > .ig4pill): photo icon, mic icon, input, ➤ send (40dp gol)
 *    (GIF button hataya gaya — keyboard se direct bhejte hain)
 *
 * Abhi demo data hai (in-memory). Asli E2E chat agle step mein.
 */
class ChatActivity : Activity() {

    /** Ek message. rx = emoji -> kya wo meri reaction hai. */
    private class Msg(
        val id: Int,
        val text: String,
        val own: Boolean,
        val time: String,
        val day: String,
        var read: Boolean = false,
        var replyName: String = "",
        var replyText: String = "",
        val rx: LinkedHashMap<String, Boolean> = LinkedHashMap()
    )

    private var peer = "Dost"
    private var peerColor = 0
    private val msgs = mutableListOf<Msg>()
    private var nextId = 1
    private var replyTo: Msg? = null
    private var animId = -1          // jis nay message ko abhi entry animation milti hai
    private var emojiTarget: Msg? = null   // ➕ se jis message pe emoji lagana hai

    private lateinit var threadBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var replyBar: LinearLayout
    private lateinit var replyWrap: FrameLayout
    private lateinit var replyWho: TextView
    private lateinit var replyWhat: TextView
    private lateinit var emojiWrap: FrameLayout
    private lateinit var emojiInput: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun hex(s: String): Int = Color.parseColor(s)
    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peer = intent.getStringExtra("name")?.takeIf { it.isNotBlank() } ?: "Dost"
        peerColor = pickColor(peer)
        setContentView(buildScreen())
        seedDemo()
        renderThread()
        scrollBottom()
    }

    // ============================ SCREEN ============================

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        root.background = rootBg()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        buildReplyBar()
        col.addView(replyWrap, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        threadBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // website #dm-thread: justify-content: flex-end -> kam messages neeche se chipke
            gravity = Gravity.BOTTOM
            setPadding(dp(11), dp(12), dp(11), dp(8))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(threadBox)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ➕ wali emoji patti: composer ke THEEK upar (website: bottom 122px)
        buildEmojiBar()
        col.addView(emojiWrap, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(buildComposer(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    /** Website #dm-view-chat ka background: radial gradient upar se neeche. */
    private fun rootBg(): LayerDrawable {
        val base = GradientDrawable().apply { setColor(hex("#05030d")) }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setColors(intArrayOf(hex("#241456"), hex("#130933"), hex("#05030d"), Color.BLACK))
            setGradientCenter(0.5f, 0f)
            gradientRadius = resources.displayMetrics.heightPixels * 0.75f
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    // ============================ HEADER ============================

    private fun buildHeader(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // ← back
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundBox(Color.argb(26, 255, 255, 255), Color.argb(41, 255, 255, 255), 9, 1)
            setOnClickListener { finish(); overridePendingTransition(0, 0) }
        }, lp(dp(28), dp(28)))

        // Avatar (cyan -> purple ring, andar letter)
        val av = TextView(this).apply {
            text = peer.first().uppercase()
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(peerColor) }
        }
        val ring = FrameLayout(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#22d3ee"), hex("#a855f7"))).apply { shape = GradientDrawable.OVAL }
            addView(av, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }
        bar.addView(ring, lp(dp(38), dp(38)).apply { leftMargin = dp(6) })

        // Naam + sub
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(this).apply {
            text = peer
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        })
        title.addView(TextView(this).apply {
            text = "Online"
            textSize = 10f
            setTextColor(hex("#cfc6ee"))
            setSingleLine(true)
        })
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(9)
        })

        // 📞 call (website .dm-call-open wala phone icon)
        bar.addView(iconAction("phone") {
            Toast.makeText(this@ChatActivity, "Call agle step mein", Toast.LENGTH_SHORT).show()
        }, lp(dp(36), dp(36)))

        // ☰ menu
        bar.addView(circleAction("☰") { showChatMenu(it) }, lp(dp(36), dp(36)).apply { leftMargin = dp(6) })

        wrap.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Website .dm-bar ki neeche wali 1dp line
        wrap.addView(View(this).apply { setBackgroundColor(Color.argb(26, 255, 255, 255)) },
            lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrap
    }

    /** Header ka vector-icon wala gol button (call) — website .add2 jaisa. */
    private fun iconAction(kind: String, fn: (View) -> Unit): FrameLayout = FrameLayout(this).apply {
        addView(WpIcon(this@ChatActivity, kind), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        background = roundBox(Color.argb(26, 255, 255, 255), Color.argb(36, 255, 255, 255), 11, 1)
        setOnClickListener { fn(this) }
    }

    /** Header ke chhote gol buttons (call / menu) — website .add2 jaisa. */
    private fun circleAction(label: String, fn: (View) -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundBox(Color.argb(26, 255, 255, 255), Color.argb(36, 255, 255, 255), 11, 1)
        setOnClickListener { fn(this) }
    }

    // ============================ THREAD ============================

    private fun renderThread() {
        threadBox.removeAllViews()

        if (msgs.isEmpty()) {
            threadBox.addView(emptyState(),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(40) })
            return
        }

        val rowW = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        var lastDay = ""
        msgs.forEach { m ->
            if (m.day != lastDay) {
                lastDay = m.day
                threadBox.addView(TextView(this).apply {
                    text = m.day
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.argb(140, 255, 255, 255))
                }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, dp(4), 0, dp(2))
                })
            }
            val row = buildRow(m)
            // Naya message: thoda sa neeche se upar aata hai (website wpMessageIn 180ms)
            if (m.id == animId) {
                animId = -1
                row.alpha = 0f
                row.translationY = dp(7).toFloat()
                row.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            }
            threadBox.addView(row, lp(rowW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (m.own) Gravity.END else Gravity.START
                bottomMargin = dp(10)
            })
        }
    }

    /** Ek message ki row: [avatar] [naam + bubble + reactions + time] (apna = ulti taraf). */
    private fun buildRow(m: Msg): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or (if (m.own) Gravity.END else Gravity.START)
        }

        // --- bubble ke upar naam ---
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.own) Gravity.END else Gravity.START
        }
        col.addView(TextView(this).apply {
            text = if (m.own) "You" else peer
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (m.own) hex("#f9a8d4") else peerColor)
            alpha = 0.8f
            setSingleLine(true)
            setPadding(if (m.own) 0 else dp(8), 0, if (m.own) dp(8) else 0, 0)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })

        // --- bubble ---
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (m.own) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(hex("#5b21b6"), hex("#9333ea"), hex("#db2777"))).apply { cornerRadius = dp(22).toFloat() }
            } else {
                roundBox(Color.argb(23, 255, 255, 255), Color.argb(36, 255, 255, 255), 22, 1)
            }
            if (m.own) elevation = dp(6).toFloat()
            setOnLongClickListener { showMsgActions(m); true }
        }

        // bubble ke andar: (reply quote) + text
        if (m.replyText.isNotBlank()) bubble.addView(quoteBlock(m),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        bubble.addView(TextView(this).apply {
            text = m.text
            textSize = 14f
            setTextColor(if (m.own) Color.WHITE else hex("#f3efff"))
            setLineSpacing(0f, 1.45f)
        })
        col.addView(bubble, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- reactions (chhoti chips) ---
        if (m.rx.isNotEmpty()) col.addView(buildChips(m),
            lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // --- time + ✓✓ ---
        col.addView(TextView(this).apply {
            text = timeWithTick(m)
            textSize = 9.5f
            setPadding(if (m.own) 0 else dp(8), 0, if (m.own) dp(8) else 0, 0)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
            gravity = if (m.own) Gravity.END else Gravity.START
        })

        // --- avatar ---
        val av = TextView(this).apply {
            text = if (m.own) "Y" else peer.first().uppercase()
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = if (m.own) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(hex("#ff5ebc"), hex("#a855f7"))).apply { shape = GradientDrawable.OVAL }
            } else {
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(peerColor) }
            }
        }

        if (m.own) {
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            row.addView(av, lp(dp(34), dp(34)))
        } else {
            row.addView(av, lp(dp(34), dp(34)))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        }
        bindSwipe(row, m)
        return row
    }

    /**
     * Swipe karke reply (website ka d4bind):
     * dayen swipe -> 12dp par pakad, zyada se zyada 110dp slide,
     * 60dp se zyada par chhodne par reply set, 200ms mein wapas.
     */
    private fun bindSwipe(row: View, m: Msg) {
        val startAt = dp(12).toFloat()
        val maxSlide = dp(110).toFloat()
        val fireAt = dp(60).toFloat()
        var sx = 0f
        var sy = 0f
        var dx = 0f
        var swiping = false

        row.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; dx = 0f; swiping = false
                    false                       // tap / long-press ko mauka do
                }
                MotionEvent.ACTION_MOVE -> {
                    val ddx = ev.rawX - sx
                    val ddy = ev.rawY - sy
                    if (Math.abs(ddx) > startAt || Math.abs(ddy) > startAt) v.cancelLongPress()
                    if (!swiping && ddx > startAt && Math.abs(ddx) > Math.abs(ddy) * 1.5f) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        dx = ddx
                        v.translationX = Math.min(dx, maxSlide)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!swiping) {
                        false
                    } else {
                        val fire = dx > fireAt && ev.actionMasked == MotionEvent.ACTION_UP
                        v.animate().translationX(0f).setDuration(200L)
                            .setInterpolator(DecelerateInterpolator()).start()
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        swiping = false
                        dx = 0f
                        if (fire) setReply(m)
                        true
                    }
                }
                else -> false
            }
        }
    }

    /** Bubble ke andar reply ka hissa (website .quote). */
    private fun quoteBlock(m: Msg): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = roundBox(Color.argb(56, 0, 0, 0), Color.TRANSPARENT, 4, 0)
        }
        box.addView(View(this).apply { setBackgroundColor(Color.argb(217, 255, 255, 255)) },
            lp(dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = m.replyName
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (m.own) hex("#ffe6a8") else hex("#c4b5fd"))
            setSingleLine(true)
        })
        txt.addView(TextView(this).apply {
            text = m.replyText
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        return box
    }

    private fun buildChips(m: Msg): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (m.own) Gravity.END else Gravity.START
        }
        m.rx.forEach { (emoji, mine) ->
            box.addView(TextView(this).apply {
                text = "$emoji 1"
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)      // dim dikhne ki wajah: text color set hi nahi tha
                gravity = Gravity.CENTER
                minHeight = dp(22)
                setPadding(dp(9), dp(1), dp(9), dp(1))
                background = GradientDrawable().apply {
                    setColor(if (mine) Color.argb(150, 244, 114, 182) else Color.argb(235, 34, 26, 62))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), if (mine) hex("#f472b6") else Color.argb(120, 255, 255, 255))
                }
                setOnClickListener { toggleRx(m, emoji) }
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        }
        return box
    }

    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(20), dp(22), dp(16))
        addView(TextView(this@ChatActivity).apply { text = "👋"; textSize = 26f; gravity = Gravity.CENTER })
        addView(TextView(this@ChatActivity).apply {
            text = "$peer ko salam bhejo!"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(hex("#a9a6c8"))
            setLineSpacing(0f, 1.7f)
        })
    }

    // ============================ COMPOSER ============================

    private fun buildComposer(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(89, 0, 0, 0))     // website: rgba(0,0,0,.35)
        }
        bar.addView(View(this).apply { setBackgroundColor(Color.argb(31, 255, 255, 255)) },
            lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }

        // Pill: photo icon + mic icon + input  (website .ig4pill)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = roundBox(Color.argb(23, 255, 255, 255), Color.argb(41, 255, 255, 255), 21, 1)
        }
        pill.addView(iconBtn("photo", intArrayOf(hex("#f59e0b"), hex("#ec4899"), hex("#8b5cf6"))) {
            Toast.makeText(this@ChatActivity, "Photo agle step mein", Toast.LENGTH_SHORT).show()
        }, lp(dp(32), dp(32)).apply { rightMargin = dp(4) })
        pill.addView(iconBtn("mic", intArrayOf(hex("#06b6d4"), hex("#3b82f6"), hex("#8b5cf6"))) {
            Toast.makeText(this@ChatActivity, "Voice message agle step mein", Toast.LENGTH_SHORT).show()
        }, lp(dp(32), dp(32)).apply { rightMargin = dp(4) })

        input = EditText(this).apply {
            hint = "Message likho..."
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        pill.addView(input, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4) })
        row.addView(pill, LinearLayout.LayoutParams(0, dp(40), 1f))

        // ➤ send
        row.addView(TextView(this).apply {
            text = "➤"
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(hex("#0a0616"))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#a855f7"), hex("#22d3ee"))).apply { shape = GradientDrawable.OVAL }
            setOnClickListener { send() }
        }, lp(dp(40), dp(40)).apply { leftMargin = dp(8) })

        bar.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return bar
    }

    /** Composer ke 32dp gol icon buttons (photo / mic) — website ke SVG jaisa. */
    private fun iconBtn(kind: String, colors: IntArray, fn: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(12).toFloat()
        }
        addView(WpIcon(this@ChatActivity, kind), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { fn() }
    }

    /** Reply preview (input ke upar wali patti) — website #dm-rep4. */
    private fun buildReplyBar() {
        replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = roundBox(Color.argb(23, 255, 255, 255), Color.argb(36, 255, 255, 255), 12, 1)
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        replyWho = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(hex("#d8b4fe"))
            setSingleLine(true)
        }
        replyWhat = TextView(this).apply {
            textSize = 11.5f
            setTextColor(hex("#cfd6ff"))
            setSingleLine(true)
        }
        txt.addView(replyWho)
        txt.addView(replyWhat)
        replyBar.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        replyBar.addView(TextView(this).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundBox(Color.argb(20, 255, 255, 255), Color.argb(46, 255, 255, 255), 9, 1)
            setOnClickListener { clearReply() }
        }, lp(dp(26), dp(26)))

        // replyWrap = padding wali jagah; chhupane/dikhane ka kaam isi pe hota hai
        replyWrap = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(11), dp(6), dp(11), dp(0))
            addView(replyBar)
        }
    }

    /** ➕ wali patti: keyboard se emoji chuno (website #dm-rx4box). */
    private fun buildEmojiBar() {
        emojiInput = EditText(this).apply {
            hint = "Keyboard se apni marzi ka emoji chuno..."
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(8))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundBox(Color.argb(20, 255, 255, 255), Color.argb(46, 255, 255, 255), 10, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val em = firstEmoji(s?.toString() ?: "")
                    if (em.isEmpty()) return
                    val target = emojiTarget
                    closeEmojiBox()
                    if (target != null) toggleRx(target, em)
                }
            })
        }

        emojiWrap = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(0))
            addView(LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = roundBox(Color.argb(250, 13, 10, 32), Color.argb(41, 255, 255, 255), 14, 1)
                addView(emojiInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@ChatActivity).apply {
                    text = "✕"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundBox(Color.argb(31, 255, 255, 255), Color.TRANSPARENT, 10, 0)
                    setOnClickListener { closeEmojiBox() }
                }, LinearLayout.LayoutParams(dp(42), dp(38)).apply { leftMargin = dp(8) })
            })
        }
    }

    private fun openEmojiBox(m: Msg) {
        emojiTarget = m
        emojiWrap.visibility = View.VISIBLE
        emojiInput.setText("")
        emojiInput.requestFocus()
        emojiInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(emojiInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeEmojiBox() {
        emojiTarget = null
        emojiWrap.visibility = View.GONE
        emojiInput.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(emojiInput.windowToken, 0)
    }

    /** Keyboard se aaye hue text mein se pehla emoji nikalta hai (website dmFirstEmoji jaisa). */
    private fun firstEmoji(s: String): String {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val isEmoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
                cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF || cp == 0x2764
            if (isEmoji) {
                var j = i + Character.charCount(cp)
                while (j < s.length) {                       // ZWJ / variation selector / skin tone
                    val c2 = s.codePointAt(j)
                    if (c2 == 0xFE0F || c2 == 0x200D || c2 in 0x1F3FB..0x1F3FF || c2 in 0x1F000..0x1FAFF) {
                        j += Character.charCount(c2)
                    } else break
                }
                return s.substring(i, j)
            }
            i += Character.charCount(cp)
        }
        return ""
    }

    // ============================ KAAM ============================

    private fun send() {
        val txt = input.text.toString().trim()
        if (txt.isEmpty()) return
        val now = System.currentTimeMillis()
        val m = Msg(nextId++, txt, true, timeShort(now), dayLabel(now), read = false)
        replyTo?.let {
            m.replyName = if (it.own) "You" else peer
            m.replyText = it.text
            clearReply()
        }
        msgs.add(m)
        animId = m.id
        renderThread()
        scrollBottom()
        input.setText("")
        // Demo: thodi der baad doosri taraf "parh liya" -> ✓✓ neela
        window.decorView.postDelayed({
            if (!isFinishing && msgs.any { it.id == m.id }) { m.read = true; renderThread() }
        }, 900)
    }

    private fun setReply(m: Msg) {
        replyTo = m
        replyWho.text = if (m.own) "You" else peer
        replyWhat.text = m.text
        replyWrap.visibility = View.VISIBLE
        input.requestFocus()
    }

    private fun clearReply() {
        replyTo = null
        replyWrap.visibility = View.GONE
    }

    /** Bottom sheet ke chhote buttons (Reply / Copy / Delete). */
    private fun sheetBtn(label: String, fn: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = roundBox(Color.argb(23, 255, 255, 255), Color.TRANSPARENT, 9, 0)
        setOnClickListener { fn() }
    }

    /** Bubble ko zor se dabaane par khulta hai (website .dm-acts4 jaisa): emoji + Reply / Copy / Delete. */
    private fun showMsgActions(m: Msg) {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = dp(22).toFloat()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
            background = GradientDrawable().apply {
                setColor(hex("#1b1433"))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        val emoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("❤️", "😂", "😮", "😢", "👍", "🔥").forEach { e ->
            emoRow.addView(TextView(this).apply {
                text = e
                textSize = 19f
                gravity = Gravity.CENTER
                setOnClickListener { toggleRx(m, e); dlg.dismiss() }
            }, lp(dp(33), dp(33)).apply { marginEnd = dp(2) })
        }
        // ➕ : keyboard se apni marzi ka emoji (website .rx4more)
        emoRow.addView(TextView(this).apply {
            text = "➕"
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(140, 255, 255, 255), dp(3).toFloat(), dp(3).toFloat())
            }
            // sheet band hone ke baad patti khule (warna keyboard focus nahi leta)
            setOnClickListener { dlg.dismiss(); window.decorView.postDelayed({ openEmojiBox(m) }, 150) }
        }, lp(dp(33), dp(33)))
        sheet.addView(emoRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10)
        })

        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gap = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) }
        acts.addView(sheetBtn("↩ Reply") { dlg.dismiss(); setReply(m) }, gap)
        acts.addView(sheetBtn("📋 Copy") { dlg.dismiss(); copyText(m.text) }, gap)
        acts.addView(sheetBtn("🗑 Delete") {
            dlg.dismiss()
            confirmThen(this@ChatActivity, "Message delete karein?", "Ye message is chat se hat jayega.") { deleteMsg(m) }
        })
        sheet.addView(acts, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
    }

    private fun toggleRx(m: Msg, emoji: String) {
        if (m.rx[emoji] == true) m.rx.remove(emoji) else m.rx[emoji] = true
        renderThread()
    }

    private fun deleteMsg(m: Msg) {
        msgs.removeAll { it.id == m.id }
        if (replyTo?.id == m.id) clearReply()
        renderThread()
        Toast.makeText(this, "Message delete ho gaya", Toast.LENGTH_SHORT).show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, "Copy ho gaya", Toast.LENGTH_SHORT).show()
    }

    /** ☰ menu: sirf Chat clear (website ki tarah). */
    private fun showChatMenu(anchor: View) {
        showDropMenu(anchor, listOf(
            "🧹  Chat clear" to {
                confirmThen(this, "Chat clear karein?", "$peer ke saath purani baat-cheet mit jayegi.") {
                    msgs.clear()
                    clearReply()
                    renderThread()
                    Toast.makeText(this, "Chat clear ho gayi", Toast.LENGTH_SHORT).show()
                }
            }
        ))
    }

    private fun scrollBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ============================ HELPERS ============================

    private fun timeWithTick(m: Msg): CharSequence {
        if (!m.own) {
            return SpannableString(m.time).apply {
                setSpan(ForegroundColorSpan(Color.argb(140, 255, 255, 255)), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val s = "${m.time} ✓✓"
        return SpannableString(s).apply {
            setSpan(ForegroundColorSpan(Color.argb(140, 255, 255, 255)), 0, m.time.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(if (m.read) hex("#5bdcff") else Color.argb(158, 255, 255, 255)),
                m.time.length, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun timeShort(ts: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

    private fun dayLabel(ts: Long): String {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR); val todayYear = cal.get(Calendar.YEAR)
        cal.timeInMillis = ts
        val d = cal.get(Calendar.DAY_OF_YEAR); val y = cal.get(Calendar.YEAR)
        if (d == today && y == todayYear) return "Today"
        cal.timeInMillis = System.currentTimeMillis() - 86_400_000L
        if (d == cal.get(Calendar.DAY_OF_YEAR) && y == cal.get(Calendar.YEAR)) return "Yesterday"
        return SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }

    private fun pickColor(name: String): Int {
        val palette = listOf("#f472b6", "#38bdf8", "#fb7185", "#a78bfa", "#34d399", "#fbbf24")
        return hex(palette[Math.floorMod(name.hashCode(), palette.size)])
    }

    private fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    /** Demo baat-cheet (asli chat agle step mein). */
    private fun seedDemo() {
        val now = System.currentTimeMillis()
        val yest = now - 86_400_000L
        msgs.add(Msg(nextId++, "Kal ka party kaisa tha?", false,
            timeShort(yest - 3_600_000L), dayLabel(yest)))
        msgs.add(Msg(nextId++, "Bahut maza aaya 🎉", true,
            timeShort(yest - 3_500_000L), dayLabel(yest), read = true,
            replyName = peer, replyText = "Kal ka party kaisa tha?"))
        msgs.add(Msg(nextId++, "Aaj raat phir chalega?", false,
            timeShort(now - 600_000L), dayLabel(now),
            rx = LinkedHashMap<String, Boolean>().apply { put("🔥", false) }))
        msgs.add(Msg(nextId++, "Haan, 9 baje ready rehna", true,
            timeShort(now - 540_000L), dayLabel(now), read = true))
    }
}

/** Composer ke icons — website ke SVG se utare gaye: "photo" aur "mic". */
private class WpIcon(ctx: Context, private val kind: String) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val s = width / 24f
        c.save()
        c.scale(s, s)
        p.clearShadowLayer()
        if (kind == "photo") {
            // frame
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.9f
            box.set(3f, 5f, 21f, 19f)
            c.drawRoundRect(box, 2f, 2f, p)
            // sooraj
            p.style = Paint.Style.FILL
            c.drawCircle(8.6f, 9.6f, 2.1f, p)
            // pahad
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.7f
            path.reset()
            path.moveTo(3f, 18.2f)
            path.lineTo(8.6f, 11.6f)
            path.lineTo(12.8f, 16.6f)
            path.lineTo(15.8f, 13.4f)
            path.lineTo(21f, 18.6f)
            c.drawPath(path, p)
        } else if (kind == "phone") {
            // website #dm-call-btn ka phone SVG (Lucide phone) + purple glow
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.setShadowLayer(3f, 0f, 1f, Color.argb(110, 139, 114, 255))
            path.reset()
            path.moveTo(22f, 16.92f)
            path.lineTo(22f, 19.92f)
            path.quadTo(21.6f, 21.6f, 19.82f, 21.92f)
            path.quadTo(15.96f, 21.5f, 11.19f, 18.85f)
            path.quadTo(7.5f, 16.6f, 5.19f, 12.85f)
            path.quadTo(2.73f, 8.1f, 2.12f, 4.18f)
            path.quadTo(2.2f, 2.2f, 4.11f, 2f)
            path.lineTo(7.11f, 2f)
            path.quadTo(9.11f, 2f, 9.11f, 3.72f)
            path.quadTo(8.9f, 5.2f, 9.81f, 6.51f)
            path.quadTo(8.9f, 7.7f, 9.36f, 8.62f)
            path.lineTo(8.09f, 9.91f)
            path.quadTo(10.4f, 12.2f, 14.09f, 15.91f)
            path.lineTo(15.36f, 14.64f)
            path.quadTo(16.6f, 13.8f, 17.47f, 14.19f)
            path.quadTo(18.9f, 14.1f, 20.26f, 14.89f)
            path.quadTo(21.6f, 14.9f, 22f, 16.92f)
            path.close()
            c.drawPath(path, p)
        } else {
            // mic ka capsule
            p.style = Paint.Style.FILL
            box.set(9f, 2f, 15f, 13f)
            c.drawRoundRect(box, 3f, 3f, p)
            // neeche wali U
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            path.reset()
            box.set(5f, 5f, 19f, 19f)
            path.arcTo(box, 0f, 180f)
            c.drawPath(path, p)
            // dandi + base
            c.drawLine(12f, 19f, 12f, 21.6f, p)
            c.drawLine(8.4f, 21.6f, 15.6f, 21.6f, p)
            // chamak (chhote sitare)
            p.style = Paint.Style.FILL
            star(c, 19.2f, 3.2f, 1.7f)
            star(c, 21.6f, 6.2f, 1.1f)
            star(c, 16.6f, 5.6f, 1f)
        }
        c.restore()
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.42f, cy - r * 0.42f)
        path.lineTo(cx + r, cy)
        path.lineTo(cx + r * 0.42f, cy + r * 0.42f)
        path.lineTo(cx, cy + r)
        path.lineTo(cx - r * 0.42f, cy + r * 0.42f)
        path.lineTo(cx - r, cy)
        path.lineTo(cx - r * 0.42f, cy - r * 0.42f)
        path.close()
        c.drawPath(path, p)
    }
}
