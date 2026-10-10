package app.party.wpnative

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.ListenerRegistration

/**
 * Messages / Inbox (website-v61 wali #dm-sheet > #dm-view-inbox ka design).
 * Sirf user ke khud add kiye hue dost — koi test/demo row nahi.
 */
class InboxActivity : Activity() {

    private data class Friend(
        val name: String,
        val last: String,
        val time: String,
        val online: Boolean,
        val unreadCount: Int,
        val color: Int,
        val pinned: Boolean = false,
        val code: String = "",
        val lastTs: Long = 0L,
        val outgoing: Boolean = false
    ) {
        val unread: Boolean get() = unreadCount > 0
    }

    /** Rows sirf asli add kiye hue doston ke liye; test Dost 1..4 hata diye. */
    private val chats = mutableListOf<Friend>()
    private val incomingRequests = mutableListOf<FriendRequest>()
    private var requestListener: ListenerRegistration? = null
    private var searchQuery = ""
    private val avatarLookups = HashSet<String>()
    private val inboxObserver: () -> Unit = {
        if (!isFinishing && ::listBox.isInitialized) fillInbox()
    }

    private lateinit var listBox: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun hex(s: String): Int = Color.parseColor(s)
    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        askNotifyPermission()
        BgMsgService.start(this)      // app band hone pe bhi notification
        setContentView(buildScreen())
        DmInboxStore.observe(inboxObserver)
        FirebaseChat.publishFriendProfile(this)
        requestListener = FirebaseChat.listenFriendRequests(this) { list ->
            incomingRequests.clear()
            incomingRequests.addAll(list.filterNot { Friends.hasCode(this, it.code) })
            DmInboxStore.setRequestCount(this, incomingRequests.size)
            if (::listBox.isInitialized) fillInbox()
        }
    }

    override fun onDestroy() {
        DmInboxStore.removeObserver(inboxObserver)
        requestListener?.remove()
        requestListener = null
        super.onDestroy()
    }

    /** Android 13+ par notification ki ijazat (push ke liye zaroori). */
    private fun askNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private var listSig = ""

    override fun onResume() {
        super.onResume()
        CallMiniBar.attach(this)
        FirebaseChat.publishFriendProfile(this)
        refreshFriendAvatars()
        // Chat screen se koi friend remove hua ho to list turant saaf ho jaye —
        // magar kuch na badla ho to bekaar dobara mat banao (tab badalte waqt jhatka na ho)
        if (::listBox.isInitialized && listSignature() != listSig) {
            listSig = listSignature()
            fillInbox()
        }
    }

    private fun refreshFriendAvatars() {
        Friends.entries(this).filter { it.code.length == 8 }.forEach { friend ->
            // Set sirf in-flight lookup ko dedupe karta hai; har onResume latest name/DP leta hai.
            if (!avatarLookups.add(friend.code)) return@forEach
            FirebaseChat.findFriendProfile(this, friend.code) { profile ->
                avatarLookups.remove(friend.code)
                if (profile != null && !isFinishing) {
                    val oldName = Friends.updateNameByCode(this, profile.code, profile.name)
                    if (oldName != null && !oldName.equals(profile.name, ignoreCase = false)) {
                        val i = chats.indexOfFirst {
                            it.code == profile.code || it.name.equals(oldName, ignoreCase = true)
                        }
                        if (i >= 0) chats[i] = chats[i].copy(
                            name = profile.name,
                            color = colorFor(profile.name),
                            code = profile.code
                        )
                    }
                    if (::listBox.isInitialized) {
                        listSig = listSignature()
                        fillInbox()
                    }
                }
            }
        }
    }

    /** List ka "naksha" — is se pata chalta hai ke dobara banane ki zarurat hai ya nahi. */
    private fun listSignature(): String =
        Friends.entries(this).joinToString(",") { "${it.code}:${it.name}" } + "#" +
            incomingRequests.joinToString(",") { "${it.code}:${it.ts}" } + "#" +
            chats.joinToString(",") { "${it.name}:${it.pinned}:${it.last}:${it.unreadCount}:${it.lastTs}" }

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        // Website ka dm-sheet radial background
        root.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setColors(intArrayOf(hex("#1a1a2e"), hex("#0f0b20"), hex("#050507")))
            setGradientCenter(0.5f, 0f)
            gradientRadius = resources.displayMetrics.heightPixels * 0.75f
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildHeader(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(buildSearch(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(14))
        }
        listBox = list
        fillInbox()
        listSig = listSignature()      // abhi ban chuki -> onResume mein bekaar na bane

        val scroll = ScrollView(this).apply { addView(list) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(buildBottomBar(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundBox(Color.argb(8, 255, 255, 255), Color.argb(15, 255, 255, 255), 0, 1)
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "💬 Messages"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        bar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val addBtn = squareBtn("👤+", gradient = true)
        addBtn.setOnClickListener { addFriend() }
        bar.addView(addBtn, lp(dp(40), dp(40)).apply { leftMargin = dp(6) })
        val menuBtn = squareBtn("☰", gradient = true)
        menuBtn.setOnClickListener { showChatMenu(menuBtn) }
        bar.addView(menuBtn, lp(dp(40), dp(40)).apply { leftMargin = dp(6) })
        return bar
    }

    private fun squareBtn(label: String, gradient: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (gradient) hex("#0d0716") else Color.WHITE)
        background = if (gradient) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(hex("#54e8ff"), hex("#8b72ff")))
                .apply { cornerRadius = dp(12).toFloat() }
        } else {
            roundBox(Color.argb(31, 255, 255, 255), Color.argb(0, 255, 255, 255), 12, 0)
        }
        setOnClickListener {
            Toast.makeText(this@InboxActivity, "Agle step mein", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSearch(): View {
        val wrap = FrameLayout(this).apply { setPadding(dp(12), dp(9), dp(12), dp(6)) }
        val input = EditText(this).apply {
            hint = "Naam ya code se dhoondo..."
            setHintTextColor(hex("#8b84a8"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), 0, dp(12), 0)
            background = roundBox(Color.argb(15, 255, 255, 255), Color.argb(20, 255, 255, 255), 11, 1)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    if (::listBox.isInitialized) fillInbox()
                }
            })
        }
        wrap.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        return wrap
    }

    private fun buildRow(f: Friend): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = inboxRowBackground(f.unread)
            setOnClickListener {
                val chatId = WpUser.friendChatId(this@InboxActivity, f.name, f.code)
                DmInboxStore.markRead(this@InboxActivity, chatId, f.lastTs)
                WpNotify.cancel(this@InboxActivity, chatId)
                startActivity(Intent(this@InboxActivity, ChatActivity::class.java)
                    .putExtra("name", f.name)
                    .putExtra("friendCode", f.code)
                    .putExtra("chatId", chatId))
            }
        }

        row.setOnLongClickListener { showRowMenu(row, f); true }

        // Original ring4: 49dp cyan-violet-pink ring, 45dp dark-edged real Friend Code DP.
        val online = Presence.isOnline(this, f.name)
        val avatar = FrameLayout(this).apply {
            clipChildren = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = if (online) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(hex("#54e8ff"), hex("#8b72ff"), hex("#ff5ebc"))).apply {
                    shape = GradientDrawable.OVAL
                }
            } else GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(36, 255, 255, 255))
            }
        }
        val avatarEdge = FrameLayout(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(hex("#100a1e"))
            }
            addView(DpStore.circle(this@InboxActivity, f.name, f.color, 41),
                FrameLayout.LayoutParams(dp(41), dp(41), Gravity.CENTER))
        }
        avatar.addView(avatarEdge, FrameLayout.LayoutParams(dp(45), dp(45), Gravity.CENTER))
        avatar.addView(
            presenceDot(this, online, 13, 17, 2),
            FrameLayout.LayoutParams(dp(17), dp(17), Gravity.END or Gravity.BOTTOM).apply {
                setMargins(0, 0, -dp(2), -dp(2))
            }
        )
        row.clipChildren = false
        row.addView(avatar, lp(dp(49), dp(49)))

        // Naam + last message pill
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = if (f.pinned) "📌 ${f.name}" else f.name
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        })
        if (f.last.isNotBlank()) info.addView(TextView(this).apply {
            val palette = selectedBubblePalette()
            text = (if (f.outgoing) "✓  " else "") + f.last
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (f.outgoing) palette.ownText else palette.otherText)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = miniMessageBackground(f.outgoing, palette)
            setSingleLine(true)
            maxWidth = resources.displayMetrics.widthPixels - dp(160)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(11) })

        // Original meta3: time/day + unread number; read ho to lock.
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            minimumWidth = dp(58) // `4:00 PM`/`10:59 PM` ka aakhri digit kabhi clip na ho
        }
        if (f.time.isNotBlank()) meta.addView(TextView(this).apply {
            text = f.time
            textSize = 9.5f
            gravity = Gravity.END
            setSingleLine(true)
            includeFontPadding = false
            minWidth = dp(58)
            setPadding(dp(2), 0, dp(1), 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (f.unread) hex("#8ce8ff") else hex("#9e97c4"))
        }, lp(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
        meta.addView(TextView(this).apply {
            text = if (f.unread) f.unreadCount.toString() else "🔒"
            textSize = if (f.unread) 10.5f else 11f
            gravity = Gravity.CENTER
            if (f.unread) {
                minWidth = dp(20)
                minHeight = dp(20)
                setPadding(dp(6), 0, dp(6), 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(hex("#ff5ebc"), hex("#8b72ff"))).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.argb(56, 255, 255, 255))
                }
            } else alpha = .55f
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)).apply { topMargin = dp(5) })
        row.addView(meta, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return row.also {
            it.layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    }

    /** Neeche ka nav: shared BottomNav.kt, Chat active. */
    private fun buildBottomBar(): View = buildBottomNav(this, "chat")

    /** Chat ☰ menu: Pinned chats + Chat clear + Remove Friend. */
    private fun showChatMenu(anchor: View) {
        showDropMenu(anchor, listOf(
            "📌  Pinned chats" to { showPinSheet() },
            "🗑️  Chat clear" to {
                confirmThen(this, "Chat clear karein?", "Chat ki history clear hogi.") {
                    Toast.makeText(this, "Chat clear (demo)", Toast.LENGTH_SHORT).show()
                }
            },
            "👤  Remove Friend" to { showRemoveSheet() }
        ))
    }

    /**
     * Original Friend Code view: apna code, Copy/Share, peer-code validation/lookup,
     * phir real Firestore request + greeting. Koi sample name-only shortcut nahi.
     */
    private fun addFriend() {
        FirebaseChat.publishFriendProfile(this)
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setColors(intArrayOf(hex("#241456"), hex("#130933"), hex("#05030d")))
                setGradientCenter(0.5f, 0f)
                gradientRadius = resources.displayMetrics.heightPixels * 0.8f
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "👤  Add Friends"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(squareBtn("✕", gradient = false).apply { setOnClickListener { dlg.dismiss() } },
            lp(dp(38), dp(38)))
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ownCode = WpUser.friendCode(this)
        val codeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            background = roundBox(Color.argb(38, 139, 114, 255), Color.argb(95, 84, 232, 255), 20, 1)
        }
        codeCard.addView(TextView(this).apply {
            text = "Tumhara Friend Code (dost ko bhejo)"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(hex("#bdb5d4"))
        })
        codeCard.addView(TextView(this).apply {
            text = ownCode
            textSize = 25f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#54e8ff"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7); bottomMargin = dp(12)
        })
        val codeButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copy = friendCodeButton("📋  Copy", true)
        val share = friendCodeButton("📲  Share", false)
        codeButtons.addView(copy, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        codeButtons.addView(share, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        codeCard.addView(codeButtons, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(codeCard, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val addBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(14))
            background = roundBox(Color.argb(18, 255, 255, 255), Color.argb(35, 255, 255, 255), 18, 1)
        }
        addBox.addView(TextView(this).apply {
            text = "Dost ka code likho / paste karo"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        val peerInput = EditText(this).apply {
            hint = "WP1-XXXX-XXXX"
            setHintTextColor(hex("#777089"))
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(13))
            setPadding(dp(12), 0, dp(12), 0)
            background = roundBox(Color.argb(95, 8, 5, 20), Color.argb(55, 139, 114, 255), 12, 1)
        }
        addBox.addView(peerInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        })
        val find = friendCodeButton("🔍  Dost dhoondo", true)
        addBox.addView(find, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)))
        body.addView(addBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        var foundProfile: FriendProfile? = null
        val found = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
            background = roundBox(Color.argb(30, 52, 211, 153), Color.argb(90, 52, 211, 153), 18, 1)
        }
        val foundTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val foundAvatar = TextView(this).apply {
            text = "?"
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(hex("#8b72ff")) }
        }
        foundTop.addView(foundAvatar, lp(dp(42), dp(42)))
        val foundInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val foundName = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val foundCode = TextView(this).apply {
            textSize = 11f
            setTextColor(hex("#9ef5d2"))
        }
        foundInfo.addView(foundName)
        foundInfo.addView(foundCode)
        foundTop.addView(foundInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(11)
        })
        found.addView(foundTop)
        val request = friendCodeButton("✉️  Message bhejo / request", true)
        found.addView(request, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)).apply { topMargin = dp(12) })
        body.addView(found, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        body.addView(TextView(this).apply {
            text = "🔒  Friend Code dono handsets ki stable pehchaan hai. Pehli dafa message karne wala Request mein aata hai; Accept ke baad woh normal chat list mein aa jata hai."
            textSize = 11.5f
            setTextColor(hex("#aaa1c2"))
            setLineSpacing(0f, 1.25f)
            setPadding(dp(3), dp(14), dp(3), dp(10))
        })

        val scroll = ScrollView(this).apply { addView(body) }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        copy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Smart Party Plus Friend Code", ownCode))
            Toast.makeText(this, "📋 Code copy ho gaya", Toast.LENGTH_SHORT).show()
        }
        share.setOnClickListener {
            val text = "Smart Party Plus pe mujhe add karo 👇\nMera code: $ownCode"
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Friend Code share karo"))
            } catch (_: Throwable) {
                Toast.makeText(this, "Code: $ownCode", Toast.LENGTH_LONG).show()
            }
        }

        var formatting = false
        peerInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (formatting) return
                val raw = WpUser.normalizeFriendCode(s?.toString())
                val pretty = when {
                    raw.isEmpty() -> ""
                    raw.length <= 4 -> "WP1-$raw"
                    else -> "WP1-${raw.take(4)}-${raw.drop(4)}"
                }
                if (pretty != s?.toString()) {
                    formatting = true
                    peerInput.setText(pretty)
                    peerInput.setSelection(pretty.length)
                    formatting = false
                }
                foundProfile = null
                found.visibility = View.GONE
            }
        })

        find.setOnClickListener {
            val raw = WpUser.normalizeFriendCode(peerInput.text.toString())
            when {
                raw.length != 8 -> Toast.makeText(this, "⚠️ Poora code likho (WP1-XXXX-XXXX)", Toast.LENGTH_SHORT).show()
                raw == WpUser.friendCodeRaw(this) -> Toast.makeText(this, "😂 Ye tumhara apna code hai!", Toast.LENGTH_SHORT).show()
                else -> {
                    find.isEnabled = false
                    find.alpha = 0.55f
                    find.text = "Dhoond rahe hain…"
                    FirebaseChat.findFriendProfile(this, raw) { profile ->
                        find.isEnabled = true
                        find.alpha = 1f
                        find.text = "🔍  Dost dhoondo"
                        if (profile == null) {
                            foundProfile = null
                            found.visibility = View.GONE
                            Toast.makeText(this, "Is code ka dost nahi mila — usay app ek dafa kholne ko kaho", Toast.LENGTH_LONG).show()
                            return@findFriendProfile
                        }
                        foundProfile = profile
                        foundName.text = profile.name
                        foundCode.text = "${WpUser.formatFriendCode(profile.code)} · code theek hai"
                        foundAvatar.text = profile.name.firstOrNull()?.uppercase() ?: "?"
                        val already = Friends.hasCode(this, profile.code)
                        request.text = if (already) "💬  Chat kholo" else "✉️  Message bhejo / request"
                        found.visibility = View.VISIBLE
                        Toast.makeText(this, "✅ Code theek hai", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        request.setOnClickListener {
            val profile = foundProfile ?: return@setOnClickListener
            if (Friends.hasCode(this, profile.code)) {
                dlg.dismiss()
                openFriendChat(profile)
                return@setOnClickListener
            }
            val greeting = "👋 Salam! Main ${WpUser.me(this)} hoon — Smart Party Plus pe milte hain."
            request.isEnabled = false
            request.alpha = 0.55f
            request.text = "Request bhej rahe hain…"
            FirebaseChat.sendFriendRequest(this, profile.code, greeting) { ok ->
                request.isEnabled = true
                request.alpha = 1f
                request.text = "✉️  Message bhejo / request"
                if (!ok) {
                    Toast.makeText(this, "Request nahi ja saki — internet/Firebase check karo", Toast.LENGTH_LONG).show()
                    return@sendFriendRequest
                }
                Friends.add(this, profile.name, profile.code)
                val chatId = WpUser.friendChatId(this, profile.name, profile.code)
                FirebaseChat.send(this, chatId, ChatMsg(
                    from = WpUser.me(this), text = greeting,
                    ts = System.currentTimeMillis(), type = "text"
                )) { FirebaseChat.prune(this, chatId) }
                fillInbox()
                BgMsgService.start(this)
                dlg.dismiss()
                Toast.makeText(this, "✅ Request bhej di", Toast.LENGTH_SHORT).show()
                openFriendChat(profile)
            }
        }

        dlg.setContentView(page)
        dlg.show()
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
        }
    }

    private fun friendCodeButton(label: String, primary: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (primary) hex("#10081e") else Color.WHITE)
        background = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(hex("#54e8ff"), hex("#8b72ff"))).apply { cornerRadius = dp(12).toFloat() }
        } else roundBox(Color.argb(28, 255, 255, 255), Color.argb(55, 255, 255, 255), 12, 1)
    }

    private fun openFriendChat(profile: FriendProfile) {
        startActivity(Intent(this, ChatActivity::class.java)
            .putExtra("name", profile.name)
            .putExtra("friendCode", profile.code)
            .putExtra("chatId", WpUser.friendChatId(this, profile.name, profile.code)))
    }

    /** Upar wale ☰ se: pehle poochhna kaunsa dost hatana hai (poorani list). */
    private fun showRemoveSheet() {
        val list = visibleChats()
        if (list.isEmpty()) {
            Toast.makeText(this, "Friend list khali hai", Toast.LENGTH_SHORT).show()
            return
        }
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(hex("#1b1433"))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        sheet.addView(TextView(this).apply {
            text = "Kaunsa dost hatana hai?"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        sheet.addView(TextView(this).apply {
            text = "Dost par tap karo, phir confirm karo"
            textSize = 12f
            setTextColor(hex("#a291c6"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2); bottomMargin = dp(10)
        })

        list.forEach { f ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = roundBox(Color.argb(10, 255, 255, 255), Color.argb(15, 255, 255, 255), 14, 1)
            }
            item.addView(TextView(this).apply {
                text = f.name.first().toString().uppercase()
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(f.color) }
            }, lp(dp(32), dp(32)))
            item.addView(TextView(this).apply {
                text = f.name
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            item.addView(TextView(this).apply {
                text = "Remove"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(hex("#fda4af"))
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            item.setOnClickListener { dlg.dismiss(); removeFriend(f) }
            sheet.addView(item, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
    }

    /** Original order: pehle Message Requests, phir accepted Chats. */
    private fun fillInbox() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()

        val q = searchQuery.lowercase()
        val qCode = WpUser.normalizeFriendCode(searchQuery).lowercase()
        val requests = incomingRequests.filterNot { Friends.hasCode(this, it.code) }.filter {
            q.isBlank() || it.name.lowercase().contains(q) ||
                (qCode.length >= 3 && it.code.lowercase().contains(qCode))
        }
        if (requests.isNotEmpty()) {
            listBox.addView(sectionLabel("MESSAGE REQUESTS"))
            requests.forEach { listBox.addView(buildRequestRow(it)) }
        }

        listBox.addView(sectionLabel("CHATS"))
        val accepted = visibleChats().filter {
            q.isBlank() || it.name.lowercase().contains(q) ||
                (qCode.length >= 3 && it.code.lowercase().contains(qCode))
        }.sortedWith(compareByDescending<Friend> { it.pinned }.thenByDescending { it.lastTs })
        accepted.forEach { listBox.addView(buildRow(it)) }
        if (accepted.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Abhi koi chat nahi — upar 👤+ se Friend Code add karo."
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(hex("#8b84a8"))
                setPadding(dp(14), dp(18), dp(14), dp(18))
            }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listSig = listSignature()
    }

    private fun sectionLabel(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.12f
        setTextColor(hex("#A78BFA"))
        layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), dp(10), 0, dp(7))
        }
    }

    private fun buildRequestRow(req: FriendRequest): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(11), dp(10), dp(11))
            background = roundBox(Color.argb(25, 139, 114, 255), Color.argb(55, 139, 114, 255), 16, 1)
        }
        row.addView(DpStore.circle(this, req.name, colorFor(req.name), 44), lp(dp(44), dp(44)))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = req.name
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        })
        info.addView(TextView(this).apply {
            text = req.preview.ifBlank { WpUser.formatFriendCode(req.code) }
            textSize = 11f
            setTextColor(hex("#aaa1c2"))
            maxLines = 2
        })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10); rightMargin = dp(8)
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accept = friendCodeButton("Accept", true)
        val reject = friendCodeButton("✕", false)
        buttons.addView(accept, lp(dp(70), dp(38)).apply { rightMargin = dp(5) })
        buttons.addView(reject, lp(dp(38), dp(38)))
        row.addView(buttons)

        accept.setOnClickListener { acceptRequest(req) }
        reject.setOnClickListener {
            incomingRequests.removeAll { it.code == req.code }
            DmInboxStore.setRequestCount(this, incomingRequests.size)
            FirebaseChat.removeFriendRequest(this, req.code)
            fillInbox()
            Toast.makeText(this, "Request hata di", Toast.LENGTH_SHORT).show()
        }
        return row.also {
            it.layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun acceptRequest(req: FriendRequest) {
        Friends.add(this, req.name, req.code)
        incomingRequests.removeAll { it.code == req.code }
        DmInboxStore.setRequestCount(this, incomingRequests.size)
        FirebaseChat.removeFriendRequest(this, req.code)
        val chatId = WpUser.friendChatId(this, req.name, req.code)
        val accepted = "✅ ${WpUser.me(this)} ne tumhari request accept kar li — ab baat kar sakte ho!"
        FirebaseChat.send(this, chatId, ChatMsg(
            from = WpUser.me(this), text = accepted,
            ts = System.currentTimeMillis(), type = "text"
        )) { FirebaseChat.prune(this, chatId) }
        BgMsgService.start(this)
        fillInbox()
        Toast.makeText(this, "✅ ${req.name} add ho gaya", Toast.LENGTH_SHORT).show()
    }

    /**
     * Store se naam + stable code le kar rows banao. Removed friend dobara appear nahi hota.
     */
    private fun visibleChats(): List<Friend> {
        val entries = Friends.entries(this)
        entries.forEach { e ->
            val chatId = WpUser.friendChatId(this, e.name, e.code)
            val state = DmInboxStore.get(this, chatId)
            val i = chats.indexOfFirst { it.name.equals(e.name, ignoreCase = true) }
            if (i < 0) {
                chats.add(Friend(e.name, state.preview, inboxDayLabel(state.lastTs), false,
                    state.unread, colorFor(e.name), code = e.code, lastTs = state.lastTs,
                    outgoing = state.outgoing))
            } else {
                chats[i] = chats[i].copy(
                    code = e.code,
                    last = state.preview,
                    time = inboxDayLabel(state.lastTs),
                    unreadCount = state.unread,
                    lastTs = state.lastTs,
                    outgoing = state.outgoing
                )
            }
        }
        chats.removeAll { chat -> entries.none { it.name.equals(chat.name, ignoreCase = true) } }
        return chats.toList()
    }

    /** Original dayLabel: aaj time, kal `Kal`, warna dd/MM. */
    private fun inboxDayLabel(ts: Long): String {
        if (ts <= 0L) return ""
        val now = java.util.Calendar.getInstance()
        val day = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        if (day.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            day.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) {
            return java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(ts))
        }
        now.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (day.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            day.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) return "Kal"
        return java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault())
            .format(java.util.Date(ts))
    }

    private fun colorFor(name: String): Int {
        val palette = listOf("#f472b6", "#38bdf8", "#fb7185", "#a78bfa", "#34d399", "#fbbf24")
        return hex(palette[Math.floorMod(name.hashCode(), palette.size)])
    }

    /** Chat par zor se press: Pin / Unpin, Chat clear aur Remove Friend. */
    private fun showRowMenu(anchor: View, f: Friend) {
        showDropMenu(anchor, listOf(
            (if (f.pinned) "📌  Unpin karo" else "📌  Pin karo") to { togglePin(f.name) },
            "🧹  Chat clear karo" to {
                confirmThen(this, "${f.name} ka chat clear karein?", "Is chat ki history clear hogi.") { clearChat(f.name) }
            },
            "👤  Remove Friend" to { removeFriend(f) }
        ))
    }

    /**
     * Friend hatao: website ke #dm-remove-dialog jaisa confirm, phir
     * Friends store se nikal do (Inbox + Chat dono par asar).
     */
    private fun removeFriend(f: Friend) {
        if (!Friends.has(this, f.name)) {
            fillInbox()
            Toast.makeText(this, "${f.name} pehle hi hat chuka hai", Toast.LENGTH_SHORT).show()
            return
        }
        removeFriendDialog(this, f.name) {
            Friends.remove(this, f.name)
            chats.removeAll { it.name == f.name }
            fillInbox()
            Toast.makeText(this, "${f.name} friend list se hat gaya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePin(name: String) {
        val i = chats.indexOfFirst { it.name == name }
        if (i >= 0) chats[i] = chats[i].copy(pinned = !chats[i].pinned)
        fillInbox()
    }

    private fun clearChat(name: String) {
        val i = chats.indexOfFirst { it.name == name }
        if (i >= 0) {
            val f = chats[i]
            DmInboxStore.markRead(this, WpUser.friendChatId(this, f.name, f.code), f.lastTs)
            chats[i] = f.copy(last = "Chat khali", unreadCount = 0)
        }
        fillInbox()
        Toast.makeText(this, "Chat clear ho gayi", Toast.LENGTH_SHORT).show()
    }

    /** Pinned chats: neeche se sheet khulti hai, chats select karke Save karte hain. */
    private fun showPinSheet() {
        val picked = chats.filter { it.pinned }.map { it.name }.toMutableSet()
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(hex("#1b1433"))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        sheet.addView(TextView(this).apply {
            text = "Konsi chat pin karni hai?"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        sheet.addView(TextView(this).apply {
            text = "Tap karke select karo, phir Save dabao"
            textSize = 12f
            setTextColor(hex("#a291c6"))
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(10) })

        chats.forEach { f ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = roundBox(Color.argb(10, 255, 255, 255), Color.argb(15, 255, 255, 255), 14, 1)
            }
            val dot = View(this)
            fun paintDot() {
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (picked.contains(f.name)) hex("#a477ff") else Color.TRANSPARENT)
                    setStroke(dp(2), hex("#a477ff"))
                }
            }
            paintDot()
            item.addView(dot, lp(dp(20), dp(20)))
            item.addView(TextView(this).apply {
                text = f.name
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            item.setOnClickListener {
                if (picked.contains(f.name)) picked.remove(f.name) else picked.add(f.name)
                paintDot()
            }
            sheet.addView(item, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        sheet.addView(TextView(this).apply {
            text = "Save"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(hex("#0d0716"))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(hex("#54e8ff"), hex("#8b72ff")))
                .apply { cornerRadius = dp(12).toFloat() }
            setOnClickListener {
                for (i in chats.indices) chats[i] = chats[i].copy(pinned = picked.contains(chats[i].name))
                fillInbox()
                dlg.dismiss()
            }
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })

        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
    }

    /** Original v2.2 inbox glass card, including the unread cyan-to-pink edge. */
    private fun inboxRowBackground(unread: Boolean): LayerDrawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (unread) intArrayOf(Color.argb(43, 84, 232, 255), Color.argb(33, 255, 94, 188))
            else intArrayOf(Color.argb(33, 139, 114, 255), Color.argb(18, 255, 94, 188))).apply {
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), Color.argb(if (unread) 51 else 33, 255, 255, 255))
        }
        val edge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(hex("#54e8ff"), hex("#ff5ebc"))).apply {
            cornerRadii = floatArrayOf(0f, 0f, dp(3).toFloat(), dp(3).toFloat(),
                dp(3).toFloat(), dp(3).toFloat(), 0f, 0f)
            alpha = if (unread) 255 else 0
        }
        return LayerDrawable(arrayOf(base, edge)).apply {
            setLayerWidth(1, dp(3))
            setLayerGravity(1, Gravity.START)
            setLayerInset(1, 0, dp(15), 0, dp(15))
        }
    }

    private fun selectedBubblePalette(): WpBubbleTheme {
        val index = getSharedPreferences("wp_native", Context.MODE_PRIVATE)
            .getInt("bubble", 1).coerceIn(WpBubbles.all.indices)
        return WpBubbles.all[index]
    }

    /** Inbox ki last-message preview exactly selected own/other bubble palette follow karti hai. */
    private fun miniMessageBackground(outgoing: Boolean, palette: WpBubbleTheme): GradientDrawable {
        val radius = dp(13).toFloat()
        val small = dp(5).toFloat()
        return GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (outgoing) palette.own else palette.other).apply {
            cornerRadii = if (outgoing)
                floatArrayOf(radius, radius, radius, radius, small, small, radius, radius)
            else floatArrayOf(radius, radius, radius, radius, radius, radius, small, small)
            setStroke(dp(1), palette.edge ?: Color.argb(if (outgoing) 51 else 66, 255, 255, 255))
        }
    }

    private fun roundBox(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }
}
