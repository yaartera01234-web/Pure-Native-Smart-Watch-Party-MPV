package app.party.wpnative

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
class PartyRoomActivity : Activity(), ChatHost, PartyTowerListener {

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
    private val keyboardCollapseViews = mutableListOf<View>()
    private var roomKeyboardOpen = false

    // Selected MQTT tower + real Room members/playlist/media UI.
    private lateinit var partyOnlineText: TextView
    private lateinit var partyMembersTitle: TextView
    private lateinit var partyMembersList: LinearLayout
    private lateinit var sourceRowView: View
    private lateinit var sourceInput: EditText
    private lateinit var playlistCount: TextView
    private lateinit var playlistHint: TextView
    private lateinit var playlistArrow: TextView
    private lateinit var playlistListWrap: ScrollView
    private lateinit var playlistList: LinearLayout
    private val partyQueue = mutableListOf<PartyQueueItem>()
    private var partyQueueIndex = -1
    private var playlistOpen = false
    private var partyTowerUp = false
    private var partyMemberCount = 1
    private lateinit var partyComposerRow: LinearLayout
    private lateinit var partyRecBar: LinearLayout
    private lateinit var partyRecTime: TextView
    private var leavingParty = false
    private val REQ_PARTY_PHOTO = 177
    private val REQ_PARTY_MIC = 178

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

        // Party Bar sirf Lobby tak laati hai; actual Tower join isi Activity ko Lobby ke
        // Enter Party se kholne ke baad hota hai, saved first-page name/room/tower par.
        val room = prefs.getString("room", "")?.trim().orEmpty()
        if (room.isBlank()) {
            Toast.makeText(this, "Pehle first page par Room name save karo", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        MediaCache.deletePrefix(this, "party_")
        PartyTower.enter(this, room, WpUser.me(this), prefs.getInt("tower", 0), this)
    }

    /**
     * Smart Music website mein DM close hote hi wahi #app dobara paint hota tha.
     * Native mein Activity surface cover/uncover hoti hai, isliye resume par saved
     * theme dobara verify karke background render-node ko explicitly zinda karte hain.
     */
    override fun onResume() {
        super.onResume()
        PartyTower.attach(this)
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
        PartyTower.detach(this)
        VoiceRec.abort()
        PartyRoomRoute.detach(this)
        super.onDestroy()
    }

    @Deprecated("Android back callback compatibility")
    override fun onBackPressed() {
        if (leavingParty) return
        AlertDialog.Builder(this)
            .setTitle("🚪 Party se Left?")
            .setMessage("Aapki Room chat is phone se foran clear hogi. Baqi members ki chat aur saved playlist rahegi.")
            .setNegativeButton("Nahi", null)
            .setPositiveButton("Left") { _, _ -> leavePartyNow() }
            .show()
    }

    private fun leavePartyNow() {
        if (leavingParty) return
        leavingParty = true
        VoicePlay.stop()
        VoiceRec.abort()
        partyMsgs.forEach { if (it.mediaKey.startsWith("party_")) MediaCache.delete(this, it.mediaKey) }
        partyMsgs.clear()
        if (::partyAdapter.isInitialized) renderPartyThread()
        PartyTower.leave {
            MediaCache.deletePrefix(this, "party_")
            if (!isFinishing) {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun applyWindowBase() {
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
            palette.page.copyOf()))
    }

    private fun buildRoom(): View {
        keyboardCollapseViews.clear()
        roomKeyboardOpen = false
        val root = PartyRoomBackdrop(this, palette.page.copyOf(), palette.glowA, palette.glowB,
            palette.glowC, themeIndex == 1)
        roomBackdrop = root
        appliedThemeIndex = themeIndex
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Keyboard khule to fixed player blocks chupte hain, warna composer screen ke neeche kat jata hai.
        sourceRowView = buildSourceRow()
        col.addView(sourceRowView, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(51f)).apply {
            setMargins(dp(8f), dp(8f), dp(8f), 0)
        })

        // RAVE SIZE: poori screen width × 9/16. Filhaal sirf reserved native box.
        val playerSlot = RavePlayerSlot(this).also(keyboardCollapseViews::add)
        col.addView(playerSlot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7f)
        })

