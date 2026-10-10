package app.party.wpnative

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PARTY BAR ke baad aane wala **Party Lobby v48** — 100% pure native Android.
 *
 * Original website ko sirf naap/design reference banaya hai. Yahan koi WebView,
 * HTML, JavaScript, network image ya animated GIF nahi:
 *  - chhe approved WebP sections APK ke andar bundled hain;
 *  - room name, Back aur Enter asli native views hain;
 *  - notes, beat bars aur button sheen asli Android animators hain;
 *  - extra screen height website v48 ki tarah 6 weighted gaps mein bant-ti hai.
 */
class PartyLobbyActivity : Activity() {

    private val motions = ArrayList<Animator>()
    private lateinit var status: TextView

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun hex(v: String): Int = Color.parseColor(v)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildLobby())
    }

    override fun onResume() {
        super.onResume()
        CallMiniBar.attach(this)
        if (::status.isInitialized && !getSharedPreferences("wp_native", Context.MODE_PRIVATE)
                .getBoolean("party_live", false)) {
            status.text = "Yet Not Joined, Tap On Enter Party"
            status.setTextColor(hex("#bfc1ed"))
        }
    }

    private fun buildLobby(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(hex("#020616"))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(hex("#020616"))
        }
        scroll.addView(col, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---------------- Header: exact artwork + native Back-to-Chat button
        val header = LobbyScene(this, R.drawable.lobby_v48_header, 170, bottomFade = .24f)
        val back = backButton()
        header.addPercent(back, left = 0f, top = .26f, width = .085f, height = 0f,
            right = .046f, square = true, minWidthDp = 44f)
        col.addView(header, sceneLp())
        addGap(col, 8, .80f)

        // ---------------- Hero: approved couple art + 3 floating native notes
        val hero = LobbyScene(this, R.drawable.lobby_v48_hero, 480, topFade = .08f, bottomFade = .09f)
        addNote(hero, "♪", .43f, .85f, "#db61ff", 300L)
        addNote(hero, "♫", .93f, .91f, "#5ccfff", 1800L)
        addNote(hero, "♪", .94f, .18f, "#db61ff", 2800L)
        col.addView(hero, sceneLp())
        addGap(col, 4, .50f)

        // ---------------- Four feature cards + five live beat bars
        val features = LobbyScene(this, R.drawable.lobby_v48_features, 235, topFade = .03f, bottomFade = .03f)
        val beat = buildBeatBars()
        features.addPercent(beat, .581f, .4857f, .064f, .0752f)
        col.addView(features, sceneLp())
        addGap(col, 4, 1f)

        // ---------------- Room card; room naam baked image nahi, real saved value hai
        val room = LobbyScene(this, R.drawable.lobby_v48_room, 275, topFade = .08f, bottomFade = .08f)
        val roomName = TextView(this).apply {
            text = getSharedPreferences("wp_native", Context.MODE_PRIVATE)
                .getString("room", "")?.trim().takeUnless { it.isNullOrBlank() } ?: "Your room"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = -0.01f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        room.addPercent(roomName, .277f, .3636f, .54f, 0f)
        col.addView(room, sceneLp())
        addGap(col, 4, 1f)

        // ---------------- Enter CTA; artwork stable, native clipped shine moves over it
        val cta = LobbyScene(this, R.drawable.lobby_v48_cta, 175, topFade = .07f, bottomFade = .03f)
        val enter = shineButton().apply {
            contentDescription = "Enter Party"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Party Bar sirf Lobby kholti hai. Asli join isi tap par, first-page
                // ke saved name + room + selected tower ke mutabiq hota hai.
                val p = getSharedPreferences("wp_native", Context.MODE_PRIVATE)
                val name = p.getString("name", "")?.trim().orEmpty()
                val savedRoom = p.getString("room", "")?.trim().orEmpty()
                if (name.isBlank() || savedRoom.isBlank()) {
                    status.text = "First page par Name aur Room save karo"
                    status.setTextColor(hex("#fda4af"))
                    Toast.makeText(this@PartyLobbyActivity,
                        "Pehle first page par Name aur Room likho", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                status.text = "Joining $savedRoom on ${PartyTower.towerLabel(p.getInt("tower", 0))}…"
                status.setTextColor(hex("#86efac"))
                startActivity(Intent(this@PartyLobbyActivity, PartyRoomActivity::class.java)
                    .putExtra("join_from_lobby", true))
                overridePendingTransition(0, 0)
            }
        }
        cta.addPercent(enter, .055f, .1208f, .89f, .8448f)
        col.addView(cta, sceneLp())
        addGap(col, 4, .35f)

        // Website ka real status (Enter se pehle).
        status = TextView(this).apply {
            text = "Yet Not Joined, Tap On Enter Party"
            textSize = 11f
            setTextColor(hex("#bfc1ed"))
            gravity = Gravity.CENTER
            includeFontPadding = false
            minHeight = dp(15f)
            setPadding(dp(9f), 0, dp(9f), 0)
        }
        col.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addGap(col, 8, .85f)

        // ---------------- Footer waves + last floating note
        val footer = LobbyScene(this, R.drawable.lobby_v48_footer, 197)
        addNote(footer, "♫", .81f, .18f, "#db61ff", 800L)
        col.addView(footer, sceneLp())

        return scroll
    }

    private fun sceneLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** v48: bachi hui height inhi weights ke mutabiq sections ke darmiyan. */
    private fun addGap(parent: LinearLayout, baseDp: Int, weight: Float) {
        parent.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(hex("#020616"), hex("#05081b"), hex("#020616")))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(baseDp.toFloat()), weight))
    }

    private fun backButton(): View {
        val frame = FrameLayout(this).apply {
            contentDescription = "Back to Chat"
            isClickable = true
            isFocusable = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#0b0a25"), hex("#080d22"))).apply {
                cornerRadius = dp(13f).toFloat()
                setStroke(dp(1f), Color.argb(77, 117, 97, 200))
            }
            elevation = dp(2f).toFloat()
            setOnClickListener { backToChat() }
        }
        frame.addView(LobbyBackArrow(this), FrameLayout.LayoutParams(
            dp(29f), dp(29f), Gravity.CENTER))
        return frame
    }

    /** Top-right arrow website ki tarah hamesha Chat inbox kholta hai—even Calls se aaye hon. */
    private fun backToChat() {
        startActivity(Intent(this, InboxActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ))
        overridePendingTransition(0, 0)
        finish()
    }

    /** CSS wpl46Note: 3.8s mein neeche se upar, halka rotate, fade in/out. */
    private fun addNote(scene: LobbyScene, glyph: String, x: Float, y: Float, color: String, phaseMs: Long) {
        val note = TextView(this).apply {
            text = glyph
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(hex(color))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(9f).toFloat(), 0f, 0f, hex(color))
            alpha = 0f
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        scene.addPercent(note, x, y, .09f, .17f)
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedFraction
                note.alpha = when {
                    p < .20f -> .90f * (p / .20f)
                    p < .70f -> .90f - .10f * ((p - .20f) / .50f)
                    else -> .80f * (1f - (p - .70f) / .30f)
                }.coerceIn(0f, .9f)
                note.translationX = dp(-3f + 12f * p).toFloat()
                note.translationY = dp(15f - 50f * p).toFloat()
                note.rotation = -15f + 29f * p
            }
            start()
            currentPlayTime = phaseMs
        }
        motions.add(a)
    }

    /** CSS wpl46Beat: 5 magenta bars, har bar 170ms alag phase. */
    private fun buildBeatBars(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        repeat(5) { i ->
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(hex("#ee64ff"))
                    cornerRadius = dp(2f).toFloat()
                }
                alpha = .6f
            }
            row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(.45f)
            })
            bar.post { bar.pivotY = bar.height.toFloat() }
            val a = ObjectAnimator.ofFloat(bar, View.SCALE_Y, .18f, 1f).apply {
                duration = 720L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { bar.alpha = .60f + .40f * (it.animatedFraction) }
                start()
                currentPlayTime = i * 170L
            }
            motions.add(a)
        }
        return row
    }

    /** CSS wpl46Shine: 3.6s ki safed chamak Enter button se guzarti hai. */
    private fun shineButton(): FrameLayout {
        val button = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(25f).toFloat()
            }
            clipToOutline = true
        }
        val shine = View(this).apply {
            background = LobbySheenDrawable()
            rotation = -18f
            alpha = 0f
        }
        button.addView(shine, FrameLayout.LayoutParams(dp(110f),
            ViewGroup.LayoutParams.MATCH_PARENT).apply { height = dp(110f); gravity = Gravity.CENTER_VERTICAL })
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedFraction
                val travel = button.width.toFloat() + dp(220f)
                shine.translationX = -dp(140f) + travel * p
                shine.alpha = when {
                    p < .05f -> 0f
                    p < .15f -> (p - .05f) / .10f * .78f
                    p < .75f -> .78f
                    p < .85f -> (1f - (p - .75f) / .10f) * .78f
                    else -> 0f
                }
            }
            start()
        }
        motions.add(a)
        return button
    }

    override fun onStart() {
        super.onStart()
        motions.forEach { if (it.isPaused) it.resume() }
    }

    override fun onStop() {
        motions.forEach { if (it.isStarted && !it.isPaused) it.pause() }
        super.onStop()
    }

    override fun onDestroy() {
        motions.forEach { it.cancel() }
        motions.clear()
        super.onDestroy()
    }
}

