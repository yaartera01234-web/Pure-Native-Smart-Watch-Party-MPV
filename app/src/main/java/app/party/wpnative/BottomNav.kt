package app.party.wpnative

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Neeche ka nav = website #yp-bar ka copy. Messages aur Calls dono isi ko use karte hain.
 * active = "party" | "chat" | "call"
 */
fun buildBottomNav(act: Activity, active: String): View {
    val density = act.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val frame = FrameLayout(act)
    val fillLayer = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(240, 60, 37, 100), Color.argb(245, 30, 16, 54))).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.argb(43, 255, 255, 255))
    }
    // Gloss: upar wali roshni (website ka ::before), background ki layer ke taur par
    val glossLayer = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        Color.argb(56, 255, 255, 255), Color.argb(13, 255, 255, 255), Color.argb(0, 255, 255, 255))).apply {
        cornerRadius = dp(22).toFloat()
    }
    frame.background = LayerDrawable(arrayOf(fillLayer, glossLayer))
    frame.clipToOutline = true

    /**
     * Tab badlo — **purani screen ko tod kar nayi mat banao**.
     *
     * Pehle har tap par `finish()` karke nayi Activity ban ti thi -> poori screen
     * (header + search + list + nav + gradients) dobara banti thi -> 80-200ms ka jhatka.
     * Ab CLEAR_TOP + SINGLE_TOP se wahi purani screen turant aage aa jati hai
     * (uske upar wali hat jati hai, to back-history bhi saaf rahti hai).
     */
    fun open(target: Class<*>) {
        val i = Intent(act, target).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        act.startActivity(i)
        act.overridePendingTransition(0, 0)
    }

    val row = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(5), dp(5), dp(5), dp(5))
    }
    val partyDot = View(act).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4ade80")) }
    }
    ObjectAnimator.ofFloat(partyDot, "alpha", 0.35f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        start()
    }

    fun tab(key: String, icon: String, label: String, dot: View?): View {
        val on = key == active
        val tabView = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), dp(8), dp(2), dp(6))
            if (on) {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.argb(140, 196, 120, 255), Color.argb(117, 255, 96, 176))).apply {
                    cornerRadius = dp(17).toFloat()
                    setStroke(dp(1), Color.argb(77, 255, 255, 255))
                }
            }
            setOnClickListener {
                if (on) return@setOnClickListener
                when (key) {
                    "party" -> {
                        // Agar koi live Party Room stack mein hai to nayi Lobby/Room
                        // mat banao. CLEAR_TOP usi exact instance ko saamne laata hai:
                        // theme, aurora aur animations jaisi thin waisi rehti hain.
                        // Live Room na ho to normal Inbox se Lobby hi khulti hai.
                        if (!PartyRoomRoute.returnToLiveRoom(act)) {
                            open(PartyLobbyActivity::class.java)
                        }
                    }
                    "chat" -> open(InboxActivity::class.java)
                    "call" -> open(CallsActivity::class.java)
                }
            }
        }
        val iconBox = FrameLayout(act)
        iconBox.clipChildren = false
        iconBox.clipToPadding = false
        iconBox.addView(NavIcon(act, icon, on), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        if (dot != null) iconBox.addView(dot, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.END or Gravity.TOP))
        if (key == "chat") {
            // Original mobile-dm-count: aggregate number; sirf display 9+ par cap hota hai.
            val unreadBadge = TextView(act).apply {
                textSize = 9f
                gravity = Gravity.CENTER
                minWidth = dp(18)
                minHeight = dp(18)
                setPadding(dp(4), 0, dp(4), 0)
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#ff5ebc"), Color.parseColor("#8b72ff"))).apply {
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), Color.argb(90, 255, 255, 255))
                }
                elevation = dp(4).toFloat()
            }
            iconBox.addView(unreadBadge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.END or Gravity.TOP
            ).apply { topMargin = -dp(7); rightMargin = -dp(8) })
            val refreshUnread: () -> Unit = {
                val count = DmInboxStore.total(act)
                unreadBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
                unreadBadge.text = if (count > 9) "9+" else count.toString()
            }
            var observing = false
            iconBox.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!observing) {
                        observing = true
                        DmInboxStore.observe(refreshUnread)
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    if (observing) {
                        observing = false
                        DmInboxStore.removeObserver(refreshUnread)
                    }
                }
            })
            refreshUnread()
        }
        tabView.addView(iconBox, LinearLayout.LayoutParams(dp(30), dp(24)))
        tabView.addView(TextView(act).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.03f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (on) Color.WHITE else Color.argb(255, 156, 138, 194))
            if (on) setShadowLayer(dp(5).toFloat(), 0f, 0f, Color.argb(140, 255, 140, 200))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        return tabView
    }

    row.addView(tab("party", "party", "Party", partyDot), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(tab("chat", "chat", "Chat", null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(tab("call", "call", "Call", null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    frame.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // Sheen: chamakti hui safed patti, website ki tarah har 5.2s mein ek baar guzarti hai
    val sheenW = dp(110)
    val sheenH = dp(72)
    val sheen = View(act).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            Color.argb(0, 255, 255, 255), Color.argb(66, 255, 255, 255),
            Color.argb(16, 255, 255, 255), Color.argb(0, 255, 255, 255)))
        rotation = 20f
        alpha = 0f
    }
    frame.addView(sheen, FrameLayout.LayoutParams(sheenW, sheenH))
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { a ->
            val p = a.animatedFraction
            val s = ((p - 0.09f) / 0.35f).coerceIn(0f, 1f)
            sheen.translationX = -sheenW + s * (frame.width + sheenW).toFloat()
            sheen.alpha = if (p in 0.09f..0.44f) 0.92f else 0f
        }
        start()
    }
    return frame
}

