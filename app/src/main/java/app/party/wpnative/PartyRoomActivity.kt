package app.party.wpnative

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
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
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import kotlin.math.roundToInt

/** Sirf Room ke 💬 se khule Inbox ko isi live Room par wapas laane ka route marker. */
const val EXTRA_RETURN_TO_PARTY_ROOM = "opened_from_party_room"

/**
 * Enter Party ke baad wali **native Party Room** screen.
 *
 * Is batch mein poora room layout hai, magar jaan-boojh kar player engine nahi:
 * Rave jitna full-width 16:9 kala box uski final jagah reserve karta hai. MPV,
 * controls, playback aur sync last player batch mein isi box ke andar aayenge.
 */
class PartyRoomActivity : Activity() {

    /** party-final1.html ke exact page/panel/header/input rang — sirf generic pale tint nahi. */
    private data class Palette(
        val name: String,
        val page: IntArray,
        val accent: IntArray,
        val glowA: Int,
        val glowB: Int,
        val panel: IntArray,
        val head: IntArray,
        val input: IntArray
    )

    private val prefs by lazy { getSharedPreferences("wp_native", Context.MODE_PRIVATE) }
    private val palettes by lazy {
        listOf(
            Palette("Lobby Neon", cols("#050719", "#0b1327", "#050719"),
                cols("#fa35de", "#c03cff", "#36d9fa"), hex("#42721d75"), hex("#3d146183"),
                cols("#e81e1431", "#e80d1d33"), cols("#ed231036", "#ed111b34"),
                cols("#ff2a153c", "#ff132a40")),
            Palette("Night Purple", cols("#080812", "#17132f", "#0a1127"),
                cols("#ff66bd", "#a477ff", "#55baff"), hex("#3dff64c3"), hex("#3d5b7eff"),
                cols("#b81c183b", "#8c080918"), cols("#94060713", "#94060713"),
                cols("#78080818", "#78080818")),
            Palette("Ocean Cyan", cols("#051219", "#082129", "#051219"),
                cols("#358efa", "#3cb7ff", "#36faed"), hex("#421d5575"), hex("#3d14837c"),
                cols("#e8142631", "#e80d3330"), cols("#ed102836", "#ed113432"),
                cols("#ff152e3c", "#ff13403d")),
            Palette("Sunset Rose", cols("#19050c", "#261018", "#19050c"),
                cols("#fa3549", "#ff3c83", "#fa8e36"), hex("#42751d3d"), hex("#3d834614"),
                cols("#e831141f", "#e8331e0d"), cols("#ed36101e", "#ed342111"),
                cols("#ff3c1523", "#ff402713")),
            Palette("Emerald Glow", cols("#05190f", "#0a271d", "#05190f"),
                cols("#35fac5", "#3cff9e", "#36eafa"), hex("#421d7549"), hex("#3d147a83"),
                cols("#e8143123", "#e80d3033"), cols("#ed103623", "#ed113134"),
                cols("#ff153c29", "#ff133c40")),
            Palette("Champagne Gold", cols("#191305", "#28210d", "#191305"),
                cols("#e88f47", "#eabc51", "#e8db48"), hex("#42755b1d"), hex("#3d837a14"),
                cols("#e8312814", "#e833300d"), cols("#ed362b10", "#ed343111"),
                cols("#ff3c3015", "#ff403c13"))
        )
    }
    private var themeIndex = 1
    private val palette: Palette get() = palettes[themeIndex]

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun hex(v: String): Int = Color.parseColor(v)
    private fun cols(vararg v: String): IntArray = v.map(::hex).toIntArray()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        themeIndex = prefs.getInt("theme", 1).coerceIn(palettes.indices)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildRoom())
    }

    private fun buildRoom(): View {
        val root = PartyRoomBackdrop(this, palette.page, palette.glowA, palette.glowB)
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
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, palette.head).apply {
                setStroke(dp(1f), Color.argb(33, 255, 255, 255))
            }
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
                // Yahan CLEAR_TOP bilkul nahi: Room ke upar ek Inbox khulta hai,
                // isliye Android back/gesture naturally isi Room par wapas laata hai.
                // Baqi jagah se Inbox khulne ka route bilkul nahi badalta.
                startActivity(Intent(this@PartyRoomActivity, InboxActivity::class.java)
                    .putExtra(EXTRA_RETURN_TO_PARTY_ROOM, true))
                overridePendingTransition(0, 0)
            }
        }
        bar.addView(chat, lp(dp(34f), dp(34f)).apply { leftMargin = dp(7f) })
        bar.addView(headerButton("🎨").apply {
            contentDescription = "Theme"
            setOnClickListener { openThemePicker() }
        }, lp(dp(34f), dp(34f)).apply { leftMargin = dp(5f) })
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

    /** Header ke 🎨 ka real native picker; choice foran poore room par lagti aur save hoti hai. */
    private fun openThemePicker() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(10f))
        }
        box.addView(TextView(this).apply {
            text = "🎨 Theme chuno"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "Poore Watch Party Room ka rang badalta hai"
            textSize = 11f
            setTextColor(hex("#c4b5fd"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })

        var dialog: AlertDialog? = null
        var i = 0
        while (i < palettes.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) {
                val index = i + j
                if (index < palettes.size) {
                    val p = palettes[index]
                    val selected = index == themeIndex
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(7f), dp(7f), dp(7f), dp(8f))
                        background = roundBox(
                            Color.argb(if (selected) 54 else 20, 255, 255, 255),
                            if (selected) p.accent[1] else Color.argb(42, 255, 255, 255),
                            14f, if (selected) 2f else 1f)
                        addView(View(this@PartyRoomActivity).apply {
                            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                                if (index == 1) cols("#3a3170", "#241f4d") else p.accent).apply {
                                cornerRadius = dp(9f).toFloat()
                            }
                        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(39f)))
                        addView(TextView(this@PartyRoomActivity).apply {
                            text = (if (selected) "✓ " else "") + p.name
                            textSize = 11.5f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                            if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(6f)
                        })
                        setOnClickListener {
                            themeIndex = index
                            prefs.edit().putInt("theme", themeIndex).apply()
                            dialog?.dismiss()
                            setContentView(buildRoom())
                        }
                    }
                    row.addView(card, LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                    })
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
                }
            }
            box.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            i += 2
        }

        val done = TextView(this).apply {
            text = "✓ Theek hai"
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(hex("#22c55e"), hex("#16a34a"))).apply {
                cornerRadius = dp(13f).toFloat()
            }
        }
        box.addView(done, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(45f)).apply {
            setMargins(dp(4f), dp(10f), dp(4f), dp(2f))
        })

        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        val shownDialog = AlertDialog.Builder(this).setView(scroll).create()
        dialog = shownDialog
        done.setOnClickListener { shownDialog.dismiss() }
        shownDialog.show()
        shownDialog.window?.setBackgroundDrawable(roundBox(hex("#f50d0a20"),
            Color.argb(70, 255, 255, 255), 20f, 1f))
        shownDialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
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
            background = themedInput(12f)
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
            background = themedInput(23f)
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
        GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.panel).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1f), Color.argb(35, 255, 255, 255))
        }

    private fun themedInput(radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, palette.input).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1f), Color.argb(38, 255, 255, 255))
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
            cornerRadius = 19f * resources.displayMetrics.density
            setStroke((resources.displayMetrics.density).roundToInt(), Color.argb(41, 255, 255, 255))
        }
        clipToOutline = true
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

