package app.party.wpnative

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.media.ExifInterface
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.URL

/**
 * PEHLA PAGE: Join screen (website-v61 wali #join-screen ka exact design).
 * Themes (6) + bubble styles (12) + tower dropdown + save (SharedPreferences).
 */
class MainActivity : Activity() {

    private val themes get() = WpThemes.all
    private val bubbles get() = WpBubbles.all
    // Approved compact sample colors are brand identity, not theme colors.
    private val approvedBrandGradient = intArrayOf(
        Color.parseColor("#fbf5ff"), Color.parseColor("#e68aff"),
        Color.parseColor("#8f7cff"), Color.parseColor("#5de8ff")
    )
    private val approvedPlusGradient = intArrayOf(
        Color.parseColor("#64eaff"), Color.parseColor("#a776ff"), Color.parseColor("#ff6fcf")
    )

    private val towers = arrayOf("🗼 EMQX (tez!)", "🗼 HiveMQ", "🗼 tyckr")
    private var towerIndex = 0
    private var themeIndex = WpThemes.DEFAULT_INDEX   // Lobby Neon for a brand-new user
    private var bubbleIndex = WpBubbles.LOBBY_NEON_INDEX

    // Avatar
    private var avatarBitmap: Bitmap? = null
    private var avatarType = "letter"   // letter / upload / dicebear
    private var avatarData: String? = null
    private var avatarLetter: TextView? = null
    private var avatarImage: ImageView? = null
    private var avatarReq = 0
    private val REQ_PHOTO = 101

    private lateinit var rootView: FrameLayout
    private lateinit var nameInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var towerBtn: Button
    private lateinit var titleView: GradientText
    private lateinit var plusView: TextView
    private lateinit var brandMetaView: TextView
    private lateinit var joinBtn: Button
    private lateinit var barTitle: TextView
    private lateinit var joinCard: LinearLayout
    private lateinit var badgeView: TextView
    private lateinit var avatarFrame: FrameLayout
    private lateinit var avatarRing: View
    private lateinit var themeBarView: LinearLayout
    private lateinit var themeBarSubtitle: TextView
    private lateinit var digitalPanel: LinearLayout
    private lateinit var digitalHeader: TextView
    private lateinit var digitalReady: TextView
    private lateinit var digitalLine: View
    private val featureChips = mutableListOf<TextView>()
    private val dotViews = mutableListOf<View>()