/** One artwork section. Image ratio never stretches; overlays use original CSS percentages. */
private class LobbyScene(
    ctx: Context,
    imageRes: Int,
    private val sourceHeight: Int,
    topFade: Float = 0f,
    bottomFade: Float = 0f
) : ViewGroup(ctx) {

    private data class Slot(
        val view: View,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val right: Float,
        val square: Boolean,
        val minWidthDp: Float
    )

    private val density = resources.displayMetrics.density
    private val art = ImageView(ctx).apply {
        setImageResource(imageRes)
        scaleType = ImageView.ScaleType.FIT_XY
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val edgeFade = LobbyEdgeFade(ctx, topFade, bottomFade)
    private val slots = ArrayList<Slot>()

    init {
        clipChildren = true
        clipToPadding = true
        addView(art)
        // Website ki mask-image fades: art fade hoti hai, native controls/notes nahi.
        addView(edgeFade)
    }

    fun addPercent(
        view: View,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        right: Float = -1f,
        square: Boolean = false,
        minWidthDp: Float = 0f
    ) {
        slots.add(Slot(view, left, top, width, height, right, square, minWidthDp))
        addView(view)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = (w * sourceHeight / 941f).roundToInt()
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec))
        art.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
        edgeFade.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
        slots.forEach { s ->
            val ow = max((measuredWidth * s.width).roundToInt(), (s.minWidthDp * density).roundToInt())
            val oh = when {
                s.square -> ow
                s.height > 0f -> (measuredHeight * s.height).roundToInt()
                else -> 0
            }
            val hs = if (oh > 0) MeasureSpec.makeMeasureSpec(oh, MeasureSpec.EXACTLY)
                     else MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.AT_MOST)
            s.view.measure(MeasureSpec.makeMeasureSpec(ow, MeasureSpec.EXACTLY), hs)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        art.layout(0, 0, measuredWidth, measuredHeight)
        edgeFade.layout(0, 0, measuredWidth, measuredHeight)
        slots.forEach { s ->
            val x = if (s.right >= 0f)
                measuredWidth - (measuredWidth * s.right).roundToInt() - s.view.measuredWidth
            else (measuredWidth * s.left).roundToInt()
            val y = (measuredHeight * s.top).roundToInt()
            s.view.layout(x, y, x + s.view.measuredWidth, y + s.view.measuredHeight)
        }
    }
}

