package app.party.wpnative

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Original website mein DM ek overlay tha: Room kabhi destroy/recreate nahi hota tha.
 * Native activities mein bhi wahi rule rakhne ke liye live Room ka weak route rakha hai.
 */
object PartyRoomRoute {
    private var live: WeakReference<PartyRoomActivity>? = null

    fun attach(room: PartyRoomActivity) { live = WeakReference(room) }
    fun detach(room: PartyRoomActivity) {
        if (live?.get() === room) { live?.clear(); live = null }
    }

    fun returnToLiveRoom(from: Activity): Boolean {
        val room = live?.get() ?: return false
        if (room.isFinishing || room.isDestroyed) return false
        from.startActivity(Intent(from, PartyRoomActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        from.overridePendingTransition(0, 0)
        return true
    }
}

/**
 * Enter Party ke baad wali **native Party Room** screen.
 *
 * Is batch mein poora room layout hai, magar jaan-boojh kar player engine nahi:
 * Rave jitna full-width 16:9 kala box uski final jagah reserve karta hai. MPV,
 * controls, playback aur sync last player batch mein isi box ke andar aayenge.
 */
class PartyRoomActivity : Activity(), ChatHost {

    /** party-final1.html ke exact page/panel/header/input rang — sirf generic pale tint nahi. */
    private data class Palette(
        val name: String,
        val page: IntArray,
        val accent: IntArray,
        val glowA: Int,
        val glowB: Int,
        val glowC: Int,
        val panel: IntArray,
        val head: IntArray,
        val input: IntArray
    )

    private data class BubblePalette(
        val own: IntArray, val other: IntArray, val ownText: Int, val otherText: Int
    )

    private val prefs by lazy { getSharedPreferences("wp_native", Context.MODE_PRIVATE) }
    private val palettes by lazy {
        listOf(
            Palette("Lobby Neon", cols("#050719", "#050719"),
                cols("#fa35de", "#c03cff", "#36d9fa"), hex("#42721d75"), hex("#3d146183"),
                Color.TRANSPARENT, cols("#e81e1431", "#e80d1d33"),
                cols("#ed231036", "#ed111b34"), cols("#ff2a153c", "#ff132a40")),
            Palette("Night Purple", cols("#080812", "#17132f", "#0a1127"),
                cols("#ff66bd", "#a477ff", "#55baff"), hex("#3dff64c3"), hex("#3d5b7eff"),
                hex("#2e6f49d9"), cols("#b81c183b", "#8c080918"),
                cols("#94060713", "#94060713"), cols("#78080818", "#78080818")),
            Palette("Ocean Cyan", cols("#051219", "#051219"),
                cols("#358efa", "#3cb7ff", "#36faed"), hex("#421d5575"), hex("#3d14837c"),
                Color.TRANSPARENT, cols("#e8142631", "#e80d3330"),
                cols("#ed102836", "#ed113432"), cols("#ff152e3c", "#ff13403d")),
            Palette("Sunset Rose", cols("#19050c", "#19050c"),
                cols("#fa3549", "#ff3c83", "#fa8e36"), hex("#42751d3d"), hex("#3d834614"),
                Color.TRANSPARENT, cols("#e831141f", "#e8331e0d"),
                cols("#ed36101e", "#ed342111"), cols("#ff3c1523", "#ff402713")),
            Palette("Emerald Glow", cols("#05190f", "#05190f"),
                cols("#35fac5", "#3cff9e", "#36eafa"), hex("#421d7549"), hex("#3d147a83"),
                Color.TRANSPARENT, cols("#e8143123", "#e80d3033"),
                cols("#ed103623", "#ed113134"), cols("#ff153c29", "#ff133c40")),
            Palette("Champagne Gold", cols("#191305", "#191305"),
                cols("#e88f47", "#eabc51", "#e8db48"), hex("#42755b1d"), hex("#3d837a14"),
                Color.TRANSPARENT, cols("#e8312814", "#e833300d"),
                cols("#ed362b10", "#ed343111"), cols("#ff3c3015", "#ff403c13"))
        )
    }
    private val bubblePalettes by lazy {
        listOf(
            BubblePalette(cols("#cf30bd", "#6b55e4"), cols("#261c40", "#1d2340", "#12253b"), Color.WHITE, hex("#efe8fb")),
            BubblePalette(cols("#f472b6", "#a78bfa"), cols("#51c9c2", "#7182e9", "#aa7ced"), Color.WHITE, Color.WHITE),
            BubblePalette(cols("#fb923c", "#ea580c"), cols("#38bdf8", "#3b82f6", "#1d4ed8"), hex("#3a1a04"), Color.WHITE),
            BubblePalette(cols("#22c55e", "#15803d"), cols("#f472b6", "#e11d48", "#be123c"), hex("#02240f"), Color.WHITE),
            BubblePalette(cols("#facc15", "#eab308"), cols("#a78bfa", "#7c3aed", "#5b21b6"), hex("#3a2d02"), Color.WHITE),
            BubblePalette(cols("#ef4444", "#b91c1c"), cols("#2dd4bf", "#14b8a6", "#0f766e"), Color.WHITE, hex("#04201d")),
            BubblePalette(cols("#a3e635", "#65a30d"), cols("#c084fc", "#8b5cf6", "#6d28d9"), hex("#1a2e02"), Color.WHITE),
            BubblePalette(cols("#0b1220", "#1f2937"), cols("#ffffff", "#e2e8f0", "#cbd5e1"), Color.WHITE, hex("#0b1220")),
            BubblePalette(cols("#3078cf", "#55b0e4"), cols("#1c3340", "#1c3340", "#123b38"), hex("#031724"), hex("#e8f4fb")),
            BubblePalette(cols("#30cfa5", "#55e49d"), cols("#1c402e", "#1c402e", "#12383b"), hex("#041b12"), hex("#e8fbf2")),
            BubblePalette(cols("#ce3758", "#ae275d"), cols("#401c29", "#401c29", "#3b2412"), Color.WHITE, hex("#fbe8ef")),
            BubblePalette(cols("#e6b25b", "#d1bc78"), cols("#40351c", "#40351c", "#3b3812"), hex("#291b07"), hex("#fbf5e8"))
        )
    }
    private val partyBubble: BubblePalette
        get() = bubblePalettes[prefs.getInt("bubble", 1).coerceIn(bubblePalettes.indices)]

    private var themeIndex = 1
    private var appliedThemeIndex = -1
    private lateinit var roomBackdrop: PartyRoomBackdrop
    private val palette: Palette get() = palettes[themeIndex]

    // Party chat filhaal isi live Room ki local UI state hai; network transport baad mein judega.
    private val partyMsgs = mutableListOf<Msg>()
    private var nextPartyMsgId = 1
    private var partyReplyTo: Msg? = null
    private var partyEmojiTarget: Msg? = null
    private lateinit var partyRv: RecyclerView
    private lateinit var partyLm: LinearLayoutManager
    private lateinit var partyAdapter: ChatAdapter
    private lateinit var partyInput: EditText
    private lateinit var partyReplyWrap: FrameLayout
    private lateinit var partyReplyWho: TextView
    private lateinit var partyReplyWhat: TextView
    private lateinit var partyEmojiWrap: FrameLayout
    private lateinit var partyEmojiInput: EditText
    private lateinit var partyFlyLayer: FrameLayout

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    override fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    override fun hex(v: String): Int = Color.parseColor(v)
    private fun cols(vararg v: String): IntArray = v.map(::hex).toIntArray()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        PartyRoomRoute.attach(this)
        themeIndex = prefs.getInt("theme", 1).coerceIn(palettes.indices)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyWindowBase()
        setContentView(buildRoom())
    }

    /**
     * Smart Music website mein DM close hote hi wahi #app dobara paint hota tha.
     * Native mein Activity surface cover/uncover hoti hai, isliye resume par saved
     * theme dobara verify karke background render-node ko explicitly zinda karte hain.
     */
    override fun onResume() {
        super.onResume()
        val saved = prefs.getInt("theme", 1).coerceIn(palettes.indices)
        if (saved != appliedThemeIndex) {
            themeIndex = saved
            applyWindowBase()
            setContentView(buildRoom())
        } else {
            applyWindowBase()
        }
        window.decorView.animate().cancel()
        window.decorView.alpha = 1f
        if (::roomBackdrop.isInitialized) roomBackdrop.restoreColors()
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        if (newIntent != null) setIntent(newIntent)
        window.decorView.alpha = 1f
        if (::roomBackdrop.isInitialized) roomBackdrop.restoreColors()
    }

    override fun onDestroy() {
        PartyRoomRoute.detach(this)
        super.onDestroy()
    }

    private fun applyWindowBase() {
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
            palette.page.copyOf()))
    }

    private fun buildRoom(): View {
        val root = PartyRoomBackdrop(this, palette.page.copyOf(), palette.glowA, palette.glowB,
            palette.glowC, themeIndex == 1)
        roomBackdrop = root
        appliedThemeIndex = themeIndex
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
        // Reactions DM ki tarah poore Room ke upar udti hain, touches neeche pass hote hain.
        partyFlyLayer = FrameLayout(this).apply { isClickable = false; isFocusable = false }
        root.addView(partyFlyLayer, FrameLayout.LayoutParams(
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
                startActivity(Intent(this@PartyRoomActivity, InboxActivity::class.java))
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
                            applyWindowBase()
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

        chat.addView(TextView(this).apply {
            text = "💬 Live Chat"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#d4caff"))
            setPadding(dp(10f), dp(5f), dp(10f), dp(3f))
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(25f)))

        // DM wala exact recyclable bubble engine: swipe reply, long-press react, chips.
        partyLm = LinearLayoutManager(this).apply { stackFromEnd = true }
        partyAdapter = ChatAdapter(this)
        partyRv = RecyclerView(this).apply {
            layoutManager = partyLm
            adapter = partyAdapter
            setPadding(dp(8), dp(3), dp(8), dp(2))
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
        }
        chat.addView(partyRv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buildPartyReplyBar()
        chat.addView(partyReplyWrap,
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildPartyEmojiBar()
        chat.addView(partyEmojiWrap,
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chat.addView(buildComposer(),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        renderPartyThread()
        partyReplyTo?.let { showPartyReply(it, openKeyboard = false) }
        return chat
    }

    /**
     * Original room composer: emoji row typing box ke upar. GIF jaan-boojh kar hata
     * diya; photo/mic bilkul DM ke WpIcon + gradient dimensions mein hain.
     */
    private fun buildComposer(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
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
                contentDescription = "Message mein $e lagao"
                setOnClickListener { appendPartyEmoji(e) }
            }, lp(dp(35f), dp(36f)))
        }
        // GIF nahi: baad mein keyboard/Gboard se direct send setup hoga.
        emojis.addView(partyMediaIcon("photo", intArrayOf(
            hex("#f59e0b"), hex("#ec4899"), hex("#8b5cf6"))),
            lp(dp(36f), dp(36f)).apply { leftMargin = dp(4f) })
        emojis.addView(partyMediaIcon("mic", intArrayOf(
            hex("#06b6d4"), hex("#3b82f6"), hex("#8b5cf6"))),
            lp(dp(36f), dp(36f)).apply { leftMargin = dp(5f) })
        sc.addView(emojis, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38f)))
        box.addView(sc, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(39f)))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        partyInput = EditText(this).apply {
            hint = "Message likho..."
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            filters = arrayOf(InputFilter.LengthFilter(500))
            setPadding(dp(13f), 0, dp(13f), 0)
            background = themedInput(23f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendPartyMessage(); true
                } else false
            }
        }
        inputRow.addView(partyInput, LinearLayout.LayoutParams(0, dp(43f), 1f))
        inputRow.addView(TextView(this).apply {
            text = "➤"
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.accent).apply {
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { sendPartyMessage() }
        }, lp(dp(43f), dp(43f)).apply { leftMargin = dp(7f) })
        box.addView(inputRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(43f)).apply { topMargin = dp(4f) })
        return box
    }

    /** DM ka exact 36dp rounded-square SVG icon; media sending network batch mein judegi. */
    private fun partyMediaIcon(kind: String, colors: IntArray): FrameLayout = FrameLayout(this).apply {
        contentDescription = if (kind == "photo") "Photo" else "Voice message"
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(12f).toFloat()
        }
        addView(WpIcon(this@PartyRoomActivity, kind),
            FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER))
    }

    private fun appendPartyEmoji(emoji: String) {
        partyInput.append(emoji)
        partyInput.requestFocus()
        partyInput.setSelection(partyInput.text.length)
        partyInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(partyInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendPartyMessage() {
        val text = partyInput.text.toString().trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val m = Msg(
            id = nextPartyMsgId++, text = text, own = true,
            time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)),
            day = "", read = true, ts = now
        )
        partyReplyTo?.let {
            m.replyName = if (it.own) "You" else peerName()
            m.replyText = partyMessageLabel(it)
            clearPartyReply()
        }
        partyMsgs.add(m)
        // Same standing rule: phone/RAM mein 120 se zyada nahi. Network trim transport ke sath judega.
        while (partyMsgs.size > FirebaseChat.MSG_KEEP) partyMsgs.removeAt(0)
        partyInput.setText("")
        partyAdapter.entryAnimId = m.id
        renderPartyThread()
        scrollPartyBottom()
    }

    private fun renderPartyThread() {
        if (!::partyAdapter.isInitialized) return
        val rows = partyMsgs.map { m ->
            Row(Row.MSG, "party:${m.id}", partySig(m), m, null)
        }
        partyAdapter.submit(rows)
    }

    private fun partySig(m: Msg): String =
        m.text + "|" + m.replyName + "|" + m.replyText + "|" + m.time + "|" +
            m.rx.entries.joinToString(",") { "${it.key}:${it.value}" }

    private fun scrollPartyBottom() {
        partyRv.post {
            if (partyAdapter.itemCount > 0) partyRv.scrollToPosition(partyAdapter.itemCount - 1)
        }
    }

    private fun buildPartyReplyBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundBox(Color.argb(23, 255, 255, 255),
                Color.argb(36, 255, 255, 255), 12, 1)
        }
        val line = View(this).apply { setBackgroundColor(hex("#d8b4fe")) }
        bar.addView(line, lp(dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
        val replyTextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        partyReplyWho = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#d8b4fe"))
            setSingleLine(true)
        }
        partyReplyWhat = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.argb(190, 255, 255, 255))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        replyTextCol.addView(partyReplyWho)
        replyTextCol.addView(partyReplyWhat)
        bar.addView(replyTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })
        bar.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { clearPartyReply() }
        }, lp(dp(40), dp(36)))
        partyReplyWrap = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), 0)
            addView(bar)
        }
    }

    private fun showPartyReply(m: Msg, openKeyboard: Boolean = true) {
        partyReplyTo = m
        partyReplyWho.text = "Replying to " + if (m.own) "You" else peerName()
        partyReplyWhat.text = partyMessageLabel(m)
        partyReplyWrap.visibility = View.VISIBLE
        if (openKeyboard) {
            partyInput.requestFocus()
            partyInput.setSelection(partyInput.text.length)
            partyInput.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(partyInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun clearPartyReply() {
        partyReplyTo = null
        if (::partyReplyWrap.isInitialized) partyReplyWrap.visibility = View.GONE
    }

    private fun partyMessageLabel(m: Msg): String = when (m.type) {
        "photo" -> "🖼️ Photo"
        "voice" -> "🎙️ Voice note"
        else -> m.text
    }

    /** DM wala ➕ custom-reaction keyboard. */
    private fun buildPartyEmojiBar() {
        partyEmojiInput = EditText(this).apply {
            hint = "Keyboard se apni marzi ka emoji chuno..."
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(8))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundBox(Color.argb(20, 255, 255, 255),
                Color.argb(46, 255, 255, 255), 10, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val emoji = firstPartyEmoji(s?.toString().orEmpty())
                    if (emoji.isEmpty()) return
                    val target = partyEmojiTarget
                    closePartyEmojiBox()
                    if (target != null) togglePartyReaction(target, emoji)
                }
            })
        }
        partyEmojiWrap = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), 0)
            addView(LinearLayout(this@PartyRoomActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = roundBox(Color.argb(250, 13, 10, 32),
                    Color.argb(41, 255, 255, 255), 14, 1)
                addView(partyEmojiInput, LinearLayout.LayoutParams(0, dp(43), 1f))
                addView(TextView(this@PartyRoomActivity).apply {
                    text = "✕"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundBox(Color.argb(31, 255, 255, 255),
                        Color.TRANSPARENT, 10, 0)
                    setOnClickListener { closePartyEmojiBox() }
                }, lp(dp(42), dp(42)).apply { leftMargin = dp(8) })
            })
        }
    }

    private fun openPartyEmojiBox(m: Msg) {
        partyEmojiTarget = m
        partyEmojiWrap.visibility = View.VISIBLE
        partyEmojiInput.setText("")
        partyEmojiInput.requestFocus()
        partyEmojiInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(partyEmojiInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closePartyEmojiBox() {
        partyEmojiTarget = null
        if (::partyEmojiWrap.isInitialized) partyEmojiWrap.visibility = View.GONE
        if (::partyEmojiInput.isInitialized) {
            partyEmojiInput.setText("")
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(partyEmojiInput.windowToken, 0)
        }
    }

    private fun firstPartyEmoji(value: String): String {
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
                cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF || cp == 0x2764
            if (emoji) {
                var j = i + Character.charCount(cp)
                while (j < value.length) {
                    val next = value.codePointAt(j)
                    if (next == 0xFE0F || next == 0x200D || next in 0x1F3FB..0x1F3FF ||
                        next in 0x1F000..0x1FAFF) j += Character.charCount(next) else break
                }
                return value.substring(i, j)
            }
            i += Character.charCount(cp)
        }
        return ""
    }

    /** Bubble long-press: exact DM quick reactions + custom emoji + reply/copy/delete. */
    private fun showPartyMessageActions(m: Msg) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val radius = dp(22).toFloat()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
            background = GradientDrawable().apply {
                setColor(hex("#1b1433"))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("❤️", "😂", "😮", "😢", "👍", "🔥").forEach { emoji ->
            quick.addView(TextView(this).apply {
                text = emoji
                textSize = 20f
                gravity = Gravity.CENTER
                includeFontPadding = true
                setOnClickListener { togglePartyReaction(m, emoji); dialog.dismiss() }
            }, lp(dp(36), dp(36)).apply { rightMargin = dp(2) })
        }
        quick.addView(TextView(this).apply {
            text = "➕"
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(140, 255, 255, 255), dp(3).toFloat(), dp(3).toFloat())
            }
            setOnClickListener {
                dialog.dismiss()
                window.decorView.postDelayed({ openPartyEmojiBox(m) }, 150L)
            }
        }, lp(dp(36), dp(36)))
        sheet.addView(quick, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(partyActionButton("↩ Reply") {
            dialog.dismiss(); showPartyReply(m)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(5)
        })
        actions.addView(partyActionButton("📋 Copy") {
            dialog.dismiss()
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("message", m.text))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(5)
        })
        actions.addView(partyActionButton("🗑 Delete") {
            dialog.dismiss()
            confirmThen(this@PartyRoomActivity, "Message delete karein?",
                "Ye message Party chat se hat jayega.") {
                partyMsgs.removeAll { it.id == m.id }
                if (partyReplyTo?.id == m.id) clearPartyReply()
                renderPartyThread()
            }
        })
        sheet.addView(actions, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })

        dialog.setContentView(sheet)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun partyActionButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = roundBox(Color.argb(23, 255, 255, 255), Color.TRANSPARENT, 9, 0)
        setOnClickListener { action() }
    }

    private fun togglePartyReaction(m: Msg, emoji: String) {
        val adding = m.rx[emoji] != true
        if (adding) m.rx[emoji] = true else m.rx.remove(emoji)
        renderPartyThread()
        if (adding) flyPartyReaction(emoji)
    }

    /** DM ka Instagram-style 3-second flying reaction. */
    private fun flyPartyReaction(emoji: String) {
        if (!::partyFlyLayer.isInitialized) return
        val view = TextView(this).apply {
            text = emoji
            textSize = 30f
            alpha = 0f
        }
        partyFlyLayer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(96) })
        val height = if (partyFlyLayer.height > 0) partyFlyLayer.height else dp(420)
        val rise = (height * (.5f + Math.random().toFloat() * .3f)).coerceAtLeast(dp(180).toFloat())
        val drift = (if (Math.random() < .5) -1 else 1) * dp(18 + (Math.random() * 34).toInt())
        val spin = (if (Math.random() < .5) -1 else 1) * (8f + Math.random().toFloat() * 16f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedFraction
                view.translationY = -rise * t
                view.translationX = (drift * Math.sin(t.toDouble() * Math.PI * 1.6)).toFloat()
                view.rotation = spin * t
                val pop = min(1f, t * 7f)
                view.scaleX = .5f + .9f * pop
                view.scaleY = view.scaleX
                view.alpha = when {
                    t < .07f -> t / .07f
                    t > .62f -> (1f - (t - .62f) / .38f).coerceIn(0f, 1f)
                    else -> 1f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    partyFlyLayer.removeView(view)
                }
            })
            start()
        }
    }

    // ChatAdapter ke liye Party Room host. Bubble gestures/design DM ke exact engine se.
    override fun ctx(): Context = this
    override fun peerName(): String = "Party"
    override fun meName(): String = WpUser.me(this)
    override fun peerColorInt(): Int = palette.accent[2]
    override fun mineBubbleBg(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, partyBubble.own.copyOf()).apply {
        cornerRadius = dp(22).toFloat()
    }
    override fun peerBubbleBg(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, partyBubble.other.copyOf()).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.argb(66, 255, 255, 255))
    }
    override fun mineBubbleText(): Int = partyBubble.ownText
    override fun peerBubbleText(): Int = partyBubble.otherText
    override fun tick(m: Msg): CharSequence {
        val value = if (m.own) "${m.time} ✓✓" else m.time
        return android.text.SpannableString(value).apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.argb(155, 255, 255, 255)),
                0, value.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    override fun bubbleMaxWidth(): Int =
        maxOf(dp(120), (resources.displayMetrics.widthPixels * .85f).toInt() - dp(70))
    override fun onSwipeReply(m: Msg) = showPartyReply(m)
    override fun onBubbleLongPress(m: Msg) = showPartyMessageActions(m)
    override fun onChipClick(m: Msg, emoji: String) = togglePartyReaction(m, emoji)

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

    override fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable =
        roundBox(fill, stroke, radiusDp.toFloat(), strokeDp.toFloat())

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

