package app.party.wpnative

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** Actions emitted by the inline Rave player. Playback publication stays in the Activity. */
internal interface PartyPlayerActions {
    fun onTogglePlayback()
    fun onSeekTo(seconds: Double)
    fun onSeekBy(seconds: Double)
    fun onToggleMute()
    fun onFullscreen()
    fun onAudioTracks()
    fun onQuality()
    fun onMiniChanged(mini: Boolean)
}

/**
 * Native twin of Smart Music Watch Party's large Rave card and its 66dp mini bar.
 * The real MPV SurfaceView lives in [surfaceHost]; every visible control is native.
 */
internal class PartyPlayerView(context: Context) : FrameLayout(context) {
    val surfaceHost = FrameLayout(context)

    private val shade = VideoShade(context)
    private val audioArt = RaveAudioCanvas(context)
    private val tapLayer = View(context)
    private val idle = LinearLayout(context)
    private val loading = TextView(context)
    private val top = LinearLayout(context)
    private val badge = TextView(context)
    private val title = TextView(context)
    private val collapse = TextView(context)
    private val center = LinearLayout(context)
    private val play = TextView(context)
    private val bottom = LinearLayout(context)
    private val seek = SeekBar(context)
    private val time = TextView(context)
    private val audio = TextView(context)
    private val quality = TextView(context)
    private val mute = TextView(context)
    private val fullscreen = TextView(context)
    private val miniLayer = LinearLayout(context)
    private val miniTitle = TextView(context)
    private val miniSub = TextView(context)
    private val miniPlay = TextView(context)
    private val miniExpand = TextView(context)

    /** Theme sirf compact mini-player par lagti hai; expanded/full player fixed Rave look hai. */
    private var miniTheme: WpTheme = WpThemes.all[1]
    private var actions: PartyPlayerActions? = null
    private var mini = false
    private var fullScreenHost = false
    private var dragging = false
    private var duration = 0.0
    private var hasMedia = false
    private var playing = false
    private var muted = false
    private var audioMode = false
    private var controlsVisible = true
    private val controlHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable {
        if (hasMedia && !mini && !fullScreenHost && !dragging) setControlsVisible(false)
    }

