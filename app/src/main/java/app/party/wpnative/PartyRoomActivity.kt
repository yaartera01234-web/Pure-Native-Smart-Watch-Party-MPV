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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.view.inputmethod.InputContentInfo
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
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Original website mein DM ek overlay tha: Room kabhi destroy/recreate nahi hota tha.
 * Native activities mein bhi wahi rule rakhne ke liye live Room ka weak route rakha hai.
 */
object PartyRoomRoute {
    private var live: WeakReference<PartyRoomActivity>? = null
    @Volatile private var callSpeechDucking = false

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

    /**
     * The call's adaptive speech gate drives local movie ducking only after sustained speech.
     * Playback/sync are untouched and no Party command is published.
     */
    fun setCallSpeechDucking(duck: Boolean) {
        callSpeechDucking = duck
        val room = live?.get() ?: return
        room.runOnUiThread { room.setVoiceCallSpeechDucking(duck) }
    }

    fun isCallSpeechDucking(): Boolean = callSpeechDucking
}

/**
 * Enter Party ke baad wali **native Party Room** screen.
 *
 * Room chat ke saath Smart Music Watch Party ACT7 ka real native MPV player:
 * large Rave card, 66dp mini bar, immersive fullscreen, gestures, dual audio,
 * manual aspect/quality aur selected Room tower par retained playback sync.
 */
class PartyRoomActivity : Activity(), ChatHost, PartyTowerListener {

    /** party-final1.html ke exact semantic tokens shared by Join, Room and player. */
    private val palettes get() = WpThemes.all
    private val prefs by lazy { getSharedPreferences("wp_native", Context.MODE_PRIVATE) }
    private val partyBubble: WpBubbleTheme
        get() = WpBubbles.all[prefs.getInt("bubble", 1).coerceIn(WpBubbles.all.indices)]

    private var themeIndex = 1
    private var appliedThemeIndex = -1
    private lateinit var roomBackdrop: PartyRoomBackdrop
    private val palette: WpTheme get() = palettes[themeIndex]

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
    private val partyTypingNames = mutableListOf<String>()
    private lateinit var partyComposerRow: LinearLayout
    private lateinit var partyRecBar: LinearLayout
    private lateinit var partyRecTime: TextView
    private var leavingParty = false
    private val REQ_PARTY_PHOTO = 177
    private val REQ_PARTY_MIC = 178

