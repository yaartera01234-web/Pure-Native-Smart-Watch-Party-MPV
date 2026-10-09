package app.party.wpnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Enter Party ke baad wali **native Party Room** screen.
 *
 * Is batch mein poora room layout hai, magar jaan-boojh kar player engine nahi:
 * Rave jitna full-width 16:9 kala box uski final jagah reserve karta hai. MPV,
 * controls, playback aur sync last player batch mein isi box ke andar aayenge.
 */
class PartyRoomActivity : Activity() {

    private data class Palette(val bg: IntArray, val accent: IntArray)

    private val prefs by lazy { getSharedPreferences("wp_native", Context.MODE_PRIVATE) }
    private val palettes by lazy {
        listOf(
            Palette(cols("#050719", "#16112d", "#0b1829"), cols("#fa35de", "#c03cff", "#36d9fa")),
            Palette(cols("#0f0c29", "#302b63", "#24243e"), cols("#ff66bd", "#a477ff", "#55baff")),
            Palette(cols("#051219", "#11232d", "#0b2927"), cols("#358efa", "#3cb7ff", "#36faed")),
            Palette(cols("#19050c", "#2d111b", "#29180b"), cols("#fa3549", "#ff3c83", "#fa8e36")),
            Palette(cols("#05190f", "#112d1f", "#0b2729"), cols("#35fac5", "#3cff9e", "#36eafa")),
            Palette(cols("#191305", "#2d2511", "#29270b"), cols("#e88f47", "#eabc51", "#e8db48"))
        )
    }
    private val palette by lazy { palettes[prefs.getInt("theme", 1).coerceIn(palettes.indices)] }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun hex(v: String): Int = Color.parseColor(v)
    private fun cols(vararg v: String): IntArray = v.map(::hex).toIntArray()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildRoom())
    }

    private fun buildRoom(): View {
        val root = PartyRoomBackdrop(this, palette.bg, palette.accent)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Link/Play/Queue/Search — original room geometry; kaam player ke final batch mein.
        col.addView(buildSourceRow(), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(51f)).apply {
            setMargins(dp(8f), dp(8f), dp(8f), 0)
        })

        // RAVE SIZE: poori screen width × 9/16. Filhaal sirf reserved native box.
        col.addView(RavePlayerSlot(this), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7f)
        })

        col.addView(buildPlaylist(), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)).apply {
            setMargins(dp(8f), dp(7f), dp(8f), 0)
        })

        // Bachi hui poori height room members + live party chat ko.
        col.addView(buildPartyChat(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(8f), dp(7f), dp(8f), dp(8f))
        })

        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = roundBox(Color.argb(148, 6, 7, 19), Color.argb(33, 255, 255, 255), 0f, 1f)
            elevation = dp(2f).toFloat()
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(PartyBarsLogo(this), lp(dp(23f), dp(23f)))
        brand.addView(PartyGradientLabel(this, palette.accent).apply {
            text = "Watch Party"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(7f)
        })
        bar.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(TextView(this).apply {
            text = "●  1 online"
            textSize = 11f
            setTextColor(hex("#86efac"))
            gravity = Gravity.CENTER
            setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
            background = roundBox(Color.argb(33, 74, 222, 128), Color.argb(32, 255, 255, 255), 20f, 1f)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val chat = headerButton("💬").apply {
            contentDescription = "Messages"
            setOnClickListener {
                startActivity(Intent(this@PartyRoomActivity, InboxActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                overridePendingTransition(0, 0)
            }
        }
        bar.addView(chat, lp(dp(34f), dp(34f)).apply { leftMargin = dp(7f) })
        bar.addView(headerButton("🎨").apply { contentDescription = "Theme" },
            lp(dp(34f), dp(34f)).apply { leftMargin = dp(5f) })
        return bar
    }

    private fun headerButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundBox(Color.argb(31, 255, 255, 255),
            Color.argb(46, 255, 255, 255), 10f, 1f)
    }

    private fun buildSourceRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "YouTube / MP4 / MP3 link..."
            setHintTextColor(hex("#88869b"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12f), 0, dp(9f), 0)
            background = roundBox(Color.argb(120, 4, 5, 17),
                Color.argb(36, 255, 255, 255), 12f, 1f)
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(44f), 1f))

        row.addView(sourceButton("▶ Play", intArrayOf(hex("#1AD07A"), hex("#0ABF6A"))),
            lp(dp(59f), dp(44f)).apply { leftMargin = dp(6f) })
        row.addView(sourceButton("＋", intArrayOf(hex("#FF5AA8"), hex("#B46BFF"))),
            lp(dp(43f), dp(44f)).apply { leftMargin = dp(6f) })
        row.addView(searchButton(), lp(dp(43f), dp(44f)).apply { leftMargin = dp(6f) })
        return row
    }

    private fun sourceButton(label: String, colors: IntArray): TextView = TextView(this).apply {
        text = label
        textSize = if (label.length > 2) 11.5f else 20f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(12f).toFloat()
        }
        setOnClickListener { playerLater() }
    }

    private fun searchButton(): View {
        val f = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#EF2B2D"), hex("#C5162E"))).apply {
                cornerRadius = dp(12f).toFloat()
            }
            setOnClickListener { playerLater() }
        }
        f.addView(TextView(this).apply {
            text = "▶"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.CENTER))
        f.addView(TextView(this).apply {
            text = "⌕"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(hex("#111322"))
            }
        }, FrameLayout.LayoutParams(dp(17f), dp(17f), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(3f), dp(3f))
        })
        return f
    }

    private fun buildPlaylist(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            background = glassBox(15f)
        }
        row.addView(TextView(this).apply {
            text = "📋  Playlist (0)"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#d4caff"))
        })
        row.addView(TextView(this).apply {
            text = "▼ Tap to expand"
            textSize = 10f
            setTextColor(Color.argb(150, 212, 202, 255))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8f)
        })
        row.addView(TextView(this).apply {
            text = "▼"
            textSize = 11f
            setTextColor(hex("#c4b5fd"))
        })
        return row
    }

    private fun buildPartyChat(): View {
        val chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBox(19f)
        }

        // Party Members — filhaal apna real naam/DP; network members later party backend se.
        val members = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(7f))
            background = roundBox(Color.argb(5, 255, 255, 255),
                Color.argb(20, 255, 255, 255), 0f, 0f)
        }
        members.addView(TextView(this).apply {
            text = "👥 Party Members (1)"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#d4caff"))
        })
        val me = WpUser.me(this)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(2f), dp(9f), dp(2f))
            background = roundBox(Color.argb(19, 255, 255, 255), palette.accent[1], 20f, 1f)
        }
        chip.addView(DpStore.circle(this, me, palette.accent[1], 20, isMe = true), lp(dp(20f), dp(20f)))
        chip.addView(TextView(this).apply {
            text = me
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6f)
        })
        members.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(25f)).apply { topMargin = dp(3f) })
        chat.addView(members, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(59f)))

        // Live room conversation will occupy all free space. No fake/test messages.
        chat.addView(FrameLayout(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        chat.addView(buildComposer(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return chat
    }

    private fun buildComposer(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(5f), dp(8f), dp(8f))
            background = roundBox(Color.argb(61, 5, 6, 18),
                Color.argb(23, 255, 255, 255), 0f, 1f)
        }
        val sc = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val emojis = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("😂", "❤️", "🔥", "😭", "👏", "🥳", "👍").forEach { e ->
            emojis.addView(TextView(this).apply {
                text = e
                textSize = 18f
                gravity = Gravity.CENTER
            }, lp(dp(35f), dp(32f)))
        }
        emojis.addView(smallComposerButton("GIF"), lp(dp(42f), dp(32f)).apply { leftMargin = dp(3f) })
        emojis.addView(smallComposerButton("▧"), lp(dp(38f), dp(32f)).apply { leftMargin = dp(4f) })
        emojis.addView(smallComposerButton("🎤"), lp(dp(42f), dp(32f)).apply { leftMargin = dp(4f) })
        sc.addView(emojis, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34f)))
        box.addView(sc, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(35f)))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(EditText(this).apply {
            hint = "Message likho..."
            setHintTextColor(hex("#88869b"))
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            setSingleLine(true)
            setPadding(dp(13f), 0, dp(13f), 0)
            background = roundBox(Color.argb(87, 3, 4, 15),
                Color.argb(42, 255, 255, 255), 23f, 1f)
        }, LinearLayout.LayoutParams(0, dp(43f), 1f))
        inputRow.addView(TextView(this).apply {
            text = "➤"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.accent).apply {
                shape = GradientDrawable.OVAL
            }
        }, lp(dp(43f), dp(43f)).apply { leftMargin = dp(7f) })
        box.addView(inputRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(43f)).apply { topMargin = dp(4f) })
        return box
    }

    private fun smallComposerButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = if (label == "GIF") 11f else 16f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.accent[0], palette.accent[1], palette.accent[2])).apply {
            cornerRadius = dp(10f).toFloat()
        }
    }

    private fun playerLater() {
        Toast.makeText(this, "Player aur sync last step mein", Toast.LENGTH_SHORT).show()
    }

    private fun glassBox(radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(184, 28, 24, 59), Color.argb(160, 8, 9, 24))).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1f), Color.argb(35, 255, 255, 255))
        }

    private fun roundBox(fill: Int, stroke: Int, radius: Float, strokeDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (strokeDp > 0f) setStroke(dp(strokeDp), stroke)
        }
}