    init {
        contentDescription = "MPV video player"
        isClickable = true
        background = largePlayerBackground()
        clipToOutline = true

        surfaceHost.setBackgroundColor(Color.BLACK)
        addView(surfaceHost, LayoutParams(-1, -1))

        audioArt.visibility = GONE
        addView(audioArt, LayoutParams(-1, -1))

        // MPV/album canvas ke upar reliable blank-area tap target. SurfaceView kuch
        // devices par parent click ko kha leti thi, is liye ye explicit native layer hai.
        tapLayer.isClickable = true
        tapLayer.isFocusable = false
        tapLayer.contentDescription = "Player controls show or hide"
        tapLayer.setOnClickListener {
            if (hasMedia && !mini && !fullScreenHost) {
                if (controlsVisible) setControlsVisible(false) else wakeControls()
            }
        }
        addView(tapLayer, LayoutParams(-1, -1))

        shade.visibility = GONE
        addView(shade, LayoutParams(-1, -1))

        idle.orientation = LinearLayout.VERTICAL
        idle.gravity = Gravity.CENTER
        idle.setPadding(dp(24), dp(16), dp(24), dp(16))
        idle.addView(TextView(context).apply {
            text = "▰"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(165, 180, 252))
        })
        idle.addView(TextView(context).apply {
            text = "YouTube / MP4 / MP3 link upar paste karo"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(196, 181, 253))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        idle.addView(TextView(context).apply {
            text = "Native MPV · Party ke saath sync"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(180, 165, 180, 252))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        addView(idle, LayoutParams(-1, -1))

        loading.textSize = 12f
        loading.setTextColor(Color.WHITE)
        loading.gravity = Gravity.CENTER
        loading.setPadding(dp(14), dp(9), dp(14), dp(9))
        loading.background = rounded(0xcc160f29.toInt(), 13)
        loading.visibility = GONE
        addView(loading, LayoutParams(-2, -2, Gravity.CENTER))

        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(13), dp(9), dp(8), 0)
        badge.text = "▶ MPV"
        badge.textSize = 9.5f
        badge.setTypeface(badge.typeface, android.graphics.Typeface.BOLD)
        badge.letterSpacing = .09f
        badge.setTextColor(Color.rgb(169, 245, 255))
        top.addView(badge, LinearLayout.LayoutParams(-2, dp(31)))
        title.text = "Now Playing"
        title.textSize = 12f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9); rightMargin = dp(5) })
        styleSmall(collapse, "▾")
        collapse.contentDescription = "Player mini karo"
        collapse.setOnClickListener {
            setMini(true); actions?.onMiniChanged(true)
        }
        top.addView(collapse, LinearLayout.LayoutParams(dp(34), dp(34)))
        top.visibility = GONE
        addView(top, LayoutParams(-1, dp(50), Gravity.TOP))

        center.orientation = LinearLayout.HORIZONTAL
        center.gravity = Gravity.CENTER
        center.addView(roundControl("↶\n10", 48) { actions?.onSeekBy(-10.0); wakeControls() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        stylePlay(play)
        play.setOnClickListener { actions?.onTogglePlayback(); wakeControls() }
        center.addView(play, LinearLayout.LayoutParams(dp(59), dp(59)).apply { leftMargin = dp(13); rightMargin = dp(13) })
        center.addView(roundControl("10\n↷", 48) { actions?.onSeekBy(10.0); wakeControls() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        center.visibility = GONE
        addView(center, LayoutParams(-2, -2, Gravity.CENTER))

        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(dp(13), 0, dp(13), dp(7))
        seek.max = 10_000
        seek.progressTintList = ColorStateList.valueOf(Color.rgb(255, 94, 188))
        seek.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true; controlHandler.removeCallbacks(hideControls)
            }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) time.text = "${clock(duration * value / 10_000.0)} / ${clock(duration)}"
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                if (duration > 0) actions?.onSeekTo(duration * bar.progress / 10_000.0)
                wakeControls()
            }
        })
        bottom.addView(seek, LinearLayout.LayoutParams(-1, dp(29)))
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        time.text = "00:00 / 00:00"
        time.textSize = 10.5f
        time.setTextColor(Color.rgb(226, 220, 239))
        controls.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        styleSmall(audio, "Audio")
        audio.setOnClickListener { actions?.onAudioTracks(); wakeControls() }
        controls.addView(audio, LinearLayout.LayoutParams(dp(57), dp(31)).apply { rightMargin = dp(5) })
        styleSmall(quality, "144p")
        quality.setOnClickListener { actions?.onQuality(); wakeControls() }
        controls.addView(quality, LinearLayout.LayoutParams(dp(52), dp(31)).apply { rightMargin = dp(5) })
        styleSmall(mute, "♪")
        mute.setOnClickListener { actions?.onToggleMute(); wakeControls() }
        controls.addView(mute, LinearLayout.LayoutParams(dp(35), dp(31)).apply { rightMargin = dp(5) })
        styleSmall(fullscreen, "⛶")
        fullscreen.contentDescription = "Fullscreen"
        fullscreen.setOnClickListener { actions?.onFullscreen(); wakeControls() }
        controls.addView(fullscreen, LinearLayout.LayoutParams(dp(37), dp(31)))
        bottom.addView(controls, LinearLayout.LayoutParams(-1, dp(33)))
        bottom.visibility = GONE
        addView(bottom, LayoutParams(-1, dp(67), Gravity.BOTTOM))

        miniLayer.orientation = LinearLayout.HORIZONTAL
        miniLayer.gravity = Gravity.CENTER_VERTICAL
        miniLayer.setPadding(dp(126), 0, dp(8), 0)
        val miniWords = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        miniTitle.text = "Abhi kuch nahi chal raha"
        miniTitle.textSize = 12f
        miniTitle.setTypeface(miniTitle.typeface, android.graphics.Typeface.BOLD)
        miniTitle.setTextColor(Color.WHITE)
        miniTitle.setSingleLine(true)
        miniTitle.ellipsize = TextUtils.TruncateAt.END
        miniWords.addView(miniTitle)
        miniSub.text = "tap karo: video kholo"
        miniSub.textSize = 10f
        miniSub.setTextColor(Color.rgb(165, 180, 252))
        miniWords.addView(miniSub)
        miniLayer.addView(miniWords, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        styleSmall(miniPlay, "▶")
        miniPlay.setOnClickListener { actions?.onTogglePlayback() }
        miniLayer.addView(miniPlay, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(5) })
        styleSmall(miniExpand, "⛶")
        miniExpand.setOnClickListener { setMini(false); actions?.onMiniChanged(false) }
        miniLayer.addView(miniExpand, LinearLayout.LayoutParams(dp(34), dp(34)))
        miniLayer.visibility = GONE
        miniLayer.setOnClickListener { setMini(false); actions?.onMiniChanged(false) }
        addView(miniLayer, LayoutParams(-1, -1))
    }

    fun bind(value: PartyPlayerActions) { actions = value }

    /**
     * Original theme tokens compact 66dp mini bar ko color karte hain. Expanded inline
     * aur landscape fullscreen player jaan-boojh kar original fixed Rave design rakhte hain.
     */
    fun setTheme(value: WpTheme) {
        miniTheme = value
        miniSub.setTextColor(value.accentText)
        miniPlay.setTextColor(value.buttonText)
        miniPlay.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, value.fill2).apply {
            cornerRadius = dp(10).toFloat(); setStroke(dp(1), value.inputStroke)
        }
        miniExpand.setTextColor(Color.WHITE)
        miniExpand.background = rounded(value.chip, 10, value.inputStroke)
        audioArt.setMiniTheme(value)
        applyPlayerBackground()
    }

    fun setMini(value: Boolean) {
        if (fullScreenHost) return
        mini = value && hasMedia
        controlHandler.removeCallbacks(hideControls)
        controlsVisible = true
        val surfaceLp = surfaceHost.layoutParams as LayoutParams
        val artLp = audioArt.layoutParams as LayoutParams
        if (mini) {
            surfaceLp.width = dp(118); surfaceLp.height = -1; surfaceLp.gravity = Gravity.START
            artLp.width = dp(118); artLp.height = -1; artLp.gravity = Gravity.START
        } else {
            surfaceLp.width = -1; surfaceLp.height = -1; surfaceLp.gravity = Gravity.NO_GRAVITY
            artLp.width = -1; artLp.height = -1; artLp.gravity = Gravity.NO_GRAVITY
        }
        surfaceHost.layoutParams = surfaceLp
        audioArt.layoutParams = artLp
        audioArt.setMiniMode(mini)
        applyPlayerBackground()
        refreshLayers()
        if (hasMedia && !mini) armControlsTimeout()
        requestLayout()
    }

    fun setFullscreenHost(value: Boolean) {
        fullScreenHost = value
        controlHandler.removeCallbacks(hideControls)
        if (value) {
            miniLayer.visibility = GONE
            top.visibility = GONE
            center.visibility = GONE
            bottom.visibility = GONE
            shade.visibility = GONE
            idle.visibility = GONE
            loading.visibility = GONE
            audioArt.visibility = GONE
            clipToOutline = false
        } else {
            clipToOutline = true
            setMini(mini)
            refreshLayers()
        }
        requestLayout()
    }

    fun render(
        media: Boolean,
        titleText: String,
        type: String,
        isPlaying: Boolean,
        isMuted: Boolean,
        position: Double,
        durationSeconds: Double,
        isAudio: Boolean,
        loadingText: String?,
        bufferingPercent: Int,
        audioTracks: Int,
        qualityHeight: Int
    ) {
        val becameMedia = media && !hasMedia
        hasMedia = media
        if (becameMedia) controlsVisible = true
        playing = isPlaying
        muted = isMuted
        duration = durationSeconds
        audioMode = isAudio
        title.text = titleText.ifBlank { "Now Playing" }
        miniTitle.text = title.text
        val suffix = if (type == "youtube") " · YouTube" else if (type.isNotBlank()) " · ${type.uppercase()}" else ""
        miniSub.text = "${clock(position)} / ${clock(durationSeconds)}$suffix"
        play.text = if (isPlaying) "❚❚" else "▶"
        miniPlay.text = play.text
        mute.text = if (isMuted) "🔇" else "♪"
        badge.text = when (type) {
            "youtube" -> "▶ YOUTUBE · MPV"
            "mp3" -> "✦ MP3 AUDIO"
            "hls" -> "📡 HLS · MPV"
            else -> "🎞 MPV VIDEO"
        }
        quality.visibility = if (type == "youtube") VISIBLE else GONE
        quality.text = if (qualityHeight > 0) "${qualityHeight}p" else "Quality"
        audio.text = if (audioTracks > 1) "Audio · $audioTracks" else "Audio"
        audio.isEnabled = audioTracks > 0
        audio.alpha = if (audioTracks > 0) 1f else .45f
        if (!dragging) {
            time.text = "${clock(position)} / ${clock(durationSeconds)}"
            seek.progress = if (durationSeconds > 0) (position / durationSeconds * 10_000).toInt().coerceIn(0, 10_000) else 0
        }
        seek.isEnabled = durationSeconds > 0
        audioArt.playing = isPlaying
        audioArt.trackTitle = title.text.toString()
        audioArt.invalidate()
        loading.text = loadingText ?: if (bufferingPercent > 0) "⏳ Buffering $bufferingPercent%" else ""
        loading.visibility = if (!fullScreenHost && (loadingText != null || bufferingPercent > 0)) VISIBLE else GONE
        if (!hasMedia) {
            mini = false
            audioArt.setMiniMode(false)
            applyPlayerBackground()
            controlHandler.removeCallbacks(hideControls)
            controlsVisible = true
        }
        refreshLayers()
        if (becameMedia && !mini && !fullScreenHost) armControlsTimeout()
    }

    private fun setControlsVisible(show: Boolean) {
        controlsVisible = show
        refreshLayers()
        controlHandler.removeCallbacks(hideControls)
    }

    private fun wakeControls() {
        if (!hasMedia || mini || fullScreenHost) return
        controlsVisible = true
        refreshLayers()
        armControlsTimeout()
    }

    private fun armControlsTimeout() {
        controlHandler.removeCallbacks(hideControls)
        if (hasMedia && !mini && !fullScreenHost && !dragging) {
            controlHandler.postDelayed(hideControls, 5_000L)
        }
    }

    private fun refreshLayers() {
        if (fullScreenHost) return
        idle.visibility = if (!hasMedia) VISIBLE else GONE
        miniLayer.visibility = if (hasMedia && mini) VISIBLE else GONE
        val large = hasMedia && !mini
        val chrome = large && controlsVisible
        top.visibility = if (chrome) VISIBLE else GONE
        center.visibility = if (chrome) VISIBLE else GONE
        bottom.visibility = if (chrome) VISIBLE else GONE
        shade.visibility = if (chrome && !audioMode) VISIBLE else GONE
        audioArt.visibility = if (hasMedia && audioMode) VISIBLE else GONE
        // Audio artwork simply covers MPV. Keep its host VISIBLE so MP3/M4A can
        // initialise and continue decoding even when no video frame exists.
        surfaceHost.visibility = if (hasMedia) VISIBLE else INVISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hasMedia && !mini && !fullScreenHost && controlsVisible) armControlsTimeout()
    }

    override fun onDetachedFromWindow() {
        controlHandler.removeCallbacks(hideControls)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (fullScreenHost) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        val w = MeasureSpec.getSize(widthSpec)
        val h = if (mini) dp(66) else min((w * 9f / 16f).roundToInt(),
            (resources.displayMetrics.heightPixels * .46f).roundToInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    private fun applyPlayerBackground() {
        background = if (mini) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, miniTheme.page.first(), miniTheme.page.last())).apply {
                cornerRadius = dp(19).toFloat(); setStroke(dp(1), miniTheme.panelStroke)
            }
        } else largePlayerBackground()
    }

    private fun largePlayerBackground() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.BLACK, Color.rgb(5, 5, 12), Color.BLACK)).apply {
        cornerRadius = dp(19).toFloat()
        setStroke(dp(1), Color.argb(41, 255, 255, 255))
    }

    private fun roundControl(label: String, size: Int, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(0x66261a38, size / 2)
        setOnClickListener { action() }
    }

    private fun stylePlay(v: TextView) {
        v.text = "▶"
        v.textSize = 24f
        v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE)
        v.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(255, 94, 188), Color.rgb(139, 114, 255))).apply {
            shape = GradientDrawable.OVAL
        }
        v.elevation = dp(6).toFloat()
    }

    private fun styleSmall(v: TextView, label: String) {
        v.text = label
        v.textSize = 11f
        v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE)
        v.background = rounded(0x451f1930, 10)
        v.setPadding(dp(4), 0, dp(4), 0)
    }

    private fun rounded(
        color: Int,
        radius: Int,
        stroke: Int = Color.argb(35, 255, 255, 255)
    ) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun clock(value: Double): String {
        val seconds = value.takeIf { it.isFinite() && it >= 0 }?.toInt() ?: 0
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}