    // ------------------------------------------------ native MPV / Rave player
    private lateinit var partyPlayer: PartyPlayerView
    private var mpvVideo: MpvVideoPlayer? = null
    private var voiceCallSpeechDucked = false
    private var currentMedia: PartyPlaybackMedia? = null
    private var currentMediaTitle = ""
    private var desiredPlaying = false
    private var playerLoading: String? = null // error only; progress is numeric below
    private var playerBufferingActive = false
    private var playerBufferResolved = false
    private var playerLoadIssued = false
    private var playerBufferPercent = 0
    private var playerBufferStartedAt = 0L
    private var playerBufferCompleteUntil = 0L
    private var playerQuality = 144
    private var playerQualities = listOf(144, 240, 360, 480, 720, 1080)
    private var resolveGeneration = 0L
    private var playerFullscreen = false
    private var fullscreenControls: MpvFullscreenControls? = null
    private var inlinePlayerParent: ViewGroup? = null
    private var inlinePlayerIndex = -1
    private var inlinePlayerLayout: ViewGroup.LayoutParams? = null
    private var endedHandled = false
    private var lastNotificationTitle = ""
    private var lastNotificationPlaying = false
    private var partyForeground = false
    private var pendingRetainedThaw = false
    private val playerMain = Handler(Looper.getMainLooper())
    private val stopPartyTyping = Runnable { PartyTower.sendTyping(false) }
    private val playerIo = Executors.newSingleThreadExecutor()
    private val serviceCommand: (String) -> Unit = { command -> runOnUiThread {
        when (command) {
            PartyPlayerService.ACTION_PREVIOUS -> userQueueStep(-1)
            PartyPlayerService.ACTION_PLAY -> if (!desiredPlaying) userPlayPlayback()
            PartyPlayerService.ACTION_PAUSE -> if (desiredPlaying) userPausePlayback()
            PartyPlayerService.ACTION_TOGGLE -> userTogglePlayback()
            PartyPlayerService.ACTION_NEXT -> userQueueStep(1)
            PartyPlayerService.ACTION_BACK -> userSeekBy(-10.0)
            PartyPlayerService.ACTION_FORWARD -> userSeekBy(10.0)
        }
    } }
    private val playbackSync by lazy {
        PartyPlaybackSync(
            myId = { PartyTower.currentMemberId() },
            mediaKey = { currentMedia?.key() },
            sample = {
                val media = currentMedia
                val player = mpvVideo
                if (media == null || player == null || !player.loaded()) null else PartySyncSample(
                    media.key(), player.rawPosition(), !player.isPaused() && !player.ended(),
                    player.buffering(), player.syncSpeed(), partyForeground && PartyTower.isConnected())
            },
            send = { PartyTower.publishSyncPacket(it) },
            apply = { seek, speed, playing ->
                val player = mpvVideo
                player?.setSyncSpeed(speed)
                if (seek != null) player?.seekTo(clampPlayerTime(seek))
                if (playing != null) {
                    desiredPlaying = playing
                    if (playing) player?.resume() else player?.pause()
                }
            }
        )
    }
    private val playerTick = object : Runnable {
        override fun run() {
            updatePlayerUi()
            playerMain.postDelayed(this, 450L)
        }
    }
    private val syncTick = object : Runnable {
        override fun run() {
            if (partyForeground && PartyTower.isConnected()) playbackSync.tick()
            else playbackSync.suspend()
            playerMain.postDelayed(this, 1_000L)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    override fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    override fun hex(v: String): Int = Color.parseColor(v)
    private fun cols(vararg v: String): IntArray = v.map(::hex).toIntArray()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        PartyRoomRoute.attach(this)
        themeIndex = prefs.getInt("theme", 1).coerceIn(palettes.indices)
        val migrated = WpThemes.migrateCoupling(prefs, themeIndex,
            prefs.getInt("bubble", 1).coerceIn(WpBubbles.all.indices))
        themeIndex = migrated.theme
        playerQuality = prefs.getInt("player_quality", 144)
            .takeIf { it in listOf(144, 240, 360, 480, 720, 1080) } ?: 144
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyWindowBase()
        setContentView(buildRoom())
        YtAudioSource.ensureInit(this)
        bindNativePlayer()
        PartyPlayerService.bind(serviceCommand)
        playerMain.removeCallbacks(playerTick)
        playerMain.removeCallbacks(syncTick)
        playerMain.post(playerTick)
        playerMain.post(syncTick)

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
        PartyTaskService.start(this)
    }

    /**
     * Smart Music website mein DM close hote hi wahi #app dobara paint hota tha.
     * Native mein Activity surface cover/uncover hoti hai, isliye resume par saved
     * theme dobara verify karke background render-node ko explicitly zinda karte hain.
     */
    override fun onResume() {
        super.onResume()
        partyForeground = true
        PartyTower.attach(this)
        if (PartyTower.isConnected()) playbackSync.reset(anchor = true)
        val saved = prefs.getInt("theme", 1).coerceIn(palettes.indices)
        if (saved != appliedThemeIndex) {
            themeIndex = saved
            applyWindowBase()
            setContentView(buildRoom())
            bindNativePlayer()
        } else {
            applyWindowBase()
            if (::partyPlayer.isInitialized) bindNativePlayer()
        }
        window.decorView.animate().cancel()
        window.decorView.alpha = 1f
        if (::roomBackdrop.isInitialized) roomBackdrop.restoreColors()
        setVoiceCallSpeechDucking(PartyRoomRoute.isCallSpeechDucking())
        CallMiniBar.attach(this)
    }

    override fun onPause() {
        refreshPlaybackCheckpoint()
        partyForeground = false
        playbackSync.suspend()
        mpvVideo?.setSyncSpeed(1.0)
        super.onPause()
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        if (newIntent != null) setIntent(newIntent)
        window.decorView.alpha = 1f
        if (::roomBackdrop.isInitialized) roomBackdrop.restoreColors()
    }

    override fun onDestroy() {
        refreshPlaybackCheckpoint()
        partyForeground = false
        playbackSync.suspend()
        // Recents se poori task urrna proper Leave hai; rotation/system reclaim nahi.
        if (isFinishing && !isChangingConfigurations && !leavingParty && PartyTower.hasLiveSession()) {
            PartyTaskService.leaveRemovedTask(applicationContext)
        }
        if (playerFullscreen) exitPlayerFullscreen()
        PartyTower.sendTyping(false)
        playerMain.removeCallbacksAndMessages(null)
        resolveGeneration++
        playerIo.shutdownNow()
        PartyPlayerService.unbind(serviceCommand)
        PartyPlayerService.stop(applicationContext)
        mpvVideo?.destroy()
        mpvVideo = null
        PartyTower.detach(this)
        VoiceRec.abort()
        PartyRoomRoute.detach(this)
        super.onDestroy()
    }

    @Deprecated("Android back callback compatibility")
    override fun onBackPressed() {
        if (playerFullscreen) { exitPlayerFullscreen(); return }
        if (leavingParty) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("🚪 Party Left?")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes") { _, _ -> leavePartyNow() }
            .create()
        dialog.show()
        styleRoomDialog(dialog)
    }

    private fun leavePartyNow() {
        if (leavingParty) return
        refreshPlaybackCheckpoint()
        leavingParty = true
        VoicePlay.stop()
        VoiceRec.abort()
        mpvVideo?.stop()
        PartyPlayerService.stop(applicationContext)
        partyMsgs.forEach { if (it.mediaKey.startsWith("party_")) MediaCache.delete(this, it.mediaKey) }
        partyMsgs.clear()
        if (::partyAdapter.isInitialized) renderPartyThread()
        PartyTower.leave {
            MediaCache.deletePrefix(this, "party_")
            PartyTaskService.stop(applicationContext)
            if (!isFinishing) {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun applyWindowBase() {
        window.setBackgroundDrawable(WpPageDrawable(palette, resources.displayMetrics.density))
    }

    private fun buildRoom(): View {
        keyboardCollapseViews.clear()
        roomKeyboardOpen = false
        val root = PartyRoomBackdrop(this, palette)
        roomBackdrop = root
        appliedThemeIndex = themeIndex
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Keyboard khule to fixed player blocks chupte hain, warna composer screen ke neeche kat jata hai.
        sourceRowView = buildSourceRow()
        col.addView(sourceRowView, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(51f)).apply {
            setMargins(dp(8f), dp(8f), dp(8f), 0)
        })

        // Smart Music Watch Party ka real large Rave card; isi mein live MPV surface hai.
        partyPlayer = PartyPlayerView(this).also {
            it.setTheme(palette)
            keyboardCollapseViews.add(it)
        }
        col.addView(partyPlayer, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
                // ACT4/ACT7 edge fix: fullscreen surface/overlay must own the cutout area.
                // Insets protect controls inside MpvFullscreenControls, never the MPV root.
                val leftPad = if (playerFullscreen) 0 else bars.left
                val topPad = if (playerFullscreen) 0 else bars.top
                val rightPad = if (playerFullscreen) 0 else bars.right
                val bottomPad = if (playerFullscreen) 0 else bottom
                if (view.paddingLeft != leftPad || view.paddingTop != topPad ||
                    view.paddingRight != rightPad || view.paddingBottom != bottomPad) {
                    view.setPadding(leftPad, topPad, rightPad, bottomPad)
                }
                setRoomKeyboardMode(keyboard && !playerFullscreen)
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
                setStroke(dp(1f), palette.headerStroke)
            }
            elevation = dp(2f).toFloat()
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(PartyBarsLogo(this), lp(dp(23f), dp(23f)))
        brand.addView(PartyGradientLabel(this, palette.accent).apply {
            // Compact Room-only brand keeps the live online pill fully separate on narrow phones.
            text = "Smart Party +"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
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
        background = roundBox(palette.chip, palette.itemStroke, 10f, 1f)
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
            text = "Poore Party Room ka rang badalta hai"
            textSize = 11f
            setTextColor(palette.accentText)
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
                            if (selected) withAlpha(palette.focus, 54) else palette.soft,
                            if (selected) p.accent[1] else palette.itemStroke,
                            14f, if (selected) 2f else 1f)
                        addView(View(this@PartyRoomActivity).apply {
                            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                                if (index == 1) cols("#3a3170", "#241f4d") else p.fill2).apply {
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
                            val selected = WpThemes.select(prefs, themeIndex,
                                prefs.getInt("bubble", 1).coerceIn(WpBubbles.all.indices), index)
                            themeIndex = selected.theme
                            dialog?.dismiss()
                            applyWindowBase()
                            setContentView(buildRoom())
                            bindNativePlayer()
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
            setTextColor(palette.buttonText)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                palette.fill2).apply {
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
        shownDialog.window?.setBackgroundDrawable(GradientDrawable(
            GradientDrawable.Orientation.TL_BR, palette.menu).apply {
            cornerRadius = dp(20f).toFloat(); setStroke(dp(1f), palette.panelStroke)
        })
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
            setOnFocusChangeListener { view, focused ->
                view.background = themedInput(12f, focused)
                if (roomKeyboardOpen) setRoomKeyboardMode(true)
            }
        }
        row.addView(sourceInput, LinearLayout.LayoutParams(0, dp(44f), 1f))

        row.addView(sourceButton("▶ Play", palette.sourcePlay, Color.WHITE) {
            playSourceNow()
        }, lp(dp(59f), dp(44f)).apply { leftMargin = dp(6f) })
        row.addView(sourceButton("＋", palette.fill2, palette.buttonText) {
            addSourceToPlaylist()
        }, lp(dp(43f), dp(44f)).apply { leftMargin = dp(6f) })
        row.addView(searchButton(), lp(dp(43f), dp(44f)).apply { leftMargin = dp(6f) })
        return row
    }

    private fun sourceButton(label: String, colors: IntArray, textColor: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (label.length > 2) 11.5f else 20f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textColor)
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
            setOnClickListener { openYouTubeSearch() }
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
            setTextColor(palette.accentText)
        }
        head.addView(playlistCount)
        playlistHint = TextView(this).apply {
            text = "Tap to expand"
            textSize = 10f
            setTextColor(withAlpha(palette.accentText, 205))
            gravity = Gravity.CENTER
        }
        head.addView(playlistHint, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
        playlistArrow = TextView(this).apply {
            text = "▼"
            textSize = 12f
            setTextColor(palette.accentText)
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
        val media = parsePartyMedia(raw)
        val item = PartyQueueItem(
            id = "q" + System.currentTimeMillis().toString(36),
            type = media.type,
            url = media.url,
            videoId = media.videoId,
            label = if (media.videoId.isNotBlank()) "YouTube · ${media.videoId}" else directMediaName(raw),
            by = WpUser.me(this)
        )
        partyQueue.add(item)
        PartyTower.publishQueue(partyQueue, partyQueueIndex)
        sourceInput.setText("")
        playlistOpen = true
        renderPartyPlaylist()
        if (media.videoId.isNotBlank()) PartyPlaylistMedia.title(media.videoId) { title ->
            val live = partyQueue.firstOrNull { it.id == item.id } ?: return@title
            if (live.title.isBlank()) {
                live.title = title; live.label = title
                PartyTower.publishQueue(partyQueue, partyQueueIndex)
                renderPartyPlaylist()
            }
        }
    }

    private fun youtubeId(raw: String): String {
        val clean = raw.trim()
        if (Regex("[A-Za-z0-9_-]{11}").matches(clean)) return clean
        return try {
            val u = Uri.parse(clean)
            val host = u.host?.lowercase(Locale.US).orEmpty()
            // A direct MKV/MP4 CDN URL can also have ?v=xxxxxxxxxxx. It is YouTube
            // only when the HOST is actually youtube.com/youtu.be.
            val youtubeHost = host == "youtube.com" || host.endsWith(".youtube.com")
            val id = when {
                host == "youtu.be" || host.endsWith(".youtu.be") -> u.pathSegments.firstOrNull().orEmpty()
                youtubeHost && u.pathSegments.firstOrNull() in listOf("shorts", "embed", "live") ->
                    u.pathSegments.getOrNull(1).orEmpty()
                youtubeHost -> u.getQueryParameter("v").orEmpty()
                else -> ""
            }
            id.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }.orEmpty()
        } catch (_: Throwable) { "" }
    }

    private fun parsePartyMedia(raw: String): PartyPlaybackMedia {
        val clean = raw.trim()
        val id = youtubeId(clean)
        if (id.isNotBlank()) return PartyPlaybackMedia("youtube", clean, id)
        val path = clean.substringBefore('?').substringBefore('#')
        val type = when {
            path.endsWith(".m3u8", true) -> "hls"
            Regex("\\.(mp3|wav|ogg|m4a|aac|flac)$", RegexOption.IGNORE_CASE).containsMatchIn(path) -> "mp3"
            // MKV/WEBM/MOV and unknown direct media all go to MPV's video path.
            else -> "mp4"
        }
        return PartyPlaybackMedia(type, clean, "")
    }

    private fun normalizePartyMedia(media: PartyPlaybackMedia): PartyPlaybackMedia =
        if (media.type == "youtube" && media.url.isNotBlank() && youtubeId(media.url).isBlank()) {
            parsePartyMedia(media.url)
        } else media

    private fun directMediaName(raw: String): String = try {
        val pathName = Uri.parse(raw.trim()).lastPathSegment.orEmpty()
        Uri.decode(pathName).ifBlank { "Direct media" }.take(80)
    } catch (_: Throwable) {
        raw.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "Direct media" }.take(80)
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
                setTextColor(withAlpha(palette.accentText, 180))
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
                if (index == partyQueueIndex) withAlpha(palette.accent[1], 48) else palette.soft,
                palette.itemStroke, 10, 1)
            setOnClickListener { playQueueItem(index) }
        }
        if (item.type == "youtube" && item.videoId.isNotBlank()) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundBox(palette.soft2, palette.itemStroke, 8, 1)
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
                background = roundBox(palette.soft2, palette.itemStroke, 8, 1)
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
            setTextColor(withAlpha(palette.accentText, 170)); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(names, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "☰"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(palette.accentText)
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
            setTextColor(Color.WHITE); setHintTextColor(withAlpha(palette.accentText, 170))
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = themedInput(12f)
            setOnFocusChangeListener { view, focused -> view.background = themedInput(12f, focused) }
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
        styleRoomDialog(dialog)
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
            background = roundBox(palette.soft2, palette.itemStroke, 0f, 0f)
        }
        partyMembersTitle = TextView(this).apply {
            text = "👥 Party Members (1)"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.accentText)
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
     * Original room composer: emoji row typing box ke upar; Gboard/Samsung GIF isi
     * text field se selected Party Tower ke encrypted chat route par jati hai.
     */
    private fun buildComposer(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
            background = roundBox(palette.inputBar, palette.itemStroke, 0f, 1f)
        }
        val sc = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val emojis = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Screenshot wali website row ka native visual match: Color Emoji ko opaque
        // white paint milta hai (warna Material ka inherited text alpha usay dim karta
        // hai), glyph 20sp aur har centre ke darmiyan qareeban website wala 30dp step.
        listOf("😂", "❤️", "🔥", "😭", "👏", "🥳", "👍").forEach { e ->
            emojis.addView(TextView(this).apply {
                text = e
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = "Message mein $e lagao"
                setOnClickListener { appendPartyEmoji(e) }
            }, lp(dp(24f), dp(36f)).apply { rightMargin = dp(6f) })
        }
        // Alag GIF button nahi: Gboard/Samsung ke GIF tab se direct send hoti hai.
        emojis.addView(partyMediaIcon("photo", palette.fill2) { openPartyPhotoPicker() },
            lp(dp(36f), dp(36f)).apply { leftMargin = dp(4f) })
        emojis.addView(partyMediaIcon("mic", palette.fill2) { startPartyVoice() },
            lp(dp(36f), dp(36f)).apply { leftMargin = dp(5f) })
        sc.addView(emojis, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38f)))
        box.addView(sc, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(39f)))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        partyInput = KeyboardGifEditText(this).apply {
            onGifContent = { content -> receivePartyKeyboardGif(content); true }
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
            setOnFocusChangeListener { view, focused ->
                view.background = themedInput(23f, focused)
                if (roomKeyboardOpen) setRoomKeyboardMode(true)
                if (!focused) PartyTower.sendTyping(false)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    playerMain.removeCallbacks(stopPartyTyping)
                    if (s.isNullOrBlank()) PartyTower.sendTyping(false) else {
                        PartyTower.sendTyping(true)
                        playerMain.postDelayed(stopPartyTyping, 3_500L)
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
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
            setTextColor(palette.buttonText)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.fill2).apply {
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
        addView(WpIcon(this@PartyRoomActivity, kind, palette.buttonText),
            FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER))
        setOnClickListener { action() }
    }

    private fun buildPartyRecBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = roundBox(palette.soft, Color.TRANSPARENT, 11, 0)
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
            background = roundBox(palette.chip, Color.TRANSPARENT, 9, 0)
            setOnClickListener { stopPartyVoice(false) }
        }, lp(dp(44), dp(28)).apply { rightMargin = dp(6) })
        addView(TextView(this@PartyRoomActivity).apply {
            text = "➤"; gravity = Gravity.CENTER; setTextColor(palette.buttonText)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                palette.fill2).apply { cornerRadius = dp(9).toFloat() }
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

    /** Gboard/Samsung GIF: URL-first; local-only payload tower ke liye max 300 KiB. */
    private fun receivePartyKeyboardGif(content: InputContentInfo) {
        if (!PartyTower.isConnected()) {
            try { content.releasePermission() } catch (_: Throwable) { }
            Toast.makeText(this, "Tower connect nahi — GIF nahi gayi", Toast.LENGTH_SHORT).show()
            return
        }
        val directUrl = content.linkUri?.toString()?.takeIf(::isDirectPartyGifUrl).orEmpty()
        Toast.makeText(this, "GIF tower ke liye taiyar ho rahi hai…", Toast.LENGTH_SHORT).show()
        Thread {
            val bytes = try {
                contentResolver.openInputStream(content.contentUri)?.use { inputStream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    var tooLarge = false
                    while (true) {
                        val n = inputStream.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 8 * 1024 * 1024) { tooLarge = true; break }
                        out.write(buf, 0, n)
                    }
                    if (tooLarge) null else out.toByteArray().takeIf(GifMovieView::isGif)
                }
            } catch (_: Throwable) { null }
            try { content.releasePermission() } catch (_: Throwable) { }
            runOnUiThread {
                when {
                    isFinishing -> Unit
                    directUrl.isNotBlank() -> sendPartyGif(bytes, directUrl)
                    bytes == null -> Toast.makeText(this,
                        "Ye keyboard GIF share nahi kar saka — doosri GIF try karein", Toast.LENGTH_LONG).show()
                    bytes.size > 300 * 1024 -> Toast.makeText(this,
                        "GIF tower limit se bari hai — chhoti GIF choose karein", Toast.LENGTH_LONG).show()
                    else -> sendPartyGif(bytes, "")
                }
            }
        }.start()
    }

    private fun isDirectPartyGifUrl(raw: String): Boolean = try {
        val uri = Uri.parse(raw)
        uri.scheme.equals("https", true) &&
            (uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".gif") ||
                raw.substringBefore('?').lowercase(Locale.ROOT).endsWith(".gif"))
    } catch (_: Throwable) { false }

    private fun sendPartyGif(bytes: ByteArray?, directUrl: String) {
        val reply = partyReplyTo
        val towerBytes = if (directUrl.isBlank()) bytes else null
        val mid = PartyTower.sendMessage(
            text = "",
            replyName = reply?.let { if (it.own) "You" else messageName(it) }.orEmpty(),
            replyText = reply?.let(::partyMessageLabel).orEmpty(),
            replyMid = reply?.fid.orEmpty(),
            type = "gif", media = towerBytes, mediaUrl = directUrl
        )
        if (mid == null) {
            Toast.makeText(this, "Tower connect nahi — GIF nahi gayi", Toast.LENGTH_SHORT).show()
            return
        }
        if (bytes != null) MediaCache.save(this, "party_$mid", bytes)
        clearPartyReply()
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
        val rows = partyMsgs.mapTo(mutableListOf()) { m ->
            Row(Row.MSG, "party:${m.id}", partySig(m), m, null)
        }
        // ChatAdapter ka wahi DM typing bubble: avatar + animated three dots.
        if (partyTypingNames.isNotEmpty()) {
            rows += Row(Row.TYPING, "party:typing", partyTypingNames.joinToString("|"), null, null)
        }
        partyAdapter.submit(rows)
    }

    private fun partySig(m: Msg): String =
        m.fid + "|" + m.senderName + "|" + m.senderColor + "|" + m.text + "|" +
            m.replyName + "|" + m.replyText + "|" + m.replyMid + "|" + m.time + "|" + m.type + "|" + m.mediaKey + "|" +
            m.mediaUrl + "|" + (m.mediaKey.isNotBlank() && MediaCache.has(this, m.mediaKey)) + "|" +
            m.rx.entries.joinToString(",") { "${it.key}:${it.value}:${m.rxCounts[it.key] ?: 1}" }

    private fun scrollPartyBottom() {
        partyRv.post {
            if (partyAdapter.itemCount > 0) partyRv.scrollToPosition(partyAdapter.itemCount - 1)
        }
    }

    private fun openPartyOriginal(reply: Msg) {
        val target = reply.replyMid.takeIf { it.isNotBlank() }?.let { id ->
            partyMsgs.firstOrNull { it.fid == id }
        } ?: partyMsgs.asSequence().filter { it.id != reply.id && it.ts <= reply.ts }
            .lastOrNull { partyMessageLabel(it) == reply.replyText }
        if (target == null) {
            Toast.makeText(this, "Original Party message ab available nahi", Toast.LENGTH_SHORT).show()
            return
        }
        val pos = partyAdapter.indexOfKey("party:${target.id}")
        if (pos < 0) return
        partyRv.post {
            partyLm.scrollToPositionWithOffset(pos, (partyRv.height / 3).coerceAtLeast(0))
            partyRv.post pulse@{
                val view = partyRv.findViewHolderForAdapterPosition(pos)?.itemView ?: return@pulse
                view.animate().cancel()
                view.alpha = .45f; view.scaleX = .97f; view.scaleY = .97f
                view.animate().alpha(1f).scaleX(1.035f).scaleY(1.035f).setDuration(180L)
                    .withEndAction {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                    }.start()
            }
        }
    }

    private fun buildPartyReplyBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundBox(palette.soft, Color.TRANSPARENT, 12, 0)
        }
        val line = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                palette.fill2).apply {
                cornerRadius = dp(2).toFloat()
            }
        }
        bar.addView(line, lp(dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
        val replyTextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        partyReplyWho = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.accentText)
            setSingleLine(true)
        }
        partyReplyWhat = TextView(this).apply {
            textSize = 12f
            setTextColor(hex("#d1d5db"))
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
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(31, 255, 255, 255))
            }
            setOnClickListener { clearPartyReply() }
        }, lp(dp(26), dp(26)))
        partyReplyWrap = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(6))
            addView(bar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun showPartyReply(m: Msg, openKeyboard: Boolean = true) {
        partyReplyTo = m
        partyReplyWho.text = "↩ Replying to " + if (m.own) "You" else messageName(m)
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
        "gif" -> "🎞️ GIF"
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
            background = themedInput(10f)
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
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.menu).apply {
                    cornerRadius = dp(14).toFloat(); setStroke(dp(1), palette.panelStroke)
                }
                addView(partyEmojiInput, LinearLayout.LayoutParams(0, dp(43), 1f))
                addView(TextView(this@PartyRoomActivity).apply {
                    text = "✕"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundBox(palette.chip, Color.TRANSPARENT, 10, 0)
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
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.menu).apply {
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                setStroke(dp(1), palette.panelStroke)
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
        background = roundBox(palette.chip, Color.TRANSPARENT, 9, 0)
        setOnClickListener { action() }
    }

    private fun togglePartyReaction(m: Msg, emoji: String) {
        if (m.fid.isBlank() || !PartyTower.isConnected()) {
            Toast.makeText(this, "Tower connect nahi", Toast.LENGTH_SHORT).show(); return
        }
        // Sender bhi tower echo par fly karega; local + echo double animation nahi hogi.
        PartyTower.sendReaction(m.fid, emoji)
    }

    /** DM ka Instagram-style 3-second flying reaction. */
    private fun flyPartyReaction(emoji: String) {
        if (!::partyFlyLayer.isInitialized || emoji.isBlank()) return
        partyFlyLayer.visibility = View.VISIBLE
        partyFlyLayer.bringToFront()
        partyFlyLayer.elevation = dp(40).toFloat()
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
        val wasConnected = partyTowerUp
        partyTowerUp = connected
        if (!connected) {
            playbackSync.suspend()
            mpvVideo?.setSyncSpeed(1.0)
        } else if (!wasConnected && partyForeground) {
            playbackSync.reset(anchor = true)
        }
        if (::partyOnlineText.isInitialized) {
            partyOnlineText.text = if (connected) "●  $partyMemberCount online" else "📻 Reconnect…"
            partyOnlineText.setTextColor(if (connected) hex("#86efac") else hex("#fcd34d"))
            partyOnlineText.contentDescription = label
        }
    }

    override fun onPartyMembers(members: List<PartyMember>) {
        val visible = members.toMutableList()
        val myId = PartyTower.currentMemberId()
        // The only local fallback is the actual signed-in user — never a sample member.
        if (visible.none { it.id == myId }) visible += PartyMember(
            myId, WpUser.me(this), palette.accent[1], System.currentTimeMillis())
        partyMemberCount = visible.size
        if (::partyOnlineText.isInitialized) partyOnlineText.text =
            if (partyTowerUp) "●  $partyMemberCount online" else "📻 Reconnect…"
        renderPartyMembers(visible)
        // Presence payload DP register karta hai; already-visible message rows bhi rebind hon.
        if (::partyAdapter.isInitialized) partyAdapter.notifyDataSetChanged()
    }

    private fun renderPartyMembers(list: List<PartyMember>) {
        if (!::partyMembersList.isInitialized) return
        partyMembersTitle.text = "👥 Party Members (${list.size})"
        partyMembersList.removeAllViews()
        list.forEach { member ->
            val mine = member.id == PartyTower.currentMemberId()
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(2), dp(9), dp(2))
                background = roundBox(palette.chip, member.color, 20, 1)
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
            replyName = message.replyName, replyText = message.replyText, replyMid = message.replyMid,
            fid = message.mid, type = message.type,
            mediaKey = if (message.type == "text") "" else "party_${message.mid}",
            mediaUrl = message.mediaUrl,
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
        // Har tower reaction event ek callback deta hai: count barhe to sender samet sab
        // active members ke foreground overlay par wahi emoji fly kare.
        val flying = values.entries.filter { (emoji, state) ->
            state.first > (m.rxCounts[emoji] ?: 0)
        }.map { it.key }
        m.rx.clear(); m.rxCounts.clear()
        values.forEach { (emoji, state) ->
            m.rx[emoji] = state.second
            m.rxCounts[emoji] = state.first
        }
        renderPartyThread()
        flying.forEach { emoji -> window.decorView.post { flyPartyReaction(emoji) } }
    }

    override fun onPartyQueue(items: List<PartyQueueItem>, index: Int) {
        var corrected = false
        val normalized = items.map { item ->
            if (item.type == "youtube" && item.url.isNotBlank() && youtubeId(item.url).isBlank()) {
                corrected = true
                val media = parsePartyMedia(item.url)
                item.copy(type = media.type, videoId = "",
                    label = if (item.label.startsWith("YouTube", true)) directMediaName(item.url) else item.label,
                    title = if (item.title.startsWith("YouTube", true)) "" else item.title)
            } else item.copy()
        }
        partyQueue.clear(); partyQueue.addAll(normalized)
        partyQueueIndex = index
        if (corrected && PartyTower.isConnected()) PartyTower.publishQueue(partyQueue, index)
        currentMedia?.let { currentMediaTitle = titleForMedia(it) }
        renderPartyPlaylist()
        updatePlayerUi()
    }

    override fun onPartyTyping(names: List<String>) {
        partyTypingNames.clear(); partyTypingNames.addAll(names)
        renderPartyThread()
        if (names.isNotEmpty()) scrollPartyBottom()
    }

    override fun onPartyPlaybackState(state: PartyPlaybackState) {
        applyRetainedState(state)
    }

    override fun onPartyPlaybackCommand(command: PartyPlaybackCommand) {
        applyRemoteCommand(command)
    }

    // ChatAdapter ke liye Party Room host. Bubble gestures/design DM ke exact engine se.
    override fun ctx(): Context = this
    override fun peerName(): String = "Party"
    override fun typingName(): String = partyTypingNames.joinToString(", ").ifBlank { peerName() }
    override fun meName(): String = WpUser.me(this)
    override fun peerColorInt(): Int = palette.accent[2]
    override fun originalPartyChat(): Boolean = true
    override fun mineBubbleBg(): Drawable {
        val style = prefs.getInt("bubble", 1).coerceIn(WpBubbles.all.indices)
        return WpBubbleDrawable(
            colors = partyBubble.own.copyOf(), mine = true,
            density = resources.displayMetrics.density,
            glow = if (style == 1) Color.TRANSPARENT else partyBubble.ownGlow,
            // Original Pink+Cyan own bubble is deliberately flat: no edge/gloss/shadow.
            border = if (style in 2..7) Color.argb(56, 255, 255, 255) else null,
            glossy = style != 1, angle = if (style == 0 || style >= 8) 110f else 135f
        )
    }
    override fun peerBubbleBg(): Drawable {
        val style = prefs.getInt("bubble", 1).coerceIn(WpBubbles.all.indices)
        return WpBubbleDrawable(
            colors = partyBubble.other.copyOf(), mine = false,
            density = resources.displayMetrics.density, glow = partyBubble.otherGlow,
            border = partyBubble.edge ?: Color.argb(66, 255, 255, 255),
            glossy = true, angle = if (style == 0 || style >= 8) 125f else 135f
        )
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
    override fun onQuoteClick(m: Msg) = openPartyOriginal(m)
    override fun onBubbleLongPress(m: Msg) = showPartyMessageActions(m)
    override fun onChipClick(m: Msg, emoji: String) = togglePartyReaction(m, emoji)

    // ------------------------------------------------ MPV player / selected Room sync

    private fun bindNativePlayer() {
        if (!::partyPlayer.isInitialized) return
        partyPlayer.setTheme(palette)
        val player = mpvVideo ?: MpvVideoPlayer(this, partyPlayer.surfaceHost).also {
            mpvVideo = it
            it.ensure()
        }
        player.attachRoot(partyPlayer.surfaceHost)
        player.setMaskColor("#000000")
        partyPlayer.bind(object : PartyPlayerActions {
            override fun onTogglePlayback() = userTogglePlayback()
            override fun onSeekTo(seconds: Double) = userSeekTo(seconds)
            override fun onSeekBy(seconds: Double) = userSeekBy(seconds)
            override fun onToggleMute() {
                player.setMuted(!player.isMuted())
                updatePlayerUi()
            }
            override fun onFullscreen() = enterPlayerFullscreen()
            override fun onAudioTracks() = chooseInlineAudioTrack()
            override fun onQuality() = chooseInlineQuality()
            override fun onMiniChanged(mini: Boolean) {
                prefs.edit().putBoolean("player_mini", mini).apply()
            }
        })
        partyPlayer.setMini(prefs.getBoolean("player_mini", false))
        if (currentMedia != null) player.show() else player.hide()
        updatePlayerUi()
    }

    /** Link Play dabte hi URI field ka IME/focus hata kar poora Room layout wapas lao. */
    private fun dismissSourceKeyboard() {
        val token = sourceInput.windowToken ?: window.decorView.windowToken
        sourceInput.clearFocus()
        roomBackdrop.isFocusableInTouchMode = true
        roomBackdrop.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(token, 0)
        // Insets callback bhi baad mein yahi state confirm karega; immediate restore se
        // player/playlist keyboard animation ke peeche chhupe nahi rehte.
        setRoomKeyboardMode(false)
        roomBackdrop.post { roomBackdrop.requestApplyInsets() }
    }

    private fun playSourceNow() {
        dismissSourceKeyboard()
        val raw = sourceInput.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Pehle YouTube, MP4 ya MP3 link paste karo", Toast.LENGTH_SHORT).show()
            return
        }
        if (!PartyTower.isConnected()) {
            Toast.makeText(this, "Tower connect hone do", Toast.LENGTH_SHORT).show()
            return
        }
        val media = parsePartyMedia(raw)
        val label = if (media.type == "youtube") "YouTube · ${media.videoId}"
        else directMediaName(media.url)
        val item = PartyQueueItem("q" + System.currentTimeMillis().toString(36), media.type,
            media.url, media.videoId, label, by = WpUser.me(this))
        partyQueue.add(item)
        partyQueueIndex = partyQueue.lastIndex
        PartyTower.publishQueue(partyQueue, partyQueueIndex)
        renderPartyPlaylist()
        sourceInput.setText("")
        playMediaEverywhere(media, item.name.ifBlank { item.originalName() })
        if (media.videoId.isNotBlank()) PartyPlaylistMedia.title(media.videoId) { resolved ->
            val live = partyQueue.firstOrNull { it.id == item.id } ?: return@title
            if (live.title.isBlank()) {
                live.title = resolved; live.label = resolved
                if (currentMedia?.key() == media.key()) currentMediaTitle = live.name.ifBlank { resolved }
                PartyTower.publishQueue(partyQueue, partyQueueIndex)
                renderPartyPlaylist(); updatePlayerUi()
            }
        }
    }

    private fun playQueueItem(index: Int) {
        val item = partyQueue.getOrNull(index) ?: return
        if (!PartyTower.isConnected()) {
            Toast.makeText(this, "Tower connect hone do", Toast.LENGTH_SHORT).show(); return
        }
        partyQueueIndex = index
        PartyTower.publishQueue(partyQueue, index)
        renderPartyPlaylist()
        playMediaEverywhere(PartyPlaybackMedia(item.type, item.url, item.videoId),
            item.name.ifBlank { item.originalName() })
    }

    private fun playMediaEverywhere(media: PartyPlaybackMedia, title: String) {
        val normalized = normalizePartyMedia(media)
        val normalizedTitle = if (normalized != media && title.startsWith("YouTube", true)) {
            directMediaName(normalized.url)
        } else title
        pendingRetainedThaw = false
        val wp4 = playbackSync.localCommand()
        loadPartyMedia(normalized, 0.0, true, normalizedTitle)
        PartyTower.publishPlaybackCommand("load", media = normalized, user = true, wp4 = wp4)
        publishPlaybackSnapshot(normalized, 0.0, true, wp4.getJSONArray("epoch"))
        showPlayerActivity(WpUser.me(this), "play", "Resumed", normalizedTitle)
    }

    private fun loadPartyMedia(
        incoming: PartyPlaybackMedia,
        position: Double,
        playWhenReady: Boolean,
        titleHint: String = titleForMedia(incoming),
        qualityOverride: Int? = null
    ) {
        val media = normalizePartyMedia(incoming)
        if (media.type == "none" || (media.type != "youtube" && media.url.isBlank())) return
        currentMedia = media
        currentMediaTitle = if (media != incoming && titleHint.startsWith("YouTube", true)) {
            directMediaName(media.url)
        } else titleHint.ifBlank { titleForMedia(media) }
        desiredPlaying = playWhenReady
        endedHandled = false
        val generation = ++resolveGeneration
        val player = mpvVideo ?: return
        player.stop()
        player.clearError()
        playerLoading = null
        playerBufferingActive = true
        playerBufferResolved = media.type != "youtube"
        playerLoadIssued = false
        playerBufferPercent = 1
        playerBufferStartedAt = SystemClock.elapsedRealtime()
        playerBufferCompleteUntil = 0L
        player.show()
        partyPlayer.setMini(false)
        updatePlayerUi()

        if (media.type == "youtube") {
            val quality = qualityOverride ?: playerQuality
            try {
                playerIo.execute {
                    val result = YtAudioSource.resolve(media.videoId, preferHeight = quality)
                    runOnUiThread {
                        if (generation != resolveGeneration || currentMedia?.key() != media.key()) return@runOnUiThread
                        if (result == null) {
                            playerBufferingActive = false
                            playerBufferPercent = 0
                            playerLoading = "Stream unavailable"
                            Toast.makeText(this, "YouTube stream nahi mili — link ya net check karo", Toast.LENGTH_LONG).show()
                            updatePlayerUi()
                            return@runOnUiThread
                        }
                        playerBufferResolved = true
                        playerBufferPercent = maxOf(playerBufferPercent, 45)
                        playerBufferStartedAt = SystemClock.elapsedRealtime()
                        playerQuality = result.height.takeIf { it > 0 } ?: quality
                        if (result.qualities.isNotEmpty()) playerQualities = result.qualities
                        if (!result.title.isNullOrBlank() && (currentMediaTitle.startsWith("YouTube ·") || currentMediaTitle.isBlank())) {
                            currentMediaTitle = result.title
                        }
                        player.ensure {
                            if (generation != resolveGeneration) return@ensure
                            playerLoadIssued = true
                            player.play(result.url, position, startMuted = false, audioUrl = result.audioUrl,
                                userAgent = result.userAgent, referer = result.referer)
                            if (!desiredPlaying) player.pause()
                        }
                    }
                }
            } catch (_: Throwable) { }
        } else {
            player.ensure {
                if (generation != resolveGeneration) return@ensure
                playerLoadIssued = true
                player.play(media.url, position, startMuted = false)
                if (!desiredPlaying) player.pause()
            }
        }
        playbackSync.reset(anchor = true)
    }

    private fun titleForMedia(media: PartyPlaybackMedia): String {
        val queued = partyQueue.firstOrNull {
            (media.type == "youtube" && it.videoId == media.videoId) ||
                (media.type != "youtube" && it.url == media.url)
        }
        if (queued != null) return queued.name.ifBlank { queued.originalName() }
        if (media.type == "youtube") return "YouTube · ${media.videoId}"
        return try {
            Uri.decode(media.url.substringBefore('?').substringAfterLast('/'))
                .replace(Regex("\\.[A-Za-z0-9]{2,5}$"), "")
                .replace(Regex("[-_]+"), " ").ifBlank { "Video" }
        } catch (_: Throwable) { "Video" }
    }

    private fun sameMedia(media: PartyPlaybackMedia): Boolean = currentMedia?.key() == media.key()

    private fun queueItemForPlayback(): PartyQueueItem? {
        partyQueue.getOrNull(partyQueueIndex)?.let { return it.copy() }
        val key = currentMedia?.key() ?: return null
        return partyQueue.firstOrNull {
            PartyPlaybackMedia(it.type, it.url, it.videoId).key() == key
        }?.copy()
    }

    private fun publishPlaybackSnapshot(
        media: PartyPlaybackMedia,
        time: Double,
        playing: Boolean,
        epoch: org.json.JSONArray = playbackSync.currentEpoch(),
        clockRunning: Boolean = playing,
        frozen: Boolean = false
    ) {
        PartyTower.publishPlaybackState(
            media = media,
            time = time,
            playing = playing,
            title = currentMediaTitle,
            itemIndex = partyQueueIndex,
            item = queueItemForPlayback(),
            clockRunning = clockRunning,
            frozen = frozen,
            epoch = epoch
        )
    }

    private fun refreshPlaybackCheckpoint() {
        if (leavingParty) return
        val media = currentMedia ?: return
        val player = mpvVideo
        val position = if (player != null && player.loaded()) player.rawPosition() else player?.position() ?: 0.0
        val playing = desiredPlaying && player?.ended() != true
        PartyTower.updatePlaybackCheckpoint(
            media = media,
            time = position.coerceAtLeast(0.0),
            playing = playing,
            title = currentMediaTitle,
            itemIndex = partyQueueIndex,
            item = queueItemForPlayback(),
            epoch = playbackSync.currentEpoch()
        )
    }

    private fun applyRetainedState(state: PartyPlaybackState) {
        val player = mpvVideo
        val requireRestore = !sameMedia(state.media)
        val decision = playbackSync.onRetainedState(state.epochCounter, state.epochOwner, requireRestore)
        if (decision == PartyPlaybackSync.RetainedDecision.REJECT) return
        if (state.queueIndex >= 0) partyQueueIndex = state.queueIndex
        state.queueItem?.let { embedded ->
            val localIndex = partyQueue.indexOfFirst { it.id == embedded.id }
            if (localIndex >= 0) partyQueue[localIndex] = embedded.copy()
        }
        if (state.title.isNotBlank() && (currentMedia == null || sameMedia(state.media))) {
            currentMediaTitle = state.title
        }
        if (decision == PartyPlaybackSync.RetainedDecision.METADATA_ONLY) {
            renderPartyPlaylist(); updatePlayerUi(); return
        }
        val age = if (state.playing && state.clockRunning && !state.frozen && state.at > 0L) {
            (System.currentTimeMillis() - state.at).coerceAtLeast(0L) / 1000.0
        } else 0.0
        val target = (state.time + age).coerceAtLeast(0.0)
        pendingRetainedThaw = state.frozen && state.playing
        if (sameMedia(state.media) && player != null && player.loaded()) {
            val exactTarget = clampPlayerTime(target)
            if (abs(player.rawPosition() - exactTarget) > .05) player.seekTo(exactTarget)
            desiredPlaying = state.playing
            if (state.playing) player.resume() else player.pause()
            updatePlayerUi()
            return
        }
        val embeddedTitle = state.queueItem?.let { it.name.ifBlank { it.originalName() } }.orEmpty()
        val retainedTitle = state.title.ifBlank { embeddedTitle }.ifBlank { titleForMedia(state.media) }
        loadPartyMedia(state.media, target, state.playing, retainedTitle)
    }

    private fun applyRemoteCommand(command: PartyPlaybackCommand) {
        if (!playbackSync.acceptRemote(command.raw)) return
        if (command.action in listOf("load", "play", "pause", "seek", "sync")) {
            pendingRetainedThaw = false
        }
        val player = mpvVideo
        when (command.action) {
            "load" -> command.media?.let {
                loadPartyMedia(it, 0.0, true, titleForMedia(it))
                showPlayerActivity(command.by, "play", "Resumed", titleForMedia(it))
            }
            "play" -> {
                val time = command.time ?: return
                if (player != null && abs(player.position() - time) > 2.0) player.seekTo(clampPlayerTime(time))
                desiredPlaying = true; player?.resume()
                showPlayerActivity(command.by, "play", "Resumed", clockForFeed(time))
            }
            "pause" -> {
                val time = command.time ?: return
                if (player != null && abs(player.position() - time) > 2.0) player.seekTo(clampPlayerTime(time))
                desiredPlaying = false; player?.pause()
                showPlayerActivity(command.by, "pause", "Paused", clockForFeed(time))
            }
            "seek" -> command.time?.let {
                player?.seekTo(clampPlayerTime(it))
                showPlayerActivity(command.by, "seek", "Seek", clockForFeed(it))
            }
            "sync" -> command.time?.let {
                player?.seekTo(clampPlayerTime(it)); desiredPlaying = command.playing == true
                if (desiredPlaying) player?.resume() else player?.pause()
                showPlayerActivity(command.by, "seek", "Synced", clockForFeed(it))
            }
        }
        // Every peer which accepted the winning epoch refreshes the retained snapshot.
        // This is state convergence only—never a rebroadcast user command/seek loop.
        if (command.action in listOf("load", "play", "pause", "seek", "sync")) {
            val retainedTime = when (command.action) {
                "load" -> 0.0
                else -> command.time ?: (player?.position() ?: 0.0)
            }
            val retainedPlaying = when (command.action) {
                "load", "play" -> true
                "pause" -> false
                "sync" -> command.playing == true
                else -> desiredPlaying
            }
            currentMedia?.let { media ->
                val epoch = command.raw.optJSONObject("_wp4")?.optJSONArray("epoch")
                    ?: playbackSync.currentEpoch()
                publishPlaybackSnapshot(media, retainedTime, retainedPlaying, epoch,
                    clockRunning = retainedPlaying)
            }
        }
        updatePlayerUi()
    }

    private fun userTogglePlayback() {
        val player = mpvVideo ?: return
        if (currentMedia == null) return
        if (player.loaded() && !player.isPaused()) userPausePlayback() else userPlayPlayback()
    }

    /**
     * Conversation-aware ducking: movie stays audible at full level in call silence, fades to
     * 45% after confirmed speech, then smoothly returns. It never pauses/seeks or publishes.
     */
    internal fun setVoiceCallSpeechDucking(duck: Boolean) {
        val player = mpvVideo ?: return
        if (voiceCallSpeechDucked == duck) return
        voiceCallSpeechDucked = duck
        if (duck) player.fadeVolume(45, 180L) else player.fadeVolume(100, 500L)
    }

    private fun userPlayPlayback() {
        val media = currentMedia ?: return
        pendingRetainedThaw = false
        desiredPlaying = true
        mpvVideo?.resume()
        val time = mpvVideo?.position() ?: 0.0
        val wp4 = playbackSync.localCommand()
        PartyTower.publishPlaybackCommand("play", time = time, user = true, wp4 = wp4)
        publishPlaybackSnapshot(media, time, true, wp4.getJSONArray("epoch"))
        showPlayerActivity(WpUser.me(this), "play", "Resumed", clockForFeed(time))
        updatePlayerUi()
    }

    private fun userPausePlayback() {
        val media = currentMedia ?: return
        pendingRetainedThaw = false
        desiredPlaying = false
        mpvVideo?.pause()
        val time = mpvVideo?.position() ?: 0.0
        val wp4 = playbackSync.localCommand()
        PartyTower.publishPlaybackCommand("pause", time = time, user = true, wp4 = wp4)
        publishPlaybackSnapshot(media, time, false, wp4.getJSONArray("epoch"), clockRunning = false)
        showPlayerActivity(WpUser.me(this), "pause", "Paused", clockForFeed(time))
        updatePlayerUi()
    }

    /** Lock-screen previous/next selects the real Room queue item and publishes it. */
    private fun userQueueStep(delta: Int) {
        if (delta != -1 && delta != 1) return
        val target = partyQueueIndex + delta
        if (target !in partyQueue.indices) return
        playQueueItem(target)
    }

    private fun userSeekBy(delta: Double) = userSeekTo((mpvVideo?.position() ?: 0.0) + delta)

    private fun userSeekTo(value: Double) {
        val media = currentMedia ?: return
        pendingRetainedThaw = false
        val target = clampPlayerTime(value)
        mpvVideo?.seekTo(target)
        val wp4 = playbackSync.localCommand()
        PartyTower.publishPlaybackCommand("seek", time = target, user = true, wp4 = wp4)
        publishPlaybackSnapshot(media, target, desiredPlaying, wp4.getJSONArray("epoch"),
            clockRunning = desiredPlaying)
        showPlayerActivity(WpUser.me(this), "seek", "Seek", clockForFeed(target))
        updatePlayerUi()
    }

    private fun clampPlayerTime(value: Double): Double {
        val duration = mpvVideo?.duration() ?: 0.0
        return value.coerceAtLeast(0.0).let { if (duration > .5) it.coerceAtMost(duration - .05) else it }
    }

    private fun updatePlayerUi() {
        if (!::partyPlayer.isInitialized) return
        val player = mpvVideo
        val media = currentMedia
        if (media == null || player == null) {
            partyPlayer.render(false, "", "", false, false, 0.0, 0.0,
                false, null, 0, 0, playerQuality)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val loaded = player.loaded()
        val error = player.error
        if (!error.isNullOrBlank() && !loaded && playerLoadIssued) {
            playerBufferingActive = false
            playerBufferPercent = 0
            playerLoading = "MPV error: ${error.take(60)}"
        } else if (loaded && playerBufferingActive && playerLoadIssued) {
            playerBufferPercent = 100
            playerBufferingActive = false
            playerBufferCompleteUntil = now + 900L
            playerLoading = null
        } else if (playerBufferingActive) {
            val elapsed = now - playerBufferStartedAt
            val actual = player.cachePct()
            val floor = if (media.type == "youtube" && playerBufferResolved) 45 else 1
            val target = when {
                !playerBufferResolved -> (1 + elapsed / 900L).toInt().coerceIn(1, 45)
                actual > 0 -> floor + ((100 - floor) * actual / 100)
                else -> (floor + elapsed / 180L).toInt().coerceAtMost(95)
            }
            playerBufferPercent = maxOf(playerBufferPercent, target.coerceIn(1, 99))
        }
        val position = player.position()
        val duration = player.duration()
        val isPlaying = desiredPlaying && !player.isPaused() && !player.ended()
        val isAudio = media.type == "mp3" ||
            (loaded && player.actualHeight() == 0 && player.audioCodec().isNotBlank())
        val bufferingPercent = when {
            playerBufferingActive -> playerBufferPercent.coerceIn(1, 99)
            now < playerBufferCompleteUntil -> 100
            player.buffering() -> player.cachePct().coerceIn(1, 100)
            else -> 0
        }
        refreshPlaybackCheckpoint()
        if (pendingRetainedThaw && loaded && desiredPlaying && PartyTower.isConnected()) {
            // Empty Room ka clock load ke dauran frozen raha; first ready entrant ab usay chala raha hai.
            pendingRetainedThaw = false
            publishPlaybackSnapshot(media, player.rawPosition(), true, clockRunning = true)
        }
        partyPlayer.render(true, currentMediaTitle, media.type, isPlaying, player.isMuted(),
            position, duration, isAudio, playerLoading, bufferingPercent,
            player.audioTracks().size, playerQuality)
        fullscreenControls?.tick()

        if (lastNotificationTitle != currentMediaTitle || lastNotificationPlaying != isPlaying) {
            lastNotificationTitle = currentMediaTitle; lastNotificationPlaying = isPlaying
            PartyPlayerService.update(applicationContext, currentMediaTitle, isPlaying)
        }
        if (player.ended() && !endedHandled) {
            endedHandled = true
            advancePartyQueue()
        }
    }

    private fun advancePartyQueue() {
        if (partyQueueIndex + 1 < partyQueue.size) {
            partyQueueIndex++
            PartyTower.publishQueue(partyQueue, partyQueueIndex)
            val next = partyQueue[partyQueueIndex]
            playMediaEverywhere(PartyPlaybackMedia(next.type, next.url, next.videoId),
                next.name.ifBlank { next.originalName() })
            renderPartyPlaylist()
        } else {
            partyQueueIndex = partyQueue.size
            PartyTower.publishQueue(partyQueue, partyQueueIndex)
            desiredPlaying = false
            pendingRetainedThaw = false
            mpvVideo?.pause()
            currentMedia?.let {
                publishPlaybackSnapshot(it, mpvVideo?.duration() ?: 0.0, false,
                    clockRunning = false)
            }
            Toast.makeText(this, "📋 Playlist khatam", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseInlineAudioTrack() {
        val player = mpvVideo ?: return
        val tracks = player.audioTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Audio track abhi tayyar nahi", Toast.LENGTH_SHORT).show(); return
        }
        if (tracks.size == 1) {
            Toast.makeText(this, "Is media mein sirf ek audio track hai", Toast.LENGTH_SHORT).show(); return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Audio language / track")
            .setSingleChoiceItems(tracks.mapIndexed { i, t -> "${i + 1}. ${t.label}" }.toTypedArray(),
                tracks.indexOfFirst { it.selected }) { selectedDialog, which ->
                player.selectAudio(tracks[which].id) { ok ->
                    Toast.makeText(this, if (ok) "Audio: ${tracks[which].label}" else "Audio switch confirm nahi hua",
                        Toast.LENGTH_SHORT).show(); updatePlayerUi()
                }
                selectedDialog.dismiss()
            }.setNegativeButton("Close", null).create()
        dialog.show()
        styleRoomDialog(dialog)
    }

    private fun chooseInlineQuality() {
        if (currentMedia?.type != "youtube") return
        val values = playerQualities.ifEmpty { listOf(144, 240, 360, 480, 720, 1080) }
        val dialog = AlertDialog.Builder(this).setTitle("Video quality · this phone only")
            .setSingleChoiceItems(values.map { "${it}p" }.toTypedArray(), values.indexOf(playerQuality)) { selectedDialog, which ->
                switchPlayerQuality(values[which]); selectedDialog.dismiss()
            }.setNegativeButton("Close", null).create()
        dialog.show()
        styleRoomDialog(dialog)
    }

    private fun switchPlayerQuality(height: Int) {
        val media = currentMedia?.takeIf { it.type == "youtube" } ?: return
        val position = mpvVideo?.position() ?: 0.0
        val playing = desiredPlaying && mpvVideo?.isPaused() == false
        playerQuality = height
        prefs.edit().putInt("player_quality", height).apply()
        loadPartyMedia(media, position, playing, currentMediaTitle, qualityOverride = height)
    }

    private fun enterPlayerFullscreen() {
        val player = mpvVideo ?: return
        if (playerFullscreen || currentMedia == null || !::partyPlayer.isInitialized) return
        val parent = partyPlayer.parent as? ViewGroup ?: return
        inlinePlayerParent = parent
        inlinePlayerIndex = parent.indexOfChild(partyPlayer)
        inlinePlayerLayout = partyPlayer.layoutParams
        parent.removeView(partyPlayer)
        playerFullscreen = true
        roomBackdrop.setPadding(0, 0, 0, 0)
        roomBackdrop.addView(partyPlayer, FrameLayout.LayoutParams(-1, -1))
        partyPlayer.setFullscreenHost(true)
        player.setFullscreen(true)
        val controls = MpvFullscreenControls(this, player,
            send = { command ->
                when {
                    command == "toggle" || command == "playpause" -> userTogglePlayback()
                    command == "play" -> userPlayPlayback()
                    command == "pause" -> userPausePlayback()
                    command.startsWith("seekrel:") -> command.substringAfter(':').toDoubleOrNull()?.let(::userSeekBy)
                    command.startsWith("seekabs:") -> command.substringAfter(':').toDoubleOrNull()?.let(::userSeekTo)
                    command.startsWith("quality:") -> command.substringAfter(':').toIntOrNull()?.let(::switchPlayerQuality)
                }
            },
            exit = { exitPlayerFullscreen() },
            sourceTitle = { currentMediaTitle },
            isYoutube = { currentMedia?.type == "youtube" },
            isAudio = {
                currentMedia?.type == "mp3" ||
                    (player.loaded() && player.actualHeight() == 0 && player.audioCodec().isNotBlank())
            },
            quality = { playerQuality }, qualities = { playerQualities })
        fullscreenControls = controls
        partyPlayer.addView(controls, FrameLayout.LayoutParams(-1, -1))
        roomBackdrop.requestApplyInsets()
    }

    private fun exitPlayerFullscreen() {
        if (!playerFullscreen || !::partyPlayer.isInitialized) return
        val controls = fullscreenControls
        fullscreenControls = null
        if (controls != null) {
            controls.release()
            partyPlayer.removeView(controls)
        }
        mpvVideo?.setFullscreen(false)
        partyPlayer.setFullscreenHost(false)
        (partyPlayer.parent as? ViewGroup)?.removeView(partyPlayer)
        val parent = inlinePlayerParent
        if (parent != null) {
            val index = inlinePlayerIndex.coerceIn(0, parent.childCount)
            parent.addView(partyPlayer, index, inlinePlayerLayout)
        }
        inlinePlayerParent = null; inlinePlayerLayout = null; inlinePlayerIndex = -1
        playerFullscreen = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        roomBackdrop.requestApplyInsets()
        updatePlayerUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playerFullscreen) fullscreenControls?.immerse()
    }

    private fun showPlayerActivity(name: String, kind: String, word: String, detail: String) {
        val json = org.json.JSONObject().put("name", name.ifBlank { "Someone" }).put("kind", kind)
            .put("t1", word).put("t2", detail).put("ms", 5000L).toString()
        fullscreenControls?.activity(json)
    }

    private fun clockForFeed(value: Double): String {
        val seconds = value.coerceAtLeast(0.0).toInt()
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun openYouTubeSearch() {
        YouTubeSearchDialog(this, palette, ::playYouTubeSearchResult).show()
    }

    /** Original behavior: tapped result queue ke end mein add aur sab peers par play. */
    private fun playYouTubeSearchResult(result: YouTubeSearch.Item): Boolean {
        if (!PartyTower.isConnected()) {
            Toast.makeText(this, "Tower connect hone do", Toast.LENGTH_SHORT).show()
            return false
        }
        val url = "https://www.youtube.com/watch?v=${result.videoId}"
        val media = PartyPlaybackMedia("youtube", url, result.videoId)
        val item = PartyQueueItem(
            id = "q" + System.currentTimeMillis().toString(36),
            type = "youtube",
            url = url,
            videoId = result.videoId,
            label = result.title.take(120),
            title = result.title.take(120),
            by = WpUser.me(this)
        )
        partyQueue.add(item)
        partyQueueIndex = partyQueue.lastIndex
        PartyTower.publishQueue(partyQueue, partyQueueIndex)
        renderPartyPlaylist()
        playMediaEverywhere(media, item.originalName())
        return true
    }

    private fun styleRoomDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(GradientDrawable(
            GradientDrawable.Orientation.TL_BR, palette.menu).apply {
            cornerRadius = dp(18).toFloat(); setStroke(dp(1), palette.panelStroke)
        })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(palette.focus)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.accentText)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(palette.accentText)
    }

    private fun glassBox(radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.panel).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1f), palette.panelStroke)
        }

    private fun themedInput(radius: Float, focused: Boolean = false): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, palette.input).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(if (focused) 2f else 1f), if (focused) palette.focus else palette.inputStroke)
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    override fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable =
        roundBox(fill, stroke, radiusDp.toFloat(), strokeDp.toFloat())

    private fun roundBox(fill: Int, stroke: Int, radius: Float, strokeDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (strokeDp > 0f) setStroke(dp(strokeDp), stroke)
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
    private val theme: WpTheme
) : FrameLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, theme.page.copyOf())
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
            theme.glows.forEach { glow ->
                radial(c, w * glow.x, h * glow.y,
                    maxOf(w, h) * glow.radius, glow.color, w, h)
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