/** Full-width Rave-style 16:9 slot; landscape mein original 46vh safety cap. */
private class RavePlayerSlot(ctx: Context) : FrameLayout(ctx) {
    init {
        contentDescription = "Video player"
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.BLACK, Color.rgb(5, 5, 12), Color.BLACK)).apply {
            setStroke((resources.displayMetrics.density).roundToInt(), Color.argb(38, 255, 255, 255))
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val ratioHeight = (w * 9f / 16f).roundToInt()
        val cap = (resources.displayMetrics.heightPixels * .46f).roundToInt()
        val h = min(ratioHeight, cap)
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

/** Selected theme ka native aurora + tiny-star background. */
private class PartyRoomBackdrop(
    ctx: Context,
    private val bg: IntArray,
    private val accent: IntArray
) : FrameLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, bg, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * .08f, 0f, w * .72f,
            Color.argb(55, Color.red(accent[0]), Color.green(accent[0]), Color.blue(accent[0])),
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * .65f, p)
        p.shader = RadialGradient(w, 0f, w * .66f,
            Color.argb(48, Color.red(accent[2]), Color.green(accent[2]), Color.blue(accent[2])),
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * .62f, p)
        p.shader = null
        p.color = Color.argb(24, 255, 255, 255)
        val step = resources.displayMetrics.density * 5f
        var y = step
        var row = 0
        while (y < h * .85f) {
            var x = if (row % 2 == 0) step else step * .5f
            while (x < w) { c.drawCircle(x, y, .55f, p); x += step }
            y += step; row++
        }
    }
}

/** Original SVG ke 5 cyan-purple-pink equalizer bars. */
private class PartyBarsLogo(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(
        Color.parseColor("#54e8ff"), Color.parseColor("#8b72ff"), Color.parseColor("#ff5ebc"),
        Color.parseColor("#8b72ff"), Color.parseColor("#54e8ff"))
    override fun onDraw(c: Canvas) {
        val sx = width / 24f; val sy = height / 24f
        val tops = floatArrayOf(8f, 5f, 2.6f, 6.4f, 9.6f)
        for (i in 0..4) {
            p.color = colors[i]
            val l = (2.4f + i * 4.3f) * sx
            c.drawRoundRect(RectF(l, tops[i] * sy, l + 2.7f * sx, 21f * sy),
                1.35f * sx, 1.35f * sx, p)
        }
    }
}

/** Header title ka real gradient text. */
private class PartyGradientLabel(ctx: Context, private val colors: IntArray) : TextView(ctx) {
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        invalidate()
    }
}
