package app.party.wpnative

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/** ChatActivity aur is adapter ke darmiyan chhota sa pul (har cheez screen ke paas hi rahe). */
interface ChatHost {
    fun ctx(): android.content.Context
    fun peerName(): String
    fun meName(): String
    fun peerColorInt(): Int
    fun dp(v: Int): Int
    fun hex(s: String): Int
    fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable
    fun tick(m: Msg): CharSequence
    fun bubbleMaxWidth(): Int
    fun onSwipeReply(m: Msg)
    fun onBubbleLongPress(m: Msg)
    fun onChipClick(m: Msg, emoji: String)
}

/**
 * Chat ki poori list — **RecyclerView**.
 *
 * Pehle har naye message par saari list dobara banti thi (100 msg = ~1,200 views).
 * Ab sirf **wahi ek line** banti/badalti hai, baqi recycle hoti hain:
 *   - naya message  -> 1 insert
 *   - ✓✓ neela hua  -> 1 change
 *   - reaction      -> 1 change
 *   - delete        -> 1 remove
 * Is liye 100 ho ya 5,000 messages — scrolling ek jaisi (60fps).
 */
class ChatAdapter(private val host: ChatHost) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    /** Abhi screen par maujood lines (pagination mein purani jagah pakadne ke kaam aati hai). */
    val list: List<Row> get() = rows

    /** Is message ko "naya aaya" wala animation milega (Instagram jaisa) — ek hi baar. */
    var entryAnimId: Int = -1

    /** Abhi screen par maujood message-wali lines (voice ka ▶/second turant badalne ke liye). */
    private val live = ArrayList<MsgVH>()

    init {
        // Player ka signal -> sirf wahi line badle jiski awaaz chal rahi hai
        VoicePlay.onTick = { key, prog, rem -> live.forEach { it.voiceTick(key, prog, rem) } }
        VoicePlay.onStop = { key -> live.forEach { it.voiceEnd(key) } }
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        super.onViewRecycled(h)
        if (h is MsgVH) live.remove(h)
    }

    private companion object {
        const val T_MINE = 0
        const val T_PEER = 1
        const val T_DAY = 2
        const val T_TYPING = 3
        const val T_EMPTY = 4
    }

    /** Nayi list do — DiffUtil sirf farq wali lines update karta hai. */
    fun submit(next: List<Row>) {
        val diff = DiffUtil.calculateDiff(RowDiff(rows, next), false)
        rows = next
        diff.dispatchUpdatesTo(this)
    }

    fun indexOfKey(key: String?): Int {
        if (key == null) return -1
        for (i in rows.indices) if (rows[i].key == key) return i
        return -1
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(pos: Int): Int = when (rows[pos].kind) {
        Row.MSG -> if (rows[pos].msg?.own == true) T_MINE else T_PEER
        Row.DAY -> T_DAY
        Row.TYPING -> T_TYPING
        else -> T_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            T_MINE -> MsgVH(host, true)
            T_PEER -> MsgVH(host, false)
            T_DAY -> DayVH(host)
            T_TYPING -> TypingVH(host)
            else -> EmptyVH(host)
        }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (h) {
            is MsgVH -> rows[pos].msg?.let { m ->
                val anim = (m.id == entryAnimId)
                if (anim) entryAnimId = -1
                if (!live.contains(h)) live.add(h)
                h.bind(m, anim)
            }
            is DayVH -> h.bind(rows[pos].day ?: "")
        }
    }

    // ---------------------------------------------------------------- message

    /**
     * Ek message ki line: [avatar] [naam + bubble(+reply quote) + reactions + time].
     *
     * Poori hierarchy **ek hi baar** banti hai (ViewHolder ke sath), phir baar baar
     * sirf `bind()` chalta hai — is liye scroll mein koi naya view nahi banta.
     */
    private class MsgVH(private val host: ChatHost, private val mine: Boolean) :
        RecyclerView.ViewHolder(buildRoot(host, mine)) {

        private val row: LinearLayout = itemView as LinearLayout
        private val nameTv: TextView
        private val bubble: LinearLayout
        private val quote: LinearLayout
        private val quoteName: TextView
        private val quoteText: TextView
        private val textTv: TextView
        private val photo: ImageView
        private var photoKey: String? = null
        private val voiceWrap: LinearLayout
        private val playBtn: TextView
        private val waveV: VoiceWave
        private val durTv: TextView
        private var voiceKey: String? = null
        private val handle: TextView
        private val chips: LinearLayout
        private val timeTv: TextView
        private val av: View

        private var bound: Msg? = null
        private var chipSig = ""

        init {
            val dp = { v: Int -> host.dp(v) }
            val side = if (mine) Gravity.END else Gravity.START
            val padStart = if (mine) 0 else dp(8)
            val padEnd = if (mine) dp(8) else 0

            val col = LinearLayout(host.ctx()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = side
            }

            nameTv = TextView(host.ctx()).apply {
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (mine) host.hex("#f9a8d4") else host.peerColorInt())
                alpha = 0.8f
                setSingleLine(true)
                setPadding(padStart, 0, padEnd, 0)
            }
            col.addView(nameTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2)
            })

            // ---- bubble ----
            bubble = LinearLayout(host.ctx()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (mine) mineBg else peerBg
                if (mine) elevation = dp(6).toFloat()
            }
            quote = LinearLayout(host.ctx()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(7), dp(5), dp(7), dp(5))
                background = host.roundBox(Color.argb(56, 0, 0, 0), Color.TRANSPARENT, 4, 0)
                addView(View(host.ctx()).apply { setBackgroundColor(Color.argb(217, 255, 255, 255)) },
                    LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
                val txt = LinearLayout(host.ctx()).apply { orientation = LinearLayout.VERTICAL }
                quoteName = TextView(host.ctx()).apply {
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (mine) host.hex("#ffe6a8") else host.hex("#c4b5fd"))
                    setSingleLine(true)
                }
                quoteText = TextView(host.ctx()).apply {
                    textSize = 11.5f
                    setTextColor(Color.WHITE)
                    setSingleLine(true)
                }
                txt.addView(quoteName)
                txt.addView(quoteText, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(6)
                })
            }
            bubble.addView(quote, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })

            // ---- photo (website: .chat-photo img max 220x300, radius 12) ----
            photo = ImageView(host.ctx()).apply {
                visibility = View.GONE
                adjustViewBounds = true
                maxWidth = host.dp(220)
                maxHeight = host.dp(300)
                minimumWidth = host.dp(150)
                minimumHeight = host.dp(110)
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, host.dp(12).toFloat())
                    }
                }
                background = host.roundBox(Color.argb(31, 255, 255, 255),
                    Color.argb(46, 255, 255, 255), 12, 1)
                setOnClickListener { openFullPhoto() }
            }
            bubble.addView(photo, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // ---- voice (website .chat-voice .cv: ▶ + awaaz ki lakiren + second) ----
            voiceWrap = LinearLayout(host.ctx()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                minimumWidth = host.dp(168)                 // website: .cv min-width 168px
                setPadding(0, host.dp(2), 0, host.dp(2))    // website: padding 2px 0
            }
            playBtn = TextView(host.ctx()).apply {
                text = "\u25B6"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = if (mine)
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(77, 20, 8, 33))      // website: rgba(20,8,33,.30)
                    }
                else
                    GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(host.hex("#22d3ee"), host.hex("#8b5cf6"))).apply {
                        shape = GradientDrawable.OVAL
                    }
                setOnClickListener { toggleVoice() }
            }
            voiceWrap.addView(playBtn, LinearLayout.LayoutParams(host.dp(34), host.dp(34)))
            waveV = VoiceWave(host.ctx(), mine)
            voiceWrap.addView(waveV, LinearLayout.LayoutParams(host.dp(108), host.dp(26)).apply {
                leftMargin = host.dp(9)                          // website: gap 9px
                rightMargin = host.dp(9)
            })
            durTv = TextView(host.ctx()).apply {
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                alpha = 0.86f
                gravity = Gravity.END
                minWidth = host.dp(30)                           // website: min-width 30px
                setTextColor(if (mine) host.hex("#2b1030") else Color.WHITE)
            }
            voiceWrap.addView(durTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bubble.addView(voiceWrap, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            textTv = TextView(host.ctx()).apply {
                textSize = 14f
                setTextColor(if (mine) Color.WHITE else host.hex("#f3efff"))
                includeFontPadding = true
                setLineSpacing(0f, 1.55f)
                maxWidth = host.bubbleMaxWidth()
            }
            bubble.addView(textTv)

            handle = TextView(host.ctx()).apply {
                text = "↩"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                alpha = 0f
                scaleX = 0.7f
                scaleY = 0.7f
            }

            val bubbleWrap = FrameLayout(host.ctx()).apply {
                clipChildren = false
                addView(bubble, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL or side))
                addView(handle, FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.CENTER_VERTICAL or side).apply {
                    if (mine) rightMargin = -dp(30) else leftMargin = -dp(30)
                })
            }
            col.clipChildren = false
            col.addView(bubbleWrap, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // ---- reactions (chhoti chips) ----
            chips = LinearLayout(host.ctx()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = side
            }
            col.addView(chips, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })

            // ---- time + ✓✓ ----
            timeTv = TextView(host.ctx()).apply {
                textSize = 9.5f
                setPadding(padStart, 0, padEnd, 0)
            }
            col.addView(timeTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
                gravity = side
            })

            // ---- avatar: asli DP (image aane tak pehla harf) ----
            av = DpStore.circle(
                host.ctx(),
                if (mine) host.meName() else host.peerName(),
                if (mine) host.hex("#a855f7") else host.peerColorInt(),
                34,
                isMe = mine
            )

            if (mine) {
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                })
                row.addView(av, LinearLayout.LayoutParams(dp(34), dp(34)))
            } else {
                row.addView(av, LinearLayout.LayoutParams(dp(34), dp(34)))
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                })
            }

            bubble.setOnLongClickListener { bound?.let { host.onBubbleLongPress(it) }; true }
            attachSwipe()
        }

        private val mineBg: GradientDrawable get() = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(host.hex("#5b21b6"), host.hex("#9333ea"), host.hex("#db2777"))
        ).apply { cornerRadius = host.dp(22).toFloat() }

        private val peerBg: GradientDrawable get() =
            host.roundBox(Color.argb(23, 255, 255, 255), Color.argb(36, 255, 255, 255), 22, 1)

        fun bind(m: Msg, animate: Boolean = false) {
            bound = m
            // recycle hui line purani swipe/animation ki halat na Rakhe
            row.animate().cancel()
            row.translationX = 0f
            row.translationY = 0f
            row.alpha = 1f
            row.scaleX = 1f
            row.scaleY = 1f
            handle.alpha = 0f
            handle.scaleX = 0.7f
            handle.scaleY = 0.7f
            handle.rotation = 0f

            nameTv.text = if (m.own) "You" else host.peerName()
            textTv.text = m.text

            when {
                // photo wala message: bubble mein photo, (ho to) caption neeche
                m.type == "photo" && m.mediaKey.isNotBlank() -> {
                    photo.visibility = View.VISIBLE
                    voiceWrap.visibility = View.GONE
                    textTv.visibility = if (m.text.isBlank()) View.GONE else View.VISIBLE
                    loadPhoto(m)
                }
                // voice wala message: ▶ + awaaz ki lakiren + second
                m.type == "voice" -> {
                    photo.visibility = View.GONE
                    if (photoKey != null) { photo.setImageDrawable(null); photoKey = null }
                    textTv.visibility = View.GONE
                    voiceWrap.visibility = View.VISIBLE
                    if (voiceKey != m.mediaKey) { voiceKey = m.mediaKey; waveV.setWave(m.wave) }
                    syncVoice(m)
                }
                else -> {
                    photo.visibility = View.GONE
                    if (photoKey != null) { photo.setImageDrawable(null); photoKey = null }
                    voiceWrap.visibility = View.GONE
                    textTv.visibility = View.VISIBLE
                }
            }

            if (m.replyText.isNotBlank()) {
                quote.visibility = View.VISIBLE
                quoteName.text = m.replyName
                quoteText.text = m.replyText
            } else {
                quote.visibility = View.GONE
            }

            // chips sirf tab banayein jab reactions badle hon (scroll mein bekaar na bane)
            val sig = m.rx.entries.joinToString(",") { "${it.key}:${it.value}" }
            if (sig != chipSig) {
                chipSig = sig
                rebuildChips(m)
            }
            timeTv.text = host.tick(m)

            /* Recycle hui line ki purani naap (chaudai/unchai) kabhi na tikhe:
               har bind par text + bubble + poora row dobara naapa jaye. */
            textTv.requestLayout()
            bubble.requestLayout()
            row.requestLayout()

            /**
             * Instagram wala entry animation — bubble NEECHE SE UPAR aata hai.
             *
             * Website (party-final1.html) wala sirf 7px/180ms hai aur opacity 0 se
             * shuru hota hai -> woh "blink" jaisa lagta hai. Yahan:
             *   - doori 22dp (saaf nazar aaye)
             *   - alpha 0.35 se shuru (bubble dikhe, bas halka ho -> slide nazar aaye)
             *   - 260ms + tez-ease-out (upar aa kar baith jaye)
             *   - thoda sa scale 0.96 -> 1 (Instagram wali "phudak")
             * RecyclerView ki layout-animation NAHI — is liye size kabhi ghalat nahi hota.
             */
            if (animate) {
                row.alpha = 0.35f
                row.translationY = host.dp(22).toFloat()
                row.scaleX = 0.96f
                row.scaleY = 0.96f
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()
            }
        }

        /** ▶ / ⏸ aur second — abhi kya chal raha hai usi hisaab se. */
        private fun syncVoice(m: Msg, prog: Float = 0f, rem: Int? = null) {
            val on = m.mediaKey.isNotBlank() && VoicePlay.playing() == m.mediaKey
            playBtn.text = if (on) "\u23F8" else "\u25B6"
            waveV.setProgress(if (on) prog else 0f)
            durTv.text = fmtDur(if (on && rem != null) rem else m.dur)
        }

        /** ▶ daba -> awaaz chale (ya ruk jaye). */
        private fun toggleVoice() {
            val m = bound ?: return
            if (m.mediaKey.isBlank()) return
            if (!MediaCache.has(host.ctx(), m.mediaKey)) return
            VoicePlay.toggle(host.ctx(), m.mediaKey)
            syncVoice(m)
        }

        /** Player ka har 100ms ka signal — sirf apni line badlo. */
        fun voiceTick(key: String, prog: Float, rem: Int) {
            val m = bound ?: return
            if (m.type != "voice" || m.mediaKey != key) return
            syncVoice(m, prog, rem)
        }

        /** Awaaz khatam / roki gayi. */
        fun voiceEnd(key: String) {
            val m = bound ?: return
            if (m.type != "voice" || m.mediaKey != key) return
            syncVoice(m)
        }

        /** Phone mein padi photo uthao (background mein) aur bubble mein lagao. */
        private fun loadPhoto(m: Msg) {
            if (m.mediaKey.isBlank()) { photo.setImageDrawable(null); photoKey = null; return }
            if (photoKey == m.mediaKey) return          // pehle hi lagi hui hai
            photoKey = m.mediaKey
            photo.setImageDrawable(null)
            MediaCache.bitmapAsync(host.ctx(), m.mediaKey) { bmp ->
                if (photoKey == m.mediaKey && bmp != null) photo.setImageBitmap(bmp)
            }
        }

        /** Tap → poori screen par photo. */
        private fun openFullPhoto() {
            val key = photoKey ?: bound?.mediaKey ?: return
            val bmp = MediaCache.load(host.ctx(), key)?.let { MediaCache.decodeBytes(it) } ?: return
            PhotoViewer.show(host.ctx(), bmp)
        }

        private fun rebuildChips(m: Msg) {
            chips.removeAllViews()
            if (m.rx.isEmpty()) return
            m.rx.forEach { (emoji, byMe) ->
                val on = byMe
                chips.addView(TextView(host.ctx()).apply {
                    text = "$emoji 1"
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    minHeight = host.dp(28)
                    setPadding(host.dp(9), host.dp(4), host.dp(9), host.dp(4))
                    includeFontPadding = true
                    background = GradientDrawable().apply {
                        setColor(if (on) Color.argb(150, 244, 114, 182) else Color.argb(235, 34, 26, 62))
                        cornerRadius = host.dp(12).toFloat()
                        setStroke(host.dp(1), if (on) host.hex("#f472b6") else Color.argb(120, 255, 255, 255))
                    }
                    setOnClickListener { bound?.let { host.onChipClick(it, emoji) } }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = host.dp(4)
                })
            }
        }

        /** Swipe karke reply — purana wala code, ab ViewHolder ke sath (har row ka apna nahi). */
        private fun attachSwipe() {
            val startAt = host.dp(12).toFloat()
            val maxSlide = host.dp(110).toFloat()
            val fireAt = host.dp(60).toFloat()
            var sx = 0f
            var sy = 0f
            var dx = 0f
            var swiping = false

            val listener = View.OnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = ev.rawX; sy = ev.rawY; dx = 0f; swiping = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val ddx = ev.rawX - sx
                        val ddy = ev.rawY - sy
                        if (Math.abs(ddx) > startAt || Math.abs(ddy) > startAt) {
                            v.cancelLongPress()
                            bubble.cancelLongPress()
                        }
                        if (!swiping && Math.abs(ddx) > host.dp(6) && Math.abs(ddx) > Math.abs(ddy)) {
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
                            val prog = (slide / fireAt).coerceIn(0f, 1f)
                            handle.alpha = prog
                            handle.scaleX = 0.7f + 0.3f * prog
                            handle.scaleY = handle.scaleX
                            handle.rotation = -25f * (1f - prog)
                            handle.background = swipeBg(slide >= fireAt)
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!swiping) false
                        else {
                            val fire = dx > fireAt && ev.actionMasked == MotionEvent.ACTION_UP
                            row.animate().translationX(0f).setDuration(200L)
                                .setInterpolator(DecelerateInterpolator()).start()
                            handle.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).rotation(0f)
                                .setDuration(180L).start()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            swiping = false
                            dx = 0f
                            if (fire) bound?.let { host.onSwipeReply(it) }
                            true
                        }
                    }
                    else -> false
                }
            }
            bubble.setOnTouchListener(listener)
            row.setOnTouchListener(listener)
        }

        private fun swipeBg(armed: Boolean): GradientDrawable = GradientDrawable().apply {
            setColor(if (armed) Color.argb(80, 49, 209, 88) else Color.argb(46, 84, 232, 255))
            cornerRadius = host.dp(12).toFloat()
            setStroke(host.dp(1), if (armed) host.hex("#31d158") else Color.argb(115, 84, 232, 255))
        }

        companion object {
            private fun buildRoot(host: ChatHost, mine: Boolean): LinearLayout =
                LinearLayout(host.ctx()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM or (if (mine) Gravity.END else Gravity.START)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = host.dp(10) }
                }
        }
    }

    // ------------------------------------------------------------ din / typing / khaali

    private class DayVH(host: ChatHost) : RecyclerView.ViewHolder(TextView(host.ctx()).apply {
        textSize = 10.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.argb(140, 255, 255, 255))
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gravity = Gravity.CENTER_HORIZONTAL
    }) {
        private val tv = itemView as TextView
        fun bind(day: String) { tv.text = day }
    }

    private class TypingVH(host: ChatHost) : RecyclerView.ViewHolder(build(host)) {
        companion object {
            private fun build(host: ChatHost): View {
                val row = LinearLayout(host.ctx()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM or Gravity.START
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                row.addView(TextView(host.ctx()).apply {
                    text = host.peerName().first().uppercase()
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(host.peerColorInt())
                    }
                }, LinearLayout.LayoutParams(host.dp(34), host.dp(34)))

                val col = LinearLayout(host.ctx()).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(host.ctx()).apply {
                    text = host.peerName()
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(host.peerColorInt())
                    alpha = 0.8f
                    setSingleLine(true)
                    setPadding(host.dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = host.dp(2)
                })

                val dots = TypingDots(host.ctx())
                col.addView(FrameLayout(host.ctx()).apply {
                    setPadding(host.dp(16), host.dp(11), host.dp(16), host.dp(11))
                    background = host.roundBox(Color.argb(23, 255, 255, 255),
                        Color.argb(36, 255, 255, 255), 22, 1)
                    addView(dots, FrameLayout.LayoutParams(
                        dots.desiredWidth(), dots.desiredHeight(), Gravity.START))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                row.addView(col, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = host.dp(8) })
                return row
            }
        }
    }

    private class EmptyVH(host: ChatHost) : RecyclerView.ViewHolder(LinearLayout(host.ctx()).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(16))
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(host.ctx()).apply {
            text = "👋"; textSize = 26f; gravity = Gravity.CENTER
        })
        addView(TextView(host.ctx()).apply {
            text = "${host.peerName()} ko salam bhejo!"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(host.hex("#a9a6c8"))
            setLineSpacing(0f, 1.7f)
        })
    })

    // ---------------------------------------------------------------- diff

    private class RowDiff(
        private val old: List<Row>,
        private val next: List<Row>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = next.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].key == next[n].key
        override fun areContentsTheSame(o: Int, n: Int) =
            old[o].sig == next[n].sig && old[o].kind == next[n].kind
    }
}

/** 65 second -> "1:05" (website ka fmtDur) — voice bubble ke second. */
private fun fmtDur(sec: Int): String {
    val v = Math.max(0, sec)
    return (v / 60).toString() + ":" + String.format("%02d", v % 60)
}