/** Website ke 24x24 stroke icons (party / chat / call), active par glow. */
private class NavIcon(ctx: Context, private val kind: String, private val on: Boolean) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val s = width / 24f
        c.save()
        c.scale(s, s)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        paint.color = if (on) Color.WHITE else Color.argb(255, 156, 138, 194)
        if (on) paint.setShadowLayer(10f, 0f, 0f, Color.argb(190, 255, 120, 190)) else paint.clearShadowLayer()
        path.reset()
        when (kind) {
            "party" -> {
                rect.set(3f, 5f, 21f, 19f)
                c.drawRoundRect(rect, 3.4f, 3.4f, paint)
                path.moveTo(10.2f, 9.4f); path.lineTo(14.8f, 12f); path.lineTo(10.2f, 14.6f); path.close()
                paint.style = Paint.Style.FILL
                c.drawPath(path, paint)
            }
            "chat" -> {
                path.moveTo(20.2f, 11.7f)
                path.cubicTo(20.2f, 15.7f, 16.5f, 18.9f, 11.9f, 18.9f)
                path.cubicTo(10.9f, 18.9f, 9.9f, 18.7f, 9.0f, 18.4f)
                path.lineTo(4.6f, 20f)
                path.lineTo(5.8f, 16.3f)
                path.cubicTo(3.9f, 14.9f, 3.6f, 13.3f, 3.6f, 11.7f)
                path.cubicTo(3.6f, 7.7f, 7.3f, 4.5f, 11.9f, 4.5f)
                path.cubicTo(16.5f, 4.5f, 20.2f, 7.7f, 20.2f, 11.7f)
                path.close()
                c.drawPath(path, paint)
            }
            else -> {
                path.moveTo(6.4f, 3.6f); path.lineTo(9.3f, 3.6f); path.lineTo(10.7f, 7.4f); path.lineTo(8.8f, 8.8f)
                path.cubicTo(9.6f, 11.2f, 12.8f, 14.4f, 15.2f, 15.2f)
                path.lineTo(16.6f, 13.3f); path.lineTo(20.4f, 14.7f); path.lineTo(20.4f, 17.6f)
                path.cubicTo(20.4f, 18.9f, 19.4f, 19.9f, 18.3f, 19.5f)
                path.cubicTo(14f, 19.5f, 4.5f, 10f, 4.5f, 5.7f)
                path.cubicTo(4.5f, 4.6f, 5.3f, 3.6f, 6.4f, 3.6f)
                path.close()
                c.drawPath(path, paint)
            }
        }
        c.restore()
    }
}