/**
 * Selected website theme ka exact dark page base, aurora glows aur subtle 5px texture.
 *
 * Base ab normal Android Drawable hai (surface resume par kabhi blank nahi hota), aur
 * aurora [dispatchDraw] mein har child-frame ke sath dobara composite hoti hai. Pehle
 * sirf FrameLayout.onDraw display-list par thi; DM Activity se wapas aate waqt kuch
 * devices us cached layer ko bina aurora ke restore kar rahe the — wahi "rang urrna" tha.
 */
private class PartyRoomBackdrop(
    ctx: Context,
    private val page: IntArray,
    private val glowA: Int,
    private val glowB: Int,
    private val glowC: Int,
    private val purpleLayout: Boolean
) : FrameLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, page.copyOf())
        setWillNotDraw(true)
    }

    fun restoreColors() {
        animate().cancel()
        alpha = 1f
        visibility = View.VISIBLE
        background?.alpha = 255
        background?.invalidateSelf()
        invalidate()
        post {
            background?.invalidateSelf()
            invalidate()
            (parent as? View)?.invalidate()
        }
    }

    override fun dispatchDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w > 0f && h > 0f) {
            if (purpleLayout) {
                // Original default: pink top-left + blue top-right + purple bottom.
                radial(c, w * .08f, -h * .10f, w * .80f, glowA, w, h)
                radial(c, w, 0f, w * .76f, glowB, w, h)
                radial(c, w * .50f, h * 1.15f, w * .90f, glowC, w, h)
            } else {
                // Original four/neon themes: first glow top-right, second bottom-left.
                radial(c, w, 0f, w * .95f, glowA, w, h)
                radial(c, 0f, h, w * .95f, glowB, w, h)
            }

            p.shader = null
            // CSS: dot alpha .18 × body opacity .28 ≈ .05.
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
        super.dispatchDraw(c)
    }

    private fun radial(c: Canvas, x: Float, y: Float, radius: Float, color: Int, w: Float, h: Float) {
        if (Color.alpha(color) == 0 || radius <= 0f) return
        p.shader = RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
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