/** CSS mask-image ka native barabar: scene art ko dark background mein narm fade. */
private class LobbyEdgeFade(
    ctx: Context,
    private val topPart: Float,
    private val bottomPart: Float
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Color.parseColor("#020616")

    override fun onDraw(c: Canvas) {
        if (topPart > 0f) {
            val h = height * topPart
            p.shader = LinearGradient(0f, 0f, 0f, h,
                bg, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), h, p)
        }
        if (bottomPart > 0f) {
            val y = height * (1f - bottomPart)
            p.shader = LinearGradient(0f, y, 0f, height.toFloat(),
                Color.TRANSPARENT, bg, Shader.TileMode.CLAMP)
            c.drawRect(0f, y, width.toFloat(), height.toFloat(), p)
        }
        p.shader = null
    }
}

/** Website ka 24x24 stroke Back arrow—emoji/text glyph nahi. */
private class LobbyBackArrow(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#baa7ff")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        path.reset()
        path.moveTo(w * .76f, h * .50f)
        path.lineTo(w * .24f, h * .50f)
        path.moveTo(w * .43f, h * .29f)
        path.lineTo(w * .23f, h * .50f)
        path.lineTo(w * .43f, h * .71f)
        c.drawPath(path, p)
    }
}

/** Narrow white diagonal sheen used inside the clipped Enter button. */
private class LobbySheenDrawable : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        p.shader = LinearGradient(0f, 0f, bounds.width().toFloat(), 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(8, 255, 255, 255),
                Color.argb(110, 255, 255, 255), Color.argb(8, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, .35f, .50f, .65f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(bounds, p)
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { p.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