/** Selected website theme ka exact dark page base, aurora glows aur subtle 5px texture. */
private class PartyRoomBackdrop(
    ctx: Context,
    private val page: IntArray,
    private val glowA: Int,
    private val glowB: Int
) : FrameLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, page, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * .08f, -h * .02f, w * .76f,
            glowA, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * .66f, p)
        p.shader = RadialGradient(w, 0f, w * .72f,
            glowB, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * .64f, p)
        p.shader = null
        // HTML ka rgba(255,255,255,.05) dot texture: pehle wali double opacity nahi.
        p.color = Color.argb(13, 255, 255, 255)
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

/** Original CSS wpWave: 5 bars bottom se 45%↔100%, har bar 150ms offset. */
private class PartyBarsLogo(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bars = arrayOf(
        floatArrayOf(2.4f, 8f, 13f), floatArrayOf(6.7f, 5f, 16f),
        floatArrayOf(11f, 2.6f, 18.4f), floatArrayOf(15.3f, 6.4f, 14.6f),
        floatArrayOf(19.6f, 9.6f, 11.4f)
    )
    private val colors = intArrayOf(
        Color.parseColor("#54e8ff"), Color.parseColor("#8b72ff"), Color.parseColor("#ff5ebc"),
        Color.parseColor("#8b72ff"), Color.parseColor("#54e8ff"))
    private val delays = floatArrayOf(0f, 150f, 300f, 450f, 600f)
    private var tick = 0f
    private val wave = ValueAnimator.ofFloat(0f, 1800f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            tick = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!wave.isStarted) wave.start()
    }

    override fun onDetachedFromWindow() {
        wave.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val sx = width / 24f; val sy = height / 24f
        for (i in bars.indices) {
            val b = bars[i]
            val phase = (tick + delays[i]) % 1800f
            val linear = if (phase <= 900f) phase / 900f else (1800f - phase) / 900f
            val eased = (1f - Math.cos(linear * Math.PI).toFloat()) / 2f
            val scale = .45f + .55f * eased
            val bottom = b[1] + b[2]
            val top = bottom - b[2] * scale
            p.color = colors[i]
            c.drawRoundRect(RectF(b[0] * sx, top * sy, (b[0] + 2.7f) * sx, bottom * sy),
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