        val playlist = buildPlaylist().also(keyboardCollapseViews::add)
        col.addView(playlist, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
        installKeyboardSafeRoom(root)
        return root
    }

    /**
     * Android 15 edge-to-edge mein sirf adjustResize kaafi nahi: keyboard poore Room ke
     * fixed player ke upar aa kar composer ko neeche chhor deta hai. API 30+ par insets
     * hum khud lagate hain; purane Android par adjustResize + visible-frame fallback.
     */
    private fun installKeyboardSafeRoom(root: View) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                val keyboard = insets.isVisible(android.view.WindowInsets.Type.ime()) && ime.bottom > 0
                val bottom = if (keyboard) maxOf(bars.bottom, ime.bottom) else bars.bottom
                if (view.paddingLeft != bars.left || view.paddingTop != bars.top ||
                    view.paddingRight != bars.right || view.paddingBottom != bottom) {
                    view.setPadding(bars.left, bars.top, bars.right, bottom)
                }
                setRoomKeyboardMode(keyboard)
                insets
            }
            root.requestApplyInsets()
        } else {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val visible = android.graphics.Rect()
            root.viewTreeObserver.addOnGlobalLayoutListener {
                root.getWindowVisibleDisplayFrame(visible)
                val fullHeight = root.rootView.height
                val obscured = fullHeight - visible.bottom
                setRoomKeyboardMode(obscured > fullHeight * .18f)
            }
        }
    }

    /** Typing mode mein header/chat rehte hain; player/member blocks temporary collapse hote hain. */
    private fun setRoomKeyboardMode(open: Boolean) {
        val changed = roomKeyboardOpen != open
        roomKeyboardOpen = open
        if (changed) keyboardCollapseViews.forEach { it.visibility = if (open) View.GONE else View.VISIBLE }
        if (::sourceRowView.isInitialized) {
            sourceRowView.visibility = if (open && ::sourceInput.isInitialized && !sourceInput.hasFocus()) View.GONE else View.VISIBLE
        }
        if (changed && open && ::partyRv.isInitialized) partyRv.post { scrollPartyBottom() }
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

        partyOnlineText = TextView(this).apply {
            text = "📻 Tower…"
            textSize = 10.5f
            setTextColor(hex("#fcd34d"))
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = roundBox(Color.argb(33, 74, 222, 128), Color.argb(32, 255, 255, 255), 20f, 1f)
        }
        bar.addView(partyOnlineText,
            lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        bar.addView(headerButton("🚪").apply {
            contentDescription = "Leave Party"
            setOnClickListener { onBackPressed() }
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
                            PartyTower.attach(this@PartyRoomActivity)
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
        sourceInput = EditText(this).apply {
            hint = "YouTube / MP4 / MP3 link..."
            setHintTextColor(hex("#88869b"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12f), 0, dp(9f), 0)
            background = themedInput(12f)
            setOnFocusChangeListener { _, _ -> if (roomKeyboardOpen) setRoomKeyboardMode(true) }
        }
        row.addView(sourceInput, LinearLayout.LayoutParams(0, dp(44f), 1f))

        row.addView(sourceButton("▶ Play", intArrayOf(hex("#1AD07A"), hex("#0ABF6A"))) {
            playerLater()
        }, lp(dp(59f), dp(44f)).apply { leftMargin = dp(6f) })
        row.addView(sourceButton("＋", intArrayOf(hex("#FF5AA8"), hex("#B46BFF"))) {
            addSourceToPlaylist()
        }, lp(dp(43f), dp(44f)).apply { leftMargin = dp(6f) })
        row.addView(searchButton(), lp(dp(43f), dp(44f)).apply { leftMargin = dp(6f) })
        return row
    }

    private fun sourceButton(label: String, colors: IntArray, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (label.length > 2) 11.5f else 20f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(12f).toFloat()
        }
        setOnClickListener { action() }
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBox(15f)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            setOnClickListener {
                playlistOpen = !playlistOpen
                renderPartyPlaylist()
            }
        }
        playlistCount = TextView(this).apply {
            text = "📋  Playlist (0)"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#d4caff"))
        }
        head.addView(playlistCount)
        playlistHint = TextView(this).apply {
            text = "Tap to expand"
            textSize = 10f
            setTextColor(Color.argb(205, 212, 202, 255))
            gravity = Gravity.CENTER
        }
        head.addView(playlistHint, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
        playlistArrow = TextView(this).apply {
            text = "▼"
            textSize = 12f
            setTextColor(hex("#c4b5fd"))
        }
        head.addView(playlistArrow)
        box.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))

        playlistList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }
        playlistListWrap = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = if (playlistOpen) View.VISIBLE else View.GONE
            addView(playlistList, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(playlistListWrap, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(180f)))
        renderPartyPlaylist()
        return box
    }

    private fun addSourceToPlaylist() {
        val raw = sourceInput.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Pehle link paste karo", Toast.LENGTH_SHORT).show(); return
        }
        if (!PartyTower.isConnected()) {
            Toast.makeText(this, "Tower connect hone do", Toast.LENGTH_SHORT).show(); return
        }
        val videoId = youtubeId(raw)
        val type = when {
            videoId.isNotBlank() -> "youtube"
            raw.substringBefore('?').endsWith(".mp3", true) -> "mp3"
            else -> "mp4"
        }
        val item = PartyQueueItem(
            id = "q" + System.currentTimeMillis().toString(36),
            type = type,
            url = if (type == "youtube") "" else raw,
            videoId = videoId,
            label = if (videoId.isNotBlank()) "YouTube · $videoId" else raw.substringAfterLast('/').ifBlank { raw },
            by = WpUser.me(this)
        )
        partyQueue.add(item)
        PartyTower.publishQueue(partyQueue, partyQueueIndex)
        sourceInput.setText("")
        playlistOpen = true
        renderPartyPlaylist()
        if (videoId.isNotBlank()) PartyPlaylistMedia.title(videoId) { title ->
            val live = partyQueue.firstOrNull { it.id == item.id } ?: return@title
            if (live.title.isBlank()) {
                live.title = title; live.label = title
                PartyTower.publishQueue(partyQueue, partyQueueIndex)
                renderPartyPlaylist()
            }
        }
    }

    private fun youtubeId(raw: String): String {
        return try {
            val u = Uri.parse(raw)
            when {
                u.host?.contains("youtu.be", true) == true -> u.pathSegments.firstOrNull().orEmpty()
                u.host?.contains("youtube.com", true) == true && u.pathSegments.firstOrNull() == "shorts" ->
                    u.pathSegments.getOrNull(1).orEmpty()
                u.host?.contains("youtube.com", true) == true -> u.getQueryParameter("v").orEmpty()
                else -> ""
            }.take(20)
        } catch (_: Throwable) { "" }
    }

    private fun renderPartyPlaylist() {
        if (!::playlistList.isInitialized) return
        playlistCount.text = "📋  Playlist (${partyQueue.size})"
        playlistHint.text = if (playlistOpen) "Tap to collapse" else "Tap to expand"
        playlistArrow.text = if (playlistOpen) "▲" else "▼"
        playlistListWrap.visibility = if (playlistOpen) View.VISIBLE else View.GONE
        playlistList.removeAllViews()
        if (partyQueue.isEmpty()) {
            playlistList.addView(TextView(this).apply {
                text = "Koi song queue mein nahi — link paste karke ＋ dabao 🎶"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(180, 212, 202, 255))
                setPadding(dp(8), dp(18), dp(8), dp(18))
            }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        partyQueue.forEachIndexed { index, item ->
            playlistList.addView(buildPlaylistItem(item, index),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        }
    }

    private fun buildPlaylistItem(item: PartyQueueItem, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(4), dp(5))
            background = roundBox(
                if (index == partyQueueIndex) Color.argb(48, 167, 139, 250) else Color.argb(17, 255, 255, 255),
                Color.argb(30, 255, 255, 255), 10, 1)
            setOnClickListener { playerLater() }
        }
        if (item.type == "youtube" && item.videoId.isNotBlank()) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundBox(hex("#1a1a2e"), Color.argb(35, 255, 255, 255), 8, 1)
                clipToOutline = true
            }
            row.addView(thumb, lp(dp(56), dp(36)).apply { rightMargin = dp(7) })
            PartyPlaylistMedia.thumbnail(this, item.videoId, thumb)
            if (item.title.isBlank()) PartyPlaylistMedia.title(item.videoId) { title ->
                val live = partyQueue.firstOrNull { it.id == item.id } ?: return@title
                if (live.title.isBlank()) {
                    live.title = title; live.label = title
                    PartyTower.publishQueue(partyQueue, partyQueueIndex)
                    renderPartyPlaylist()
                }
            }
        } else {
            row.addView(TextView(this).apply {
                text = if (item.type == "mp3") "🎵" else "🎞️"
                textSize = 19f; gravity = Gravity.CENTER
                background = roundBox(hex("#1a1a2e"), Color.argb(35, 255, 255, 255), 8, 1)
            }, lp(dp(56), dp(36)).apply { rightMargin = dp(7) })
        }

        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(TextView(this).apply {
            text = (if (index == partyQueueIndex) "▶ " else "${index + 1}. ") +
                item.name.ifBlank { item.originalName() }
            textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (item.name.isNotBlank()) names.addView(TextView(this).apply {
            text = item.originalName(); textSize = 9.5f
            setTextColor(Color.argb(170, 212, 202, 255)); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(names, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "☰"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(hex("#d8b4fe"))
            contentDescription = "Playlist item rename"
            setOnClickListener { renamePlaylistItem(item.id) }
        }, lp(dp(34), dp(38)))
        row.addView(TextView(this).apply {
            text = "✕"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(hex("#fda4af"))
            contentDescription = "Playlist item remove"
            setOnClickListener { removePlaylistItem(item.id) }
        }, lp(dp(32), dp(38)))
        return row
    }

    private fun renamePlaylistItem(id: String) {
        val item = partyQueue.firstOrNull { it.id == id } ?: return
        val input = EditText(this).apply {
            hint = "Naya naam likho…"; setText(item.name); setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(60)); setSelectAllOnFocus(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("✏️ Naam badlo")
            .setMessage("Asli: ${item.originalName()}")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Asli naam") { _, _ ->
                item.name = ""; PartyTower.publishQueue(partyQueue, partyQueueIndex); renderPartyPlaylist()
            }
            .setPositiveButton("Save") { _, _ ->
                item.name = input.text.toString().trim().replace(Regex("\\s+"), " ").take(60)
                PartyTower.publishQueue(partyQueue, partyQueueIndex); renderPartyPlaylist()
            }.create()
        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
    }

    private fun removePlaylistItem(id: String) {
        val index = partyQueue.indexOfFirst { it.id == id }
        if (index < 0) return
        partyQueue.removeAt(index)
        if (index <= partyQueueIndex) partyQueueIndex--
        PartyTower.publishQueue(partyQueue, partyQueueIndex)
        renderPartyPlaylist()
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
        partyMembersTitle = TextView(this).apply {
            text = "👥 Party Members (1)"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#d4caff"))
        }
        members.addView(partyMembersTitle)
        partyMembersList = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        members.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(partyMembersList, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(25f)))
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(28f)).apply { topMargin = dp(3f) })
        renderPartyMembers(emptyList())
        keyboardCollapseViews.add(members)
        chat.addView(members, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(59f)))

        // “Live Chat” ki alag 25dp row hata di — ab yahi jagah messages ko milti hai.

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
                textSize = 22f
                gravity = Gravity.CENTER
                alpha = 1f
                elevation = dp(2f).toFloat()
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setShadowLayer(dp(4f).toFloat(), 0f, dp(1f).toFloat(), Color.argb(210, 255, 255, 255))
                background = roundBox(Color.argb(30, 255, 255, 255),
                    Color.argb(34, 255, 255, 255), 10f, 1f)
                contentDescription = "Message mein $e lagao"
                setOnClickListener { appendPartyEmoji(e) }
            }, lp(dp(37f), dp(36f)).apply { rightMargin = dp(2f) })
        }
        // GIF nahi: baad mein keyboard/Gboard se direct send setup hoga.
        emojis.addView(partyMediaIcon("photo", intArrayOf(
            hex("#f59e0b"), hex("#ec4899"), hex("#8b5cf6"))) { openPartyPhotoPicker() },
            lp(dp(36f), dp(36f)).apply { leftMargin = dp(4f) })
        emojis.addView(partyMediaIcon("mic", intArrayOf(
            hex("#06b6d4"), hex("#3b82f6"), hex("#8b5cf6"))) { startPartyVoice() },
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
            setOnFocusChangeListener { _, _ -> if (roomKeyboardOpen) setRoomKeyboardMode(true) }
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
        partyComposerRow = inputRow
        partyRecBar = buildPartyRecBar()
        box.addView(partyRecBar,
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
        box.addView(inputRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(43f)).apply { topMargin = dp(4f) })
        return box
    }

    /** DM ka exact 36dp rounded-square SVG icon; Party mein ab selected tower se kaam karta hai. */
    private fun partyMediaIcon(kind: String, colors: IntArray, action: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription = if (kind == "photo") "Photo" else "Voice message"
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(12f).toFloat()
        }
        addView(WpIcon(this@PartyRoomActivity, kind),
            FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER))
        setOnClickListener { action() }
    }

    private fun buildPartyRecBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = roundBox(Color.argb(31, 255, 255, 255), Color.TRANSPARENT, 11, 0)
        addView(View(this@PartyRoomActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(hex("#ff4d6d")) }
        }, lp(dp(9), dp(9)))
        partyRecTime = TextView(this@PartyRoomActivity).apply {
            text = "0:00 / 1:00"; textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
        }
        addView(partyRecTime, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        addView(TextView(this@PartyRoomActivity).apply {
            text = "✕"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = roundBox(Color.argb(41, 255, 255, 255), Color.TRANSPARENT, 9, 0)
            setOnClickListener { stopPartyVoice(false) }
        }, lp(dp(44), dp(28)).apply { rightMargin = dp(6) })
        addView(TextView(this@PartyRoomActivity).apply {
            text = "➤"; gravity = Gravity.CENTER; setTextColor(hex("#150c26"))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#ff5ebc"), hex("#8b72ff"))).apply { cornerRadius = dp(9).toFloat() }
            setOnClickListener { stopPartyVoice(true) }
        }, lp(dp(44), dp(28)))
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

    private fun openPartyPhotoPicker() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, "Party photo chuno"), REQ_PARTY_PHOTO)
        } catch (_: Throwable) {
            Toast.makeText(this, "Gallery nahi khul saki", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PARTY_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Toast.makeText(this, "Photo tower ke liye taiyar ho rahi hai…", Toast.LENGTH_SHORT).show()
        Thread {
            val bytes = MediaCache.compress(this, uri, 300 * 1024)
            runOnUiThread {
                if (bytes == null) Toast.makeText(this, "Photo 300KB ke andar compress nahi hui", Toast.LENGTH_SHORT).show()
                else sendPartyMedia("photo", bytes, 0, "")
            }
        }.start()
    }

    private fun startPartyVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PARTY_MIC); return
        }
        if (VoiceRec.recording()) { stopPartyVoice(true); return }
        val ok = VoiceRec.start(this) { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        if (!ok) return
        VoiceRec.onSec = { sec ->
            if (::partyRecTime.isInitialized) partyRecTime.text =
                "${sec / 60}:${String.format("%02d", sec % 60)} / 1:00"
        }
        VoiceRec.onLimit = { stopPartyVoice(true) }
        showPartyRecBar(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PARTY_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startPartyVoice()
        else Toast.makeText(this, "Mic ki ijazat ke baghair voice nahi bhej sakte", Toast.LENGTH_SHORT).show()
    }

    private fun showPartyRecBar(show: Boolean) {
        if (!::partyRecBar.isInitialized) return
        partyRecBar.visibility = if (show) View.VISIBLE else View.GONE
        partyComposerRow.visibility = if (show) View.GONE else View.VISIBLE
        if (show) partyRecTime.text = "0:00 / 1:00"
    }

    private fun stopPartyVoice(send: Boolean) {
        if (!VoiceRec.recording()) { showPartyRecBar(false); return }
        VoiceRec.stop(send) { file, dur, wave ->
            showPartyRecBar(false)
            if (file == null) {
                if (send && dur < 1) Toast.makeText(this, "Voice bahut chhoti thi", Toast.LENGTH_SHORT).show()
                return@stop
            }
            val bytes = try { file.readBytes() } catch (_: Throwable) { null }
            try { file.delete() } catch (_: Throwable) {}
            if (bytes == null || bytes.size > 480 * 1024) {
                Toast.makeText(this, "Voice tower limit se bari hai", Toast.LENGTH_SHORT).show()
            } else sendPartyMedia("voice", bytes, dur, wave)
        }
    }

    private fun sendPartyMedia(type: String, bytes: ByteArray, dur: Int, wave: String) {
        val reply = partyReplyTo
        val mid = PartyTower.sendMessage(
            text = "",
            replyName = reply?.let { if (it.own) "You" else messageName(it) }.orEmpty(),
            replyText = reply?.let(::partyMessageLabel).orEmpty(),
            replyMid = reply?.fid.orEmpty(),
            type = type, media = bytes, dur = dur, wave = wave
        )
        if (mid == null) {
            Toast.makeText(this, "Tower connect nahi — media nahi gayi", Toast.LENGTH_SHORT).show(); return
        }
        MediaCache.save(this, "party_$mid", bytes)
        clearPartyReply()
    }

    private fun sendPartyMessage() {
        val text = partyInput.text.toString().trim()
        if (text.isEmpty()) return
        val reply = partyReplyTo
        val mid = PartyTower.sendMessage(
            text = text,
            replyName = reply?.let { if (it.own) "You" else messageName(it) }.orEmpty(),
            replyText = reply?.let(::partyMessageLabel).orEmpty(),
            replyMid = reply?.fid.orEmpty()
        )
        if (mid == null) {
            Toast.makeText(this, "Tower connect nahi — message nahi gaya", Toast.LENGTH_SHORT).show(); return
        }
        partyInput.setText("")
        clearPartyReply()
    }

    private fun renderPartyThread() {
        if (!::partyAdapter.isInitialized) return
        val rows = partyMsgs.map { m ->
            Row(Row.MSG, "party:${m.id}", partySig(m), m, null)
        }
        partyAdapter.submit(rows)
    }

    private fun partySig(m: Msg): String =
        m.fid + "|" + m.senderName + "|" + m.senderColor + "|" + m.text + "|" +
            m.replyName + "|" + m.replyText + "|" + m.time + "|" + m.type + "|" + m.mediaKey + "|" +
            (m.mediaKey.isNotBlank() && MediaCache.has(this, m.mediaKey)) + "|" +
            m.rx.entries.joinToString(",") { "${it.key}:${it.value}:${m.rxCounts[it.key] ?: 1}" }

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
        partyReplyWho.text = "Replying to " + if (m.own) "You" else messageName(m)
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
                if (m.own && m.fid.isNotBlank()) PartyTower.deleteMessage(m.fid)
                else {
                    partyMsgs.removeAll { it.id == m.id }
                    if (partyReplyTo?.id == m.id) clearPartyReply()
                    renderPartyThread()
                }
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
        if (m.fid.isBlank() || !PartyTower.isConnected()) {
            Toast.makeText(this, "Tower connect nahi", Toast.LENGTH_SHORT).show(); return
        }
        val adding = m.rx[emoji] != true
        PartyTower.sendReaction(m.fid, emoji)
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

    // ------------------------------------------------ selected Tower callbacks

    override fun onPartyTowerStatus(connected: Boolean, label: String) {
        partyTowerUp = connected
        if (::partyOnlineText.isInitialized) {
            partyOnlineText.text = if (connected) "●  $partyMemberCount online" else "📻 Reconnect…"
            partyOnlineText.setTextColor(if (connected) hex("#86efac") else hex("#fcd34d"))
            partyOnlineText.contentDescription = label
        }
    }

    override fun onPartyMembers(members: List<PartyMember>) {
        partyMemberCount = members.size.coerceAtLeast(1)
        if (::partyOnlineText.isInitialized) partyOnlineText.text =
            if (partyTowerUp) "●  $partyMemberCount online" else "📻 Reconnect…"
        renderPartyMembers(members)
    }

    private fun renderPartyMembers(incoming: List<PartyMember>) {
        if (!::partyMembersList.isInitialized) return
        val list = if (incoming.isEmpty()) listOf(PartyMember(
            PartyTower.currentMemberId(), WpUser.me(this), palette.accent[1], System.currentTimeMillis()))
        else incoming
        partyMembersTitle.text = "👥 Party Members (${list.size})"
        partyMembersList.removeAllViews()
        list.forEach { member ->
            val mine = member.id == PartyTower.currentMemberId()
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(2), dp(9), dp(2))
                background = roundBox(Color.argb(24, 255, 255, 255), member.color, 20, 1)
            }
            chip.addView(DpStore.circle(this, member.name, member.color, 20, isMe = mine), lp(dp(20), dp(20)))
            chip.addView(TextView(this).apply {
                text = member.name; textSize = 11f; setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            partyMembersList.addView(chip,
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(25)).apply { rightMargin = dp(5) })
        }
    }

    override fun onPartyMessage(message: PartyMessage) {
        if (partyMsgs.any { it.fid == message.mid }) return
        val own = message.senderId == PartyTower.currentMemberId()
        val m = Msg(
            id = nextPartyMsgId++, text = message.text, own = own,
            time = message.time.ifBlank {
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.ts))
            }, day = "", read = true, ts = message.ts,
            replyName = message.replyName, replyText = message.replyText,
            fid = message.mid, type = message.type,
            mediaKey = if (message.type == "text") "" else "party_${message.mid}",
            dur = message.dur, wave = message.wave,
            senderName = message.name, senderColor = message.color
        )
        partyMsgs.add(m)
        partyMsgs.sortBy { it.ts }
        while (partyMsgs.size > FirebaseChat.MSG_KEEP) {
            val old = partyMsgs.removeAt(0)
            if (old.mediaKey.startsWith("party_")) MediaCache.delete(this, old.mediaKey)
        }
        if (System.currentTimeMillis() - message.ts < 5_000L) partyAdapter.entryAnimId = m.id
        renderPartyThread()
        if (own || System.currentTimeMillis() - message.ts < 5_000L) scrollPartyBottom()
    }

    override fun onPartyMessageRemoved(mid: String) {
        val gone = partyMsgs.firstOrNull { it.fid == mid }
        if (gone != null && gone.mediaKey.startsWith("party_")) MediaCache.delete(this, gone.mediaKey)
        partyMsgs.removeAll { it.fid == mid }
        if (partyReplyTo?.fid == mid) clearPartyReply()
        renderPartyThread()
    }

    override fun onPartyMedia(mid: String, bytes: ByteArray) {
        val key = "party_$mid"
        MediaCache.save(this, key, bytes)
        partyMsgs.firstOrNull { it.fid == mid }?.mediaKey = key
        renderPartyThread()
    }

    override fun onPartyReactions(mid: String, values: Map<String, Pair<Int, Boolean>>) {
        val m = partyMsgs.firstOrNull { it.fid == mid } ?: return
        m.rx.clear(); m.rxCounts.clear()
        values.forEach { (emoji, state) ->
            m.rx[emoji] = state.second
            m.rxCounts[emoji] = state.first
        }
        renderPartyThread()
    }

    override fun onPartyQueue(items: List<PartyQueueItem>, index: Int) {
        partyQueue.clear(); partyQueue.addAll(items.map { it.copy() })
        partyQueueIndex = index
        renderPartyPlaylist()
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