    private val prefs by lazy { getSharedPreferences("wp_native", Context.MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPrefs()
        askNotifyPermission()
        setContentView(buildJoinScreen())
        applyTheme()
        restoreAvatar()
        // App khulte hi latest selected photo/cartoon Friend Code directory mein publish ho.
        FirebaseChat.publishFriendProfile(this)
        saveFcmToken()
        BgMsgService.start(this)      // app band hone pe bhi notification
        MediaCleanup.runIfDue(this)   // 3 din purani photo/voice Firestore+phone se hat jayen
    }

    override fun onResume() {
        super.onResume()
        CallMiniBar.attach(this)
    }

    /** Android 13+ par notification ki ijazat (push ke liye zaroori). */
    private fun askNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    /** Apna FCM token Firebase mein save karo — doosra phone isi par push bhejega. */
    private fun saveFcmToken() {
        try {
            if (!FirebaseChat.isReady(this)) return
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { FirebaseChat.saveToken(this, WpUser.me(this), it) }
        } catch (t: Throwable) { }
    }

    private fun loadPrefs() {
        towerIndex = prefs.getInt("tower", 0).coerceIn(0, towers.size - 1)
        // Defaults apply only when no preference exists; every saved user choice is retained.
        themeIndex = prefs.getInt("theme", WpThemes.DEFAULT_INDEX).coerceIn(0, themes.size - 1)
        bubbleIndex = prefs.getInt("bubble", WpBubbles.LOBBY_NEON_INDEX).coerceIn(0, bubbles.size - 1)
        val migrated = WpThemes.migrateCoupling(prefs, themeIndex, bubbleIndex)
        themeIndex = migrated.theme
        bubbleIndex = migrated.bubble
    }

    private fun savePrefs() {
        val e = prefs.edit()
        e.putInt("tower", towerIndex)
        e.putInt("theme", themeIndex)
        e.putInt("bubble", bubbleIndex)
        if (::nameInput.isInitialized) e.putString("name", nameInput.text.toString())
        if (::roomInput.isInitialized) e.putString("room", roomInput.text.toString())
        e.putString("avatarType", avatarType)
        e.putString("avatarData", if (avatarType == "letter") null else avatarData)
        e.apply()
    }

    private fun restoreAvatar() {
        when (prefs.getString("avatarType", "letter")) {
            "upload" -> {
                val s = prefs.getString("avatarData", null)
                if (s != null) {
                    try {
                        val bytes = Base64.decode(s, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) setAvatar(bmp, "upload", s, save = false)
                    } catch (e: Exception) { /* letter dikhega */ }
                }
            }
            "dicebear" -> {
                val url = prefs.getString("avatarData", null)
                if (url != null) loadCartoonUrl(url, showError = false)
            }
        }
    }

    private fun buildJoinScreen(): View {
        rootView = FrameLayout(this)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true

        joinCard = LinearLayout(this).apply {
            clipChildren = false
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundBox(Color.argb(120, 12, 10, 28), Color.argb(70, 167, 139, 250), 24, 1)
        }

        // Logo (5 rangeeli bars) + title
        val logo = LogoView(this)
        joinCard.addView(logo, lp(dp(44), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        android.animation.ObjectAnimator.ofFloat(logo, "translationY", 0f, -dp(10).toFloat(), 0f).apply {
            duration = 2000
            repeatCount = android.animation.ObjectAnimator.INFINITE
            start()
        }
        // Approved compact brand lock-up: aesthetic gradient words + separate digital plus.
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = "Smart Party Plus"
        }
        titleView = GradientText(this).apply {
            text = "Smart Party"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 32f
            gravity = Gravity.CENTER
            setSingleLine(true)
            letterSpacing = -0.03f
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.argb(31, 185, 82, 255))
            setAutoSizeTextTypeUniformWithConfiguration(25, 32, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        brandRow.addView(titleView, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        plusView = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            rotation = 2f
            elevation = dp(3).toFloat()
        }
        brandRow.addView(plusView, lp(dp(34), dp(34)).apply { leftMargin = dp(7); topMargin = dp(2) })
        joinCard.addView(brandRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            topMargin = dp(2)
        })

        brandMetaView = TextView(this).apply {
            text = "WATCH  •  LISTEN  •  TOGETHER"
            textSize = 7f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = .14f
        }
        joinCard.addView(brandMetaView, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(13)).apply {
            bottomMargin = dp(3)
        })

        // Badge (glow ke saath)
        badgeView = TextView(this).apply {
            text = "Feel Special With Me 🥰 💗"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#f9a8d4"))
            setPadding(dp(18), dp(7), dp(18), dp(7))
            background = LayerDrawable(arrayOf(
                GlowDrawable(Color.argb(89, 244, 114, 182), dp(9).toFloat(), dp(20).toFloat()),
                roundBox(Color.argb(46, 244, 114, 182), Color.argb(115, 244, 114, 182), 20, 1)
            ))
        }
        joinCard.addView(badgeView, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); bottomMargin = dp(12) })

