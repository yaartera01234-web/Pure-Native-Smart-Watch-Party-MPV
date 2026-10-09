package app.party.wpnative

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
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
    private var typingOn = false     // kya doosra wala abhi likh raha hai (Instagram wale dots)

    // DEMO: jab tak asli E2E/server nahi aata, peer ki typing dikhane ke liye.
    // Asli chat aane par isko false kar dena — dots tab server ke signal se chalenge.
    private val demoPeerTyping = true

    private lateinit var threadBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var replyBar: LinearLayout
    private lateinit var replyWrap: FrameLayout
    private lateinit var replyWho: TextView
    private lateinit var replyWhat: TextView
    private lateinit var emojiWrap: FrameLayout
    private lateinit var flyLayer: FrameLayout      // Instagram wale udte emoji isi par chalte hain
    private lateinit var emojiInput: EditText
    private lateinit var statusText: TextView     // header ka "Online" / "Offline • 12 min ago"
    private lateinit var statusDotWrap: FrameLayout

    // Website ki tarah har 5 second mein presence taaza karo (dmRefreshOnlineDots ka interval)
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 5000L)
        }
    }

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

    override fun onResume() {
        super.onResume()
        // Kisi aur screen (inbox) se ye friend hat gaya ho to chat khuli nahi rehni chahiye
        if (!Friends.has(this, peer)) { finish(); return }
        refreshStatus()
        statusHandler.post(statusTick)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusTick)
        super.onPause()
    }

    /** Header ka dot + "Online / Offline • 12 min ago" taaza karo. */
    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val online = Presence.isOnline(this, peer)
        statusText.text = Presence.label(this, peer)
        statusDotWrap.removeAllViews()
        statusDotWrap.addView(presenceDot(this, online, 8, 12),
            FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
    }

    // ============================ SCREEN ============================

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        root.background = rootBg()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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

        // Reply patti: website ke #dm-rep4 ki tarah — composer ke theek upar
        buildReplyBar()
        col.addView(replyWrap, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ➕ wali emoji patti: composer ke THEEK upar (website: bottom 122px)
        buildEmojiBar()
        col.addView(emojiWrap, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(buildComposer(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Udte emojis ki layer (chhune se kuch nahi hota, neeche wale button chalu rahte hain)
        flyLayer = FrameLayout(this).apply { isClickable = false }
        root.addView(flyLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        // Online / Offline: website .dm-bar .dot4 (8dp gola) + label ("Offline • 12 min ago")
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDotWrap = FrameLayout(this)
        subRow.addView(statusDotWrap, lp(dp(12), dp(12)))
        statusText = TextView(this).apply {
            textSize = 10f
            setTextColor(hex("#cfc6ee"))
            setSingleLine(true)
        }
        subRow.addView(statusText, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(3)
        })
        title.addView(subRow)
        refreshStatus()
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
            if (typingOn) addTypingRow((resources.displayMetrics.widthPixels * 0.85f).toInt())
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

        // Doosra wala likh raha ho to sabse neeche Instagram wale 3 dots
        if (typingOn) addTypingRow(rowW)
    }

    /** Typing wali row ko thread ke aakhir mein lagata hai. */
    private fun addTypingRow(rowW: Int) {
        threadBox.addView(buildTypingRow(), lp(rowW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START
            bottomMargin = dp(10)
        })
    }

    /**
     * Instagram wala typing indicator: peer ka avatar + usi jaise bubble ke andar 3 hilte dots.
     * (Website #dm-typing ke dots se banaya, sample dekh kar yahi style chuna gaya.)
     */
    private fun buildTypingRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.START
        }

        // Avatar (bilkul message wali row jaisa)
        row.addView(TextView(this).apply {
            text = peer.first().uppercase()
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(peerColor) }
        }, lp(dp(34), dp(34)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        // Naam (message wali rows ki tarah)
        col.addView(TextView(this).apply {
            text = peer
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(peerColor)
            alpha = 0.8f
            setSingleLine(true)
            setPadding(dp(8), 0, 0, 0)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })

        // Bubble: doosre wale ke bubble jaisa (radius 22, halka glassy) + andar 3 dots
        val dots = TypingDots(this)
        val bubble = FrameLayout(this).apply {
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = roundBox(Color.argb(23, 255, 255, 255), Color.argb(36, 255, 255, 255), 22, 1)
            addView(dots, FrameLayout.LayoutParams(dots.desiredWidth(), dots.desiredHeight(), Gravity.START))
        }
        // Bubble ki jagah bilkul message wali row jaisi (avatar se 8dp door)
        col.addView(bubble, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        return row
    }

    /** Dots dikhane/chhupane ka switch — asli chat mein server ke signal se chalega. */
    private fun setTyping(on: Boolean) {
        if (typingOn == on) return
        typingOn = on
        renderThread()
        if (on) scrollBottom()
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
            includeFontPadding = true
            setLineSpacing(0f, 1.55f)
        })
        // Swipe karte waqt ↩ wala nishan (website .swh4) — bubble ke bahar, swipe wali taraf
        val handle = TextView(this).apply {
            text = "↩"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
            background = swipeHandleBg(false)
        }
        val bubbleWrap = FrameLayout(this).apply {
            clipChildren = false
            val side = if (m.own) Gravity.END else Gravity.START
            addView(bubble, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or side))
            addView(handle, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL or side).apply {
                if (m.own) rightMargin = -dp(30) else leftMargin = -dp(30)
            })
        }
        col.clipChildren = false
        row.clipChildren = false
        threadBox.clipChildren = false
        scroll.clipChildren = false
        col.addView(bubbleWrap, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        bindSwipe(row, bubble, m, handle)
        return row
    }

    /**
     * Swipe karke reply (website ka d4bind):
     * dayen swipe -> 12dp par pakad, zyada se zyada 110dp slide,
     * 60dp se zyada par chhodne par reply set, 200ms mein wapas.
     */
    private fun bindSwipe(row: View, touchOn: View, m: Msg, handle: TextView) {
        val startAt = dp(12).toFloat()
        val maxSlide = dp(110).toFloat()
        val fireAt = dp(60).toFloat()
        var sx = 0f
        var sy = 0f
        var dx = 0f
        var swiping = false

        val listener = View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; dx = 0f; swiping = false
                    false                       // tap / long-press ko mauka do
                }
                MotionEvent.ACTION_MOVE -> {
                    val ddx = ev.rawX - sx
                    val ddy = ev.rawY - sy
                    if (Math.abs(ddx) > startAt || Math.abs(ddy) > startAt) {
                        v.cancelLongPress()
                        touchOn.cancelLongPress()
                    }
                    // Jaldi pakdo: jab hi chal horizontal lage, ScrollView ko roko
                    // (warna wo upar-neeche wali kheench samajh kar event chheen leta hai)
                    if (!swiping && Math.abs(ddx) > dp(6) && Math.abs(ddx) > Math.abs(ddy)) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (!swiping && ddx > startAt && Math.abs(ddx) > Math.abs(ddy) * 1.5f) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        dx = ddx
                        val slide = Math.min(dx, maxSlide)
                        row.translationX = slide
                        // ↩ nishan: jitni kheench utni roshni; 60dp ke baad hara (armed)
                        val prog = (slide / fireAt).coerceIn(0f, 1f)
                        handle.alpha = prog
                        handle.scaleX = 0.7f + 0.3f * prog
                        handle.scaleY = handle.scaleX
                        handle.rotation = -25f * (1f - prog)
                        handle.background = swipeHandleBg(slide >= fireAt)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!swiping) {
                        false
                    } else {
                        val fire = dx > fireAt && ev.actionMasked == MotionEvent.ACTION_UP
                        row.animate().translationX(0f).setDuration(200L)
                            .setInterpolator(DecelerateInterpolator()).start()
                        handle.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).rotation(0f).setDuration(180L).start()
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
        // Bubble par bhi: wo long-clickable hai, is liye wahi touch target banta hai.
        // Row par bhi: avatar/khaali jagah se swipe karne ke liye.
        touchOn.setOnTouchListener(listener)
        row.setOnTouchListener(listener)
    }

    /** ↩ nishan ka background: neela (chal raha) / hara (chhodne par reply pakka). */
    private fun swipeHandleBg(armed: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (armed) Color.argb(80, 49, 209, 88) else Color.argb(46, 84, 232, 255))
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), if (armed) hex("#31d158") else Color.argb(115, 84, 232, 255))
    }

    /**
     * Instagram wala jadoo: emoji neeche se upar fly hoke ~3 second mein hawa ho jaye.
     * Ek se zyada emoji saath saath ud sakte hain (har reaction apna animator banata hai).
     *
     * YE EK HI RASTA hai reaction dikhane ka (abhi sirf toggleRx isi ko bulata hai).
     * Asli E2E chat aane par: doosri taraf se reaction milte hi bas
     *      m.rx[emoji] = false; renderThread(); flyReaction(emoji)
     * -> dono devices par emoji udega. Koi alag code nahi.
     */
    private fun flyReaction(emoji: String) {
        val v = TextView(this).apply {
            text = emoji
            textSize = 30f
            alpha = 0f
        }
        flyLayer.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(96) })

        val h = if (flyLayer.height > 0) flyLayer.height else dp(420)
        val rise = (h * (0.5f + Math.random().toFloat() * 0.3f)).coerceAtLeast(dp(180).toFloat())
        val drift = (if (Math.random() < 0.5) -1 else 1) * dp(18 + (Math.random() * 34).toInt())
        val spin = (if (Math.random() < 0.5) -1 else 1) * (8f + Math.random().toFloat() * 16f)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction
                v.translationY = -rise * t
                v.translationX = (drift * Math.sin(t.toDouble() * Math.PI * 1.6)).toFloat()
                v.rotation = spin * t
                val pop = Math.min(1f, t * 7f)
                v.scaleX = 0.5f + 0.9f * pop
                v.scaleY = v.scaleX
                v.alpha = when {
                    t < 0.07f -> t / 0.07f
                    t > 0.62f -> (1f - (t - 0.62f) / 0.38f).coerceIn(0f, 1f)
                    else -> 1f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { flyLayer.removeView(v) }
            })
            start()
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
                minHeight = dp(28)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                includeFontPadding = true
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
        }, lp(dp(36), dp(36)).apply { rightMargin = dp(4) })
        pill.addView(iconBtn("mic", intArrayOf(hex("#06b6d4"), hex("#3b82f6"), hex("#8b5cf6"))) {
            Toast.makeText(this@ChatActivity, "Voice message agle step mein", Toast.LENGTH_SHORT).show()
        }, lp(dp(36), dp(36)).apply { rightMargin = dp(4) })

        input = EditText(this).apply {
            hint = "Message likho..."
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL      // emoji upar na kate
            includeFontPadding = true                 // emoji font ke ascent/descent ke liye jagah
            minHeight = dp(40)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        // fixed height ki jagah poori pill ki unchai -> emoji upar se bilkul na kate
        pill.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(4) })
        row.addView(pill, LinearLayout.LayoutParams(0, dp(46), 1f))

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
        }, lp(dp(46), dp(46)).apply { leftMargin = dp(8) })

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
            // DHYAAN: yahan visibility GONE mat lagana — replyWrap hi chhupata/dikhata hai.
            // (pehle yahan GONE tha, is liye reply patti kabhi dikhti hi nahi thi)
            setPadding(dp(10), dp(7), dp(10), dp(7))          // website: padding 7px 10px
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
            textSize = 12f                                  // website: 12px
            setTextColor(hex("#d1d5db"))                    // website: #d1d5db
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
            background = GradientDrawable().apply {          // website: 26x26, radius 50%, border 0
                shape = GradientDrawable.OVAL
                setColor(Color.argb(31, 255, 255, 255))
            }
            setOnClickListener { clearReply() }
        }, lp(dp(26), dp(26)).apply { leftMargin = dp(9) })

        // replyWrap = padding wali jagah; chhupane/dikhane ka kaam isi pe hota hai
        replyWrap = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(11), dp(6), dp(11), dp(6))
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
            gravity = Gravity.CENTER_VERTICAL
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
                addView(emojiInput, LinearLayout.LayoutParams(0, dp(46), 1f))
                addView(TextView(this@ChatActivity).apply {
                    text = "✕"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundBox(Color.argb(31, 255, 255, 255), Color.TRANSPARENT, 10, 0)
                    setOnClickListener { closeEmojiBox() }
                }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
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
        // Demo: peer thodi der typing karta hai (asli E2E mein server bataega)
        if (demoPeerTyping) {
            window.decorView.postDelayed({ if (!isFinishing) setTyping(true) }, 1200)
            window.decorView.postDelayed({ if (!isFinishing) setTyping(false) }, 4200)
        }
    }

    private fun setReply(m: Msg) {
        replyTo = m
        replyWho.text = if (m.own) "You" else peer
        replyWhat.text = m.text
        replyWrap.visibility = View.VISIBLE
        // Website d4setReply() ki tarah: swipe karte hi likhne ka box khul jaye
        // (sirf requestFocus se keyboard hamesha nahi khulta — IMM bhi bulana padta hai)
        input.requestFocus()
        input.setSelection(input.text.length)
        input.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
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
                textSize = 20f
                gravity = Gravity.CENTER
                includeFontPadding = true
                setOnClickListener { toggleRx(m, e); dlg.dismiss() }
            }, lp(dp(36), dp(36)).apply { marginEnd = dp(2) })
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
        }, lp(dp(36), dp(36)))
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
        val adding = m.rx[emoji] != true
        if (adding) m.rx[emoji] = true else m.rx.remove(emoji)
        renderThread()
        if (adding) flyReaction(emoji)
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

    /** ☰ menu: Chat clear + Remove Friend (website #dm-pop4 ki tarah). */
    private fun showChatMenu(anchor: View) {
        showDropMenu(anchor, listOf(
            "🧹  Chat clear" to {
                confirmThen(this, "Chat clear karein?", "$peer ke saath purani baat-cheet mit jayegi.") {
                    msgs.clear()
                    clearReply()
                    renderThread()
                    Toast.makeText(this, "Chat clear ho gayi", Toast.LENGTH_SHORT).show()
                }
            },
            "👤  Remove Friend" to { removeFriend(peer) }
        ))
    }

    /**
     * Friend hatao: website ke #dm-remove-dialog jaisa confirm, phir
     * Friends list se nikal do aur chat band karke inbox par wapas jao.
     */
    private fun removeFriend(name: String) {
        if (!Friends.has(this, name)) {
            Toast.makeText(this, "$name ab friend nahi hai", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        removeFriendDialog(this, name) {
            Friends.remove(this, name)
            msgs.clear()
            Toast.makeText(this, "$name friend list se hat gaya", Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(0, 0)
        }
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
