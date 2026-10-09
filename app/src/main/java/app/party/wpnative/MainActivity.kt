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

    private data class ThemeDef(val key: String, val name: String, val bg: IntArray, val j: IntArray)
    private data class BubbleDef(val name: String, val short: String, val own: IntArray, val oth: IntArray, val ownTxt: Int, val othTxt: Int)

    private val themes: List<ThemeDef> by lazy {
        listOf(
            ThemeDef("neon", "Lobby Neon",
                intArrayOf(hex("#050719"), hex("#16112d"), hex("#0b1829")),
                intArrayOf(hex("#fa35de"), hex("#c03cff"), hex("#36d9fa"))),
            ThemeDef("purple", "Night Purple (default)",
                intArrayOf(hex("#0f0c29"), hex("#302b63"), hex("#24243e")),
                intArrayOf(hex("#ff66bd"), hex("#a477ff"), hex("#55baff"))),
            ThemeDef("blue", "Ocean Cyan",
                intArrayOf(hex("#051219"), hex("#11232d"), hex("#0b2927")),
                intArrayOf(hex("#358efa"), hex("#3cb7ff"), hex("#36faed"))),
            ThemeDef("sunset", "Sunset Rose",
                intArrayOf(hex("#19050c"), hex("#2d111b"), hex("#29180b")),
                intArrayOf(hex("#fa3549"), hex("#ff3c83"), hex("#fa8e36"))),
            ThemeDef("emerald", "Emerald Glow",
                intArrayOf(hex("#05190f"), hex("#112d1f"), hex("#0b2729")),
                intArrayOf(hex("#35fac5"), hex("#3cff9e"), hex("#36eafa"))),
            ThemeDef("amoled", "Champagne Gold",
                intArrayOf(hex("#191305"), hex("#2d2511"), hex("#29270b")),
                intArrayOf(hex("#e88f47"), hex("#eabc51"), hex("#e8db48")))
        )
    }

    private val bubbles: List<BubbleDef> by lazy {
        listOf(
            BubbleDef("Lobby Neon · Violet + Midnight", "Violet + Midnight", cols("#cf30bd", "#6b55e4"), cols("#261c40", "#1d2340", "#12253b"), hex("#ffffff"), hex("#efe8fb")),
            BubbleDef("1 · Pink + Cyan (default)", "Pink + Cyan", cols("#f472b6", "#a78bfa"), cols("#51c9c2", "#7182e9", "#aa7ced"), hex("#ffffff"), hex("#ffffff")),
            BubbleDef("2 · Orange + Blue", "Orange + Blue", cols("#fb923c", "#ea580c"), cols("#38bdf8", "#3b82f6", "#1d4ed8"), hex("#3a1a04"), hex("#ffffff")),
            BubbleDef("3 · Green + Magenta", "Green + Magenta", cols("#22c55e", "#15803d"), cols("#f472b6", "#e11d48", "#be123c"), hex("#02240f"), hex("#ffffff")),
            BubbleDef("4 · Yellow + Violet", "Yellow + Violet", cols("#facc15", "#eab308"), cols("#a78bfa", "#7c3aed", "#5b21b6"), hex("#3a2d02"), hex("#ffffff")),
            BubbleDef("5 · Red + Teal", "Red + Teal", cols("#ef4444", "#b91c1c"), cols("#2dd4bf", "#14b8a6", "#0f766e"), hex("#ffffff"), hex("#04201d")),
            BubbleDef("6 · Lime + Purple", "Lime + Purple", cols("#a3e635", "#65a30d"), cols("#c084fc", "#8b5cf6", "#6d28d9"), hex("#1a2e02"), hex("#ffffff")),
            BubbleDef("7 · Black + White", "Black + White", cols("#0b1220", "#1f2937"), cols("#ffffff", "#e2e8f0", "#cbd5e1"), hex("#ffffff"), hex("#0b1220")),
            BubbleDef("Ocean Cyan · Matching bubbles", "Ocean Cyan", cols("#3078cf", "#55b0e4"), cols("#1c3340", "#1c3340", "#123b38"), hex("#031724"), hex("#e8f4fb")),
            BubbleDef("Emerald Glow · Matching bubbles", "Emerald Glow", cols("#30cfa5", "#55e49d"), cols("#1c402e", "#1c402e", "#12383b"), hex("#041b12"), hex("#e8fbf2")),
            BubbleDef("Sunset Rose · Matching bubbles", "Sunset Rose", cols("#ce3758", "#ae275d"), cols("#401c29", "#401c29", "#3b2412"), hex("#ffffff"), hex("#fbe8ef")),
            BubbleDef("Champagne Gold · Matching bubbles", "Champagne Gold", cols("#e6b25b", "#d1bc78"), cols("#40351c", "#40351c", "#3b3812"), hex("#291b07"), hex("#fbf5e8"))
        )
    }

    private val towers = arrayOf("🗼 EMQX (tez!)", "🗼 HiveMQ", "🗼 tyckr")
    private var towerIndex = 0
    private var themeIndex = 1   // Night Purple (default)
    private var bubbleIndex = 1  // 1 · Pink + Cyan (default)

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
    private lateinit var joinBtn: Button
    private lateinit var barTitle: TextView
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
        saveFcmToken()
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
        themeIndex = prefs.getInt("theme", 1).coerceIn(0, themes.size - 1)
        bubbleIndex = prefs.getInt("bubble", 1).coerceIn(0, bubbles.size - 1)
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

        val card = LinearLayout(this).apply {
            clipChildren = false
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundBox(Color.argb(120, 12, 10, 28), Color.argb(70, 167, 139, 250), 24, 1)
        }

        // Logo (5 rangeeli bars) + title
        val logo = LogoView(this)
        card.addView(logo, lp(dp(44), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        android.animation.ObjectAnimator.ofFloat(logo, "translationY", 0f, -dp(10).toFloat(), 0f).apply {
            duration = 2000
            repeatCount = android.animation.ObjectAnimator.INFINITE
            start()
        }
        titleView = GradientText(this).apply {
            text = "Watch Party"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 32f
            gravity = Gravity.CENTER
        }
        card.addView(titleView, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); bottomMargin = dp(5) })

        // Badge (glow ke saath)
        card.addView(TextView(this).apply {
            text = "Feel Special With Me 🥰 💗"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#f9a8d4"))
            setPadding(dp(18), dp(7), dp(18), dp(7))
            background = LayerDrawable(arrayOf(
                GlowDrawable(Color.argb(89, 244, 114, 182), dp(9).toFloat(), dp(20).toFloat()),
                roundBox(Color.argb(46, 244, 114, 182), Color.argb(115, 244, 114, 182), 20, 1)
            ))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); bottomMargin = dp(12) })

        // Tagline
        card.addView(TextView(this).apply {
            text = "Doston ke sath YouTube & MP4 dekho + live chatting karo!"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(hex("#c4b5fd"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        // Avatar + buttons
        card.addView(buildAvatarPick(), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        // Name
        nameInput = styledEdit("Apna name likho...", 20, 17f)
        card.addView(nameInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        // Room
        roomInput = styledEdit("Room name... (doston se poocho!)", 20, 16f)
        card.addView(roomInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

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
        card.addView(towerBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        // Theme bar (tap = theme + bubble picker)
        card.addView(buildThemeBar(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

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
                    // Yahi naam chat ki pehchaan hai (doston ko bhi yahi dikhega)
                    WpUser.setName(this@MainActivity, n)
                    FirebaseChat.setPresence(this@MainActivity, n, true)
                    // Join ke baad next page: Messages (Inbox)
                    startActivity(Intent(this@MainActivity, InboxActivity::class.java))
                }
            }
        }
        card.addView(joinBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })

        // Feature chips
        card.addView(chipFlow(listOf("▶️ YouTube", "🎞️ MP4/MP3", "📋 Playlist", "🖼️ DP", "↩️ Reply", "🔄 Sync", "🚪 Rooms")),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        renderAvatar()

        scroll.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(8), dp(16), dp(24))
        })
        rootView.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return rootView
    }

    private fun towerLabel(): String = towers[towerIndex] + "   ▾"

    private fun openTowerPicker() {
        AlertDialog.Builder(this)
            .setTitle("🗼 Tower chuno")
            .setItems(arrayOf<CharSequence>(*towers)) { _, i ->
                towerIndex = i
                towerBtn.text = towerLabel()
                savePrefs()
            }
            .show()
    }

    /** Theme + bubble ka rang poore page par lagata hai. */
    private fun applyTheme() {
        val t = themes[themeIndex]
        val b = bubbles[bubbleIndex]
        rootView.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, t.bg)
        titleView.grad = t.j
        titleView.applyShader()
        joinBtn.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, t.j)
            .apply { cornerRadius = dp(14).toFloat() }
        barTitle.text = t.name.replace(" (default)", "") + " · " + b.short
        for ((i, v) in dotViews.withIndex()) {
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.j[i]) }
        }
    }

    private fun buildAvatarPick(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Avatar circle: gradient + letter/emoji, uske upar photo/cartoon image (circle mein clip)
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(hex("#f472b6"), hex("#a78bfa"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), Color.argb(77, 255, 255, 255))
            }
        }
        val letterView = TextView(this).apply {
            textSize = 30f
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
        frame.addView(letterView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        frame.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        // Website wala 3px ka pink-lavender ring (border), photo ke upar bhi dikhe
        val ringView = View(this).apply {
            background = InsetDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), Color.argb(235, 240, 171, 252))
                },
                dp(2)
            )
        }
        frame.addView(ringView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(frame, lp(dp(58), dp(58)))

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
        if (save) savePrefs()
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
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBox(Color.argb(150, 12, 10, 28), Color.argb(56, 255, 255, 255), 14, 1)
            setOnClickListener { openPicker() }
        }
        bar.addView(TextView(this).apply { text = "🎨"; textSize = 16f; setPadding(0, 0, dp(9), 0) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        barTitle = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        texts.addView(barTitle)
        texts.addView(TextView(this).apply {
            text = "theme aur bubble badlo"
            textSize = 11f
            setTextColor(hex("#c4b5fd"))
        })
        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 3 rang ke dots (theme ke rang)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dotViews.clear()
        for (i in 0 until 3) {
            val d = View(this)
            dotViews.add(d)
            dots.addView(d, lp(dp(12), dp(12)).apply { rightMargin = dp(4) })
        }
        bar.addView(dots)
        bar.addView(TextView(this).apply { text = "›"; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(8), 0, 0, 0) })
        return bar
    }

    /** Theme + bubble picker: website wali cards (theme ka swatch + bubble ka chat sample). */
    private fun openPicker() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(6))
        }
        val scroll = ScrollView(this).apply { addView(box) }

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
                setTextColor(hex("#c4b5fd"))
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        fun card(content: View, label: String, selected: Boolean, onTap: () -> Unit): LinearLayout {
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = roundBox(
                    Color.argb(if (selected) 50 else 18, 255, 255, 255),
                    if (selected) Color.argb(220, 255, 255, 255) else Color.argb(34, 255, 255, 255),
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

        fun themeSwatch(t: ThemeDef): View = View(this@MainActivity).apply {
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

        fun bubbleSample(b: BubbleDef): View = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(chip("unka", b.oth, b.othTxt), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) })
            addView(chip("mera", b.own, b.ownTxt), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        }

        fun render() {
            box.removeAllViews()
            header("🎨 Theme chuno", "(poora rang badalta hai)")
            val tCards = themes.mapIndexed { i, t ->
                card(themeSwatch(t), t.name.replace(" (default)", ""), i == themeIndex) {
                    themeIndex = i
                    applyTheme()
                    savePrefs()
                    render()
                }
            }
            grid(tCards)

            header("💬 Bubble style", "(sirf chat ke bubble · farq darje mein)")
            val bCards = bubbles.mapIndexed { i, b ->
                card(bubbleSample(b), b.short, i == bubbleIndex) {
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
        dialog.window?.setBackgroundDrawable(roundBox(Color.argb(245, 12, 10, 28), Color.argb(70, 255, 255, 255), 20, 1))
        dialog.show()
    }

    private fun styledEdit(hint: String, maxLen: Int, size: Float): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(hex("#c4b5fd"))
        setTextColor(Color.WHITE)
        textSize = size
        gravity = Gravity.CENTER
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(maxLen))
        setPadding(dp(18), 0, dp(18), 0)
        background = roundBox(Color.argb(200, 12, 10, 28), Color.argb(60, 255, 255, 255), 14, 2)
    }

    private fun chipFlow(items: List<String>): FlowLayout {
        val flow = FlowLayout(this)
        for (s in items) {
            val chip = TextView(this).apply {
                text = s
                textSize = 12f
                setTextColor(Color.WHITE)
                setSingleLine(true)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = roundBox(Color.argb(31, 255, 255, 255), Color.argb(38, 255, 255, 255), 20, 1)
            }
            flow.addView(chip, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        return flow
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
    private fun swatchColors(t: ThemeDef): IntArray = when (t.key) {
        "purple" -> cols("#3a3170", "#241f4d")
        "amoled" -> cols("#f0b756", "#e0b95f", "#f2dda0")
        else -> t.j
    }

    // ---------- custom views ----------

    /** Website ka logo: 5 rangeeli bars, CSS wpWave jaisi wave animation. */
    inner class LogoView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
            val s = width / 24f
            val r = 1.35f * s
            for ((i, b) in bars.withIndex()) {
                val ph = (t + delays[i]) % 1800f
                val f = if (ph <= 900f) ph / 900f else (1800f - ph) / 900f
                val e = (1f - Math.cos(f * Math.PI).toFloat()) / 2f
                val scale = 0.45f + 0.55f * e
                val bottom = b[1] + b[2]
                val top = bottom - b[2] * scale
                paint.color = colors[b[3].toInt()]
                canvas.drawRoundRect(b[0] * s, top * s, (b[0] + 2.7f) * s, bottom * s, r, r, paint)
            }
        }
    }

    /** Gradient wala title (theme ke rang). */
    inner class GradientText(ctx: Context) : TextView(ctx) {
        var grad: IntArray = intArrayOf(hex("#ff66bd"), hex("#a477ff"), hex("#55baff"))

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

/** Chips ko line-by-line wrap karta hai aur har line ko center mein rakhta hai (CSS flex-wrap jaisa). */
class FlowLayout(ctx: android.content.Context) : ViewGroup(ctx) {
    private val gap = (6 * resources.displayMetrics.density).toInt()

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    private fun childW(c: View): Int {
        val lp = c.layoutParams as MarginLayoutParams
        return c.measuredWidth + lp.leftMargin + lp.rightMargin
    }

    private fun childH(c: View): Int {
        val lp = c.layoutParams as MarginLayoutParams
        return c.measuredHeight + lp.topMargin + lp.bottomMargin
    }

    private fun split(maxW: Int): List<Triple<Int, Int, List<View>>> {
        val out = mutableListOf<Triple<Int, Int, List<View>>>()
        var cur = mutableListOf<View>()
        var curW = 0
        var curH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val cw = childW(c)
            if (cur.isNotEmpty() && curW + gap + cw > maxW) {
                out.add(Triple(curW, curH, cur))
                cur = mutableListOf()
                curW = 0
                curH = 0
            }
            if (cur.isNotEmpty()) curW += gap
            curW += cw
            curH = maxOf(curH, childH(c))
            cur.add(c)
        }
        if (cur.isNotEmpty()) out.add(Triple(curW, curH, cur))
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChildWithMargins(c, MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), 0,
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
        }
        val lines = split(inner)
        var h = paddingTop + paddingBottom
        for ((idx, line) in lines.withIndex()) {
            h += line.second
            if (idx > 0) h += gap
        }
        setMeasuredDimension(width, resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = (r - l) - paddingLeft - paddingRight
        var y = paddingTop
        for (line in split(maxW)) {
            var x = paddingLeft + (maxW - line.first) / 2
            for (c in line.third) {
                val lp = c.layoutParams as MarginLayoutParams
                val left = x + lp.leftMargin
                val top = y + lp.topMargin
                c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
                x += childW(c) + gap
            }
            y += line.second + gap
        }
    }
}