/** Top/bottom premium-video shade from the original Rave player. */
private class VideoShade(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xcc04040e.toInt(), 0x0004040e, 0x0004040e, 0xe604040e.toInt()),
            floatArrayOf(0f, .28f, .59f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/** Premium MP3 canvas: neon sleeve, vinyl rings, glow and animated equalizer bars. */
private class RaveAudioCanvas(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var miniTheme: WpTheme = WpThemes.all[1]
    private var miniMode = false
    fun setMiniTheme(value: WpTheme) { miniTheme = value; if (miniMode) invalidate() }
    fun setMiniMode(value: Boolean) { miniMode = value; invalidate() }
    private val phaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator(); addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private var phase = 0f
    var playing = false
        set(value) { field = value; if (value && !phaseAnimator.isStarted) phaseAnimator.start(); invalidate() }
    var trackTitle: String = "Premium Music"

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (playing && !phaseAnimator.isStarted) phaseAnimator.start() }
    override fun onDetachedFromWindow() { phaseAnimator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val music = if (miniMode) miniTheme.music
            else intArrayOf(0xff15112e.toInt(), 0xff251743.toInt(), 0xff0b1228.toInt())
        val glowA = if (miniMode) miniTheme.musicGlowA else 0x77ff5ebc
        val glowB = if (miniMode) miniTheme.musicGlowB else 0x5554e8ff
        val artColors = if (miniMode) miniTheme.art
            else intArrayOf(0xffff66bd.toInt(), 0xff8b5cf6.toInt(), 0xff26318d.toInt())
        val accent = if (miniMode) miniTheme.accentText else 0xffffd8f0.toInt()
        val ink = if (miniMode) miniTheme.buttonText else 0xff29133d.toInt()
        val label = if (miniMode) miniTheme.accentText else 0xffa9f5ff.toInt()
        val sub = if (miniMode) (miniTheme.accentText and 0x00ffffff) or (190 shl 24)
            else 0xffb4afd1.toInt()
        val bars = if (miniMode) miniTheme.bar else intArrayOf(0xff54e8ff.toInt(), 0xffff5ebc.toInt())

        p.shader = LinearGradient(0f, 0f, w, h, music, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * .83f, 0f, w * .42f, glowA, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(0f, h, w * .45f, glowB, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null

        val art = min(h * .67f, w * .31f).coerceAtLeast(48f)
        val left = w * .055f; val top = (h - art) / 2f
        p.shader = LinearGradient(left, top, left + art, top + art, artColors, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(left, top, left + art, top + art), art * .14f, art * .14f, p); p.shader = null
        val cx = left + art / 2f; val cy = top + art / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = 0x88ffffff.toInt()
        for (i in 1..4) c.drawCircle(cx, cy, art * (.15f + i * .07f), p)
        p.style = Paint.Style.FILL; p.color = accent; c.drawCircle(cx, cy, art * .14f, p)
        p.textAlign = Paint.Align.CENTER; p.textSize = art * .17f; p.color = ink
        c.drawText("♫", cx, cy + art * .06f, p)

        val tx = left + art + w * .05f
        p.textAlign = Paint.Align.LEFT; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.color = label; p.textSize = h * .055f
        c.drawText("✦  MP3 AUDIO", tx, h * .29f, p)
        p.color = Color.WHITE; p.textSize = h * .105f
        c.drawText(trackTitle.take(28), tx, h * .48f, p)
        p.typeface = android.graphics.Typeface.DEFAULT
        p.color = sub; p.textSize = h * .055f
        c.drawText("Watch Party · Now Playing", tx, h * .58f, p)

        val base = h * .77f; val barW = maxOf(2f, w * .006f); val gap = barW * 1.75f
        p.shader = LinearGradient(0f, base - h * .15f, 0f, base, bars, null, Shader.TileMode.CLAMP)
        repeat(18) { i ->
            val wave = .28f + .72f * kotlin.math.abs(kotlin.math.sin((phase * Math.PI + i * .73))).toFloat()
            val bh = h * (.035f + .10f * if (playing) wave else .42f)
            val x = tx + i * gap
            c.drawRoundRect(x, base - bh, x + barW, base, barW, barW, p)
        }
        p.shader = null
    }
}