        // Avatar + buttons
        joinCard.addView(buildAvatarPick(), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        // Name
        nameInput = styledEdit("Apna name likho...", 20, 17f)
        joinCard.addView(nameInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        // Room
        roomInput = styledEdit("Room name... (doston se poocho!)", 20, 16f)
        joinCard.addView(roomInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        // Restore saved name/room (watchers se pehle)
        val savedName = prefs.getString("name", "") ?: ""
        nameInput.setText(if (savedName.isNotBlank()) savedName else WpUser.savedName(this))
        roomInput.setText(prefs.getString("room", "") ?: "")
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderAvatar(); savePrefs()
                // Chat wali pehchaan bhi yahi naam -> Inbox/Chat isi se chatId banate hain
                val n = s?.toString()?.trim().orEmpty()
                if (n.isNotBlank()) WpUser.setName(this@MainActivity, n)
            }
        })
        roomInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { savePrefs() }
        })

        // Tower dropdown (tap karo, list khulegi)
        towerBtn = Button(this).apply {
            text = towerLabel()
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            background = roundBox(Color.argb(200, 12, 10, 28), Color.argb(60, 255, 255, 255), 14, 2)
            setOnClickListener { openTowerPicker() }
        }
        joinCard.addView(towerBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        // Theme bar (tap = theme + bubble picker)
        joinCard.addView(buildThemeBar(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // Join button
        joinBtn = Button(this).apply {
            text = "🥳 Party Me Enter Ho"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 18f
            setOnClickListener {
                val n = nameInput.text.toString().trim()
                val r = roomInput.text.toString().trim()
                if (n.isEmpty() || r.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Naam aur room name likho", Toast.LENGTH_SHORT).show()
                } else {
                    // Display naam + stable Friend Code profile dono publish/update karo.
                    WpUser.setName(this@MainActivity, n)
                    FirebaseChat.publishFriendProfile(this@MainActivity)
                    FirebaseChat.setPresence(this@MainActivity, n, true)
                    // Join ke baad next page: Messages (Inbox)
                    startActivity(Intent(this@MainActivity, InboxActivity::class.java))
                }
            }
        }
        joinCard.addView(joinBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })

        // Approved Party-Lobby inspired digital matrix. Exactly two compact rows (4 + 3),
        // replacing the old wrapping pills without making the first page taller.
        joinCard.addView(buildDigitalFeatures(),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })

        renderAvatar()

        scroll.addView(joinCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(8), dp(16), dp(24))
        })
        rootView.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return rootView
    }

    private fun towerLabel(): String = towers[towerIndex] + "   ▾"

    private fun openTowerPicker() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("🗼 Tower chuno")
            .setItems(arrayOf<CharSequence>(*towers)) { _, i ->
                towerIndex = i
                towerBtn.text = towerLabel()
                savePrefs()
            }
            .create()
        dialog.show()
        val t = themes[themeIndex]
        dialog.window?.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, t.menu).apply {
            cornerRadius = dp(18).toFloat(); setStroke(dp(1), t.panelStroke)
        })
    }

    /** Theme + bubble ka rang poore page par lagata hai. */
    private fun applyTheme() {
        val t = themes[themeIndex]
        val b = bubbles[bubbleIndex]
        rootView.background = WpPageDrawable(t, resources.displayMetrics.density)
        joinCard.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, t.joinCard).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), t.joinStroke)
        }
        // Keep the wordmark pixel-faithful to the approved sample on every theme.
        titleView.grad = approvedBrandGradient
        titleView.applyShader()
        plusView.setTextColor(hex("#06121c"))
        plusView.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, approvedPlusGradient
        ).apply {
            cornerRadii = floatArrayOf(
                dp(10).toFloat(), dp(10).toFloat(), dp(4).toFloat(), dp(4).toFloat(),
                dp(10).toFloat(), dp(10).toFloat(), dp(4).toFloat(), dp(4).toFloat()
            )
            setStroke(dp(1), hex("#a668e8ff"))
        }
        brandMetaView.setTextColor(hex("#6c6b96"))
        joinBtn.setTextColor(t.buttonText)
        joinBtn.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if (t.key == "purple") t.j else t.fill2).apply {
            cornerRadius = dp(14).toFloat()
        }
        applyMainInput(nameInput, nameInput.hasFocus())
        applyMainInput(roomInput, roomInput.hasFocus())
        towerBtn.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, t.input).apply {
            cornerRadius = dp(14).toFloat(); setStroke(dp(1), t.inputStroke)
        }
        themeBarView.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(t.chip, t.soft)).apply {
            cornerRadius = dp(14).toFloat(); setStroke(dp(1), t.inputStroke)
        }
        themeBarSubtitle.setTextColor(t.accentText)
        digitalPanel.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, t.menu).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), withAlpha(t.focus, 66))
        }
        digitalHeader.setTextColor(withAlpha(t.accentText, 205))
        digitalReady.setTextColor(hex("#76ffd8"))
        featureChips.forEachIndexed { index, chip ->
            chip.setTextColor(hex("#e8e5fa"))
            chip.background = digitalChipBackground(t, index)
        }
        digitalLine.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, withAlpha(t.focus, 100),
                withAlpha(t.j.last(), 100), Color.TRANSPARENT))
        badgeView.setTextColor(t.accentText)
        badgeView.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(withAlpha(t.j.first(), 46), withAlpha(t.j.last(), 46))).apply {
            cornerRadius = dp(20).toFloat(); setStroke(dp(1), withAlpha(t.focus, 150))
        }
        avatarFrame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, b.own).apply {
            shape = GradientDrawable.OVAL; setStroke(dp(3), withAlpha(t.focus, 115))
        }
        avatarRing.background = InsetDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(3), t.focus)
        }, dp(2))
        barTitle.text = t.name.replace(" (default)", "") + " · " + b.short
        val preview = intArrayOf(t.bg[1], b.own[0], b.other[0])
        for ((i, v) in dotViews.withIndex()) {
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(preview[i]) }
        }
    }

    private fun applyMainInput(view: EditText, focused: Boolean) {
        val t = themes[themeIndex]
        view.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, t.input).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(if (focused) 2 else 1), if (focused) t.focus else t.inputStroke)
        }
        view.setHintTextColor(withAlpha(t.accentText, 180))
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    private fun buildAvatarPick(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Avatar circle: gradient + letter/emoji, uske upar photo/cartoon image (circle mein clip)
        avatarFrame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#f472b6"), hex("#a78bfa"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), Color.argb(77, 255, 255, 255))
            }
        }
        val letterView = TextView(this).apply {
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        avatarLetter = letterView
        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
        }
        avatarImage = imageView
        // Photo/letter ko 3dp andar rakho, taake ring unke BAHAR bilkul edge par aaye (website jaisa)
        avatarFrame.addView(letterView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        avatarFrame.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        // Website wala 3px ka pink-lavender ring (border), photo ke upar bhi dikhe
        avatarRing = View(this).apply {
            background = InsetDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), Color.argb(235, 240, 171, 252))
                },
                dp(2)
            )
        }
        avatarFrame.addView(avatarRing, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(avatarFrame, lp(dp(84), dp(84)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("📷 Photo", "#10b981", "#14b8a6") { openPhotoPicker() },
            lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        row.addView(smallButton("🎲 Cartoon", "#f59e0b", "#ef4444") { loadCartoon() },
            lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        col.addView(row, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return col
    }

    private fun smallButton(label: String, c1: String, c2: String, onTap: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(hex(c1), hex(c2)))
            .apply { cornerRadius = dp(20).toFloat() }
        setOnClickListener { onTap() }
    }

    /** Avatar dikhao: photo/cartoon hai to image, warna naam ka pehla harf. */
    private fun renderAvatar() {
        val bmp = avatarBitmap
        val letter = avatarLetter ?: return
        val img = avatarImage ?: return
        if (bmp != null) {
            img.setImageBitmap(bmp)
            img.visibility = View.VISIBLE
            letter.visibility = View.GONE
        } else {
            img.setImageDrawable(null)
            img.visibility = View.GONE
            letter.visibility = View.VISIBLE
            val name = if (::nameInput.isInitialized) nameInput.text.toString().trim() else ""
            letter.text = (name.firstOrNull()?.toString() ?: "😎").uppercase()
        }
    }

    private fun setAvatar(bmp: Bitmap?, type: String, data: String?, save: Boolean = true) {
        avatarBitmap = bmp
        avatarType = if (bmp == null) "letter" else type
        avatarData = data
        renderAvatar()
        if (save) {
            savePrefs()
            // DP badalte hi doston ko purane deterministic cartoon ki jagah nayi asli DP mile.
            FirebaseChat.publishFriendProfile(this)
            if (PartyTower.hasLiveSession()) PartyTower.refreshPresence()
        }
    }

    private fun nextAvatarReq(): Int {
        avatarReq += 1
        return avatarReq
    }

    /** 📷 Photo: phone ki gallery khulti hai. */
    private fun openPhotoPicker() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "Photo chuno"), REQ_PHOTO)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PHOTO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val myReq = nextAvatarReq()
            Thread {
                val bmp = try { decodePhoto(uri) } catch (e: Exception) { null }
                val enc = if (bmp != null) try { encodeBitmap(bmp) } catch (e: Exception) { null } else null
                runOnUiThread {
                    if (myReq != avatarReq) return@runOnUiThread
                    if (bmp != null) setAvatar(bmp, "upload", enc)
                    else Toast.makeText(this@MainActivity, "Photo load nahi hui", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    private fun encodeBitmap(b: Bitmap): String {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.DEFAULT)
    }

    /** Photo ko chota karke, center se square crop karke, phone ki rotation theek karke return karta hai. */
    private fun decodePhoto(uri: android.net.Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxSide <= 0) return null
        var sample = 1
        while (maxSide / (sample * 2) >= 256) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orient = try {
            contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val rot: Float = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (rot != 0f) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot) }, true)
        } else src

        val s = minOf(upright.width, upright.height)
        val x = (upright.width - s) / 2
        val y = (upright.height - s) / 2
        val crop = Bitmap.createBitmap(upright, x, y, s, s)
        return Bitmap.createScaledBitmap(crop, 256, 256, true)
    }

    /** 🎲 Cartoon: DiceBear fun-emoji ka random avatar (website wala hi style). */
    private fun loadCartoon() {
        val seed = java.util.UUID.randomUUID().toString().take(8)
        loadCartoonUrl("https://api.dicebear.com/9.x/fun-emoji/png?seed=$seed&size=256", showError = true)
    }

    private fun loadCartoonUrl(url: String, showError: Boolean) {
        val myReq = nextAvatarReq()
        Thread {
            val bmp = try { URL(url).openStream().use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
            runOnUiThread {
                if (myReq != avatarReq) return@runOnUiThread
                if (bmp != null) setAvatar(bmp, "dicebear", url)
                else if (showError) Toast.makeText(this@MainActivity, "Cartoon load nahi hua, net check karo", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun buildThemeBar(): LinearLayout {
        themeBarView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBox(Color.argb(150, 12, 10, 28), Color.argb(56, 255, 255, 255), 14, 1)
            setOnClickListener { openPicker() }
        }
        themeBarView.addView(TextView(this).apply { text = "🎨"; textSize = 16f; setPadding(0, 0, dp(9), 0) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        barTitle = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        texts.addView(barTitle)
        themeBarSubtitle = TextView(this).apply {
            text = "theme aur bubble badlo"
            textSize = 11f
            setTextColor(themes[themeIndex].accentText)
        }
        texts.addView(themeBarSubtitle)
        themeBarView.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 3 rang ke dots (theme ke rang)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dotViews.clear()
        for (i in 0 until 3) {
            val d = View(this)
            dotViews.add(d)
            dots.addView(d, lp(dp(12), dp(12)).apply { rightMargin = dp(4) })
        }
        themeBarView.addView(dots)
        themeBarView.addView(TextView(this).apply { text = "›"; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(8), 0, 0, 0) })
        return themeBarView
    }

    /** Theme + bubble picker: website wali cards (theme ka swatch + bubble ka chat sample). */
    private fun openPicker() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(6))
        }
        val scroll = ScrollView(this).apply { addView(box) }
        var activeDialog: AlertDialog? = null

        fun header(title: String, sub: String) {
            box.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            box.addView(TextView(this@MainActivity).apply {
                text = sub
                textSize = 11f
                setTextColor(themes[themeIndex].accentText)
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        fun card(content: View, label: String, selected: Boolean, onTap: () -> Unit): LinearLayout {
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                val current = themes[themeIndex]
                background = roundBox(
                    if (selected) withAlpha(current.focus, 50) else current.soft,
                    if (selected) current.focus else current.itemStroke,
                    14, if (selected) 2 else 1
                )
                addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
                addView(TextView(this@MainActivity).apply {
                    text = (if (selected) "✓ " else "") + label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                setOnClickListener { onTap() }
            }
        }

        fun grid(items: List<View>) {
            var i = 0
            while (i < items.size) {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                for (j in 0 until 2) {
                    val idx = i + j
                    if (idx < items.size) {
                        row.addView(items[idx], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                    } else {
                        row.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
                    }
                }
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                i += 2
            }
        }

        fun themeSwatch(t: WpTheme): View = View(this@MainActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, swatchColors(t))
                .apply { cornerRadius = dp(10).toFloat() }
        }

        fun chip(label: String, colors: IntArray, txt: Int): TextView = TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(txt)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
                .apply { cornerRadius = dp(12).toFloat() }
        }

        fun bubbleSample(b: WpBubbleTheme, style: Int): View = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val other = chip("unka", b.other, b.otherText).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                background = WpBubbleDrawable(b.other, false, resources.displayMetrics.density,
                    b.otherGlow, b.edge ?: Color.argb(66, 255, 255, 255), true,
                    if (style == 0 || style >= 8) 125f else 135f)
            }
            val own = chip("mera", b.own, b.ownText).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                background = WpBubbleDrawable(b.own, true, resources.displayMetrics.density,
                    if (style == 1) Color.TRANSPARENT else b.ownGlow,
                    if (style in 2..7) Color.argb(56, 255, 255, 255) else null,
                    style != 1, if (style == 0 || style >= 8) 110f else 135f)
            }
            addView(other, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) })
            addView(own, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        }

        fun render() {
            box.removeAllViews()
            header("🎨 Theme chuno", "(poora rang badalta hai)")
            val tCards = themes.mapIndexed { i, t ->
                card(themeSwatch(t), t.name.replace(" (default)", ""), i == themeIndex) {
                    val selected = WpThemes.select(prefs, themeIndex, bubbleIndex, i)
                    themeIndex = selected.theme
                    bubbleIndex = selected.bubble
                    applyTheme()
                    savePrefs()
                    activeDialog?.window?.setBackgroundDrawable(GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, themes[themeIndex].menu).apply {
                        cornerRadius = dp(20).toFloat(); setStroke(dp(1), themes[themeIndex].panelStroke)
                    })
                    render()
                }
            }
            grid(tCards)

            header("💬 Bubble style", "(sirf chat ke bubble · farq darje mein)")
            val bCards = bubbles.mapIndexed { i, b ->
                card(bubbleSample(b, i), b.short, i == bubbleIndex) {
                    bubbleIndex = i
                    applyTheme()
                    savePrefs()
                    render()
                }
            }
            grid(bCards)
        }
        render()

        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("✓ Theek hai, save karo", null)
            .create()
        activeDialog = dialog
        dialog.show()
        val t = themes[themeIndex]
        dialog.window?.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, t.menu).apply {
            cornerRadius = dp(20).toFloat(); setStroke(dp(1), t.panelStroke)
        })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(t.focus)
    }

    private fun styledEdit(hint: String, maxLen: Int, size: Float): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(themes[themeIndex].accentText)
        setTextColor(Color.WHITE)
        textSize = size
        gravity = Gravity.CENTER
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(maxLen))
        setPadding(dp(18), 0, dp(18), 0)
        background = roundBox(Color.argb(200, 12, 10, 28), Color.argb(60, 255, 255, 255), 14, 2)
        setOnFocusChangeListener { view, focused -> applyMainInput(view as EditText, focused) }
    }

    private fun buildDigitalFeatures(): LinearLayout {
        featureChips.clear()
        digitalPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipChildren = false
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        digitalHeader = TextView(this).apply {
            text = "PARTY SYSTEMS  //  DIGITAL MATRIX"
            textSize = 7f
            includeFontPadding = false
            setSingleLine(true)
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = .08f
        }
        head.addView(digitalHeader, LinearLayout.LayoutParams(0, dp(12), 1f))
        digitalReady = TextView(this).apply {
            text = "● READY"
            textSize = 7f
            includeFontPadding = false
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = .06f
        }
        head.addView(digitalReady, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(12)))
        digitalPanel.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))

        fun chip(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 7.5f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setPadding(dp(3), 0, dp(3), 0)
            contentDescription = label.replace("▶", "YouTube").replace("♫", "Media")
            featureChips.add(this)
        }

        fun row(labels: List<String>): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            labels.forEachIndexed { index, label ->
                addView(chip(label), LinearLayout.LayoutParams(0, dp(27), 1f).apply {
                    if (index > 0) leftMargin = dp(4)
                })
            }
        }

        digitalPanel.addView(row(listOf("▶ YOUTUBE", "♫ MP4/MP3", "☷ PLAYLIST", "◎ DP")),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)).apply { topMargin = dp(4) })
        digitalPanel.addView(row(listOf("↩ SWIPE REPLY", "⌁ LIVE SYNC", "⌂ ROOMS")),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)).apply { topMargin = dp(4) })
        digitalLine = View(this)
        digitalPanel.addView(digitalLine, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
        })
        return digitalPanel
    }

    private fun digitalChipBackground(t: WpTheme, index: Int): GradientDrawable {
        // t.chip is deliberately translucent; forcing its alpha to 235 turned Night Purple
        // almost solid white. Use dark surface RGBs and keep the approved sample exact there.
        val fill = if (t.key == "purple") {
            intArrayOf(hex("#eb131231"), hex("#eb050c1d"))
        } else {
            intArrayOf(withAlpha(t.input.first(), 235), withAlpha(t.page.last(), 235))
        }
        val stroke = if (t.key == "purple") hex("#318175c7")
            else withAlpha(if (index % 2 == 0) t.focus else t.j.last(), 58)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, fill).apply {
            cornerRadii = floatArrayOf(
                dp(5).toFloat(), dp(5).toFloat(), dp(10).toFloat(), dp(10).toFloat(),
                dp(5).toFloat(), dp(5).toFloat(), dp(10).toFloat(), dp(10).toFloat()
            )
            setStroke(dp(1), stroke)
        }
    }

    private fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(strokeDp), stroke)
        }

    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    private fun hex(s: String): Int = Color.parseColor(s)

    private fun cols(vararg c: String): IntArray = IntArray(c.size) { hex(c[it]) }

    /** Website ke theme card ka swatch (Night Purple aur Champagne Gold ke alag rang). */
    private fun swatchColors(t: WpTheme): IntArray = when (t.key) {
        "purple" -> cols("#3a3170", "#241f4d")
        else -> t.fill2
    }

    // ---------- custom views ----------

    /** Website ka logo: 5 rangeeli bars, CSS wpWave jaisi wave animation. */
    inner class LogoView(ctx: Context) : View(ctx) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bars = listOf(
            floatArrayOf(2.4f, 8f, 13f, 0f), floatArrayOf(6.7f, 5f, 16f, 1f),
            floatArrayOf(11f, 2.6f, 18.4f, 2f), floatArrayOf(15.3f, 6.4f, 14.6f, 1f),
            floatArrayOf(19.6f, 9.6f, 11.4f, 0f)
        )
        private val colors = intArrayOf(hex("#54e8ff"), hex("#8b72ff"), hex("#ff5ebc"))
        private val delays = floatArrayOf(0f, 150f, 300f, 450f, 600f)
        private var t = 0f
        private val anim = android.animation.ValueAnimator.ofFloat(0f, 1800f).apply {
            duration = 1800
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            anim.start()
        }

        override fun onDetachedFromWindow() {
            anim.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = dp(2).toFloat()
            val ring = RectF(inset, inset, width - inset, height - inset)
            ringPaint.strokeWidth = dp(1).toFloat().coerceAtLeast(1f)
            ringPaint.color = withAlpha(colors[0], 115)
            canvas.save()
            canvas.rotate(t / 18f, width / 2f, height / 2f)
            canvas.drawArc(ring, -72f, 214f, false, ringPaint)
            ringPaint.color = withAlpha(colors[2], 105)
            canvas.drawArc(ring, 132f, 142f, false, ringPaint)
            canvas.restore()

            val inner = RectF(dp(7).toFloat(), dp(7).toFloat(),
                width - dp(7).toFloat(), height - dp(7).toFloat())
            ringPaint.color = withAlpha(colors[1], 62)
            canvas.drawOval(inner, ringPaint)
            dotPaint.color = colors[0]
            canvas.drawCircle(width * .29f, height * .13f, dp(2).toFloat(), dotPaint)

            // Bars stay inside the orbit; independent paints prevent redraw alpha/style leaks.
            val s = width / 32f
            val dx = (width - 24f * s) / 2f
            val dy = (height - 24f * s) / 2f
            val r = 1.35f * s
            barPaint.alpha = 255
            barPaint.style = Paint.Style.FILL
            barPaint.shader = null
            for ((i, b) in bars.withIndex()) {
                val ph = (t + delays[i]) % 1800f
                val f = if (ph <= 900f) ph / 900f else (1800f - ph) / 900f
                val e = (1f - Math.cos(f * Math.PI).toFloat()) / 2f
                val scale = 0.45f + 0.55f * e
                val bottom = b[1] + b[2]
                val top = bottom - b[2] * scale
                barPaint.color = colors[b[3].toInt()]
                canvas.drawRoundRect(dx + b[0] * s, dy + top * s,
                    dx + (b[0] + 2.7f) * s, dy + bottom * s, r, r, barPaint)
            }
        }
    }

    /** Gradient wala title (theme ke rang). */
    inner class GradientText(ctx: Context) : TextView(ctx) {
        var grad: IntArray = approvedBrandGradient

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            applyShader()
        }

        fun applyShader() {
            if (width <= 0) return
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                grad, null, Shader.TileMode.CLAMP
            )
            invalidate()
        }
    }
}

/** Blurred glow (website ke box-shadow jaisa). Sirf pill ke BAHAR dikhta hai. */
class GlowDrawable(private val glowColor: Int, private val blurPx: Float, private val radiusPx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inner = android.graphics.Path().apply {
            addRoundRect(RectF(b), radiusPx, radiusPx, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        @Suppress("DEPRECATION")
        canvas.clipPath(inner, android.graphics.Region.Op.DIFFERENCE)
        canvas.drawRoundRect(RectF(b), radiusPx, radiusPx, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
