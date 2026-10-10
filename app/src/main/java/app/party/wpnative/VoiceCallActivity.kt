package app.party.wpnative

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Full pure-native private voice-call surface. No WebView and no video/camera path. */
class VoiceCallActivity : Activity() {
    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_CALL = "call"
        private const val EXTRA_ANSWER = "answer"
        private const val MODE_OUTGOING = "outgoing"
        private const val MODE_INCOMING = "incoming"
        private const val REQ_MIC = 804

        fun startOutgoing(ctx: Context, peer: String, code: String, chatId: String) {
            val normalized = WpUser.normalizeFriendCode(code)
            if (normalized.length != 8) {
                Toast.makeText(ctx, "Voice call ke liye Friend Code zaroori hai", Toast.LENGTH_LONG).show()
                return
            }
            if (normalized == WpUser.friendCodeRaw(ctx)) {
                Toast.makeText(ctx, "Apne aap ko call nahi kar sakte", Toast.LENGTH_LONG).show()
                return
            }
            if (!Friends.hasCode(ctx, normalized)) {
                Toast.makeText(ctx, "Sirf accepted Friend Code contact ko call kar sakte hain",
                    Toast.LENGTH_LONG).show()
                return
            }
            if (CallState.active()) {
                ctx.startActivity(openIntent(ctx)); return
            }
            ctx.startActivity(Intent(ctx, VoiceCallActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_OUTGOING).putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_CODE, normalized).putExtra(EXTRA_CHAT, chatId)
                .putExtra(EXTRA_CALL, newCallId()))
        }

        fun incomingIntent(ctx: Context, pending: PendingCall, answer: Boolean): Intent =
            Intent(ctx, VoiceCallActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_INCOMING).putExtra(EXTRA_PEER, pending.peerName)
                .putExtra(EXTRA_CODE, pending.peerCode).putExtra(EXTRA_CHAT, pending.chatId)
                .putExtra(EXTRA_CALL, pending.callId).putExtra(EXTRA_ANSWER, answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun openIntent(ctx: Context): Intent = Intent(ctx, VoiceCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var controls: LinearLayout
    private lateinit var avatarHost: FrameLayout
    private val animators = ArrayList<ValueAnimator>()
    private var pendingAction: (() -> Unit)? = null
    private var outgoingStarted = false
    private val tick = object : Runnable {
        override fun run() { render(CallState.current()); window.decorView.postDelayed(this, 1000L) }
    }
    private val observer: (CallSnapshot) -> Unit = { render(it) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hex(v: String) = Color.parseColor(v)
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT < 27) window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        setContentView(build())
        handleIntent(intent)
    }

    override fun onNewIntent(value: Intent?) {
        super.onNewIntent(value)
        if (value != null) { intent = value; handleIntent(value) }
    }

    override fun onResume() {
        super.onResume(); CallState.observe(observer); window.decorView.post(tick)
    }

    override fun onPause() {
        CallState.remove(observer); window.decorView.removeCallbacks(tick); super.onPause()
    }

    override fun onDestroy() {
        animators.forEach { it.cancel() }; animators.clear(); super.onDestroy()
    }

    private fun handleIntent(value: Intent) {
        when (value.getStringExtra(EXTRA_MODE)) {
            MODE_OUTGOING -> {
                val peer = value.getStringExtra(EXTRA_PEER).orEmpty()
                val code = value.getStringExtra(EXTRA_CODE).orEmpty()
                val chat = value.getStringExtra(EXTRA_CHAT).orEmpty()
                val id = value.getStringExtra(EXTRA_CALL).orEmpty().ifBlank(::newCallId)
                if (!outgoingStarted && !CallState.active()) ensureMic {
                    outgoingStarted = true
                    VoiceCallService.startOutgoing(this, peer, code, chat, id)
                }
                if (!CallState.active()) preview(peer, CallPhase.OUTGOING, "Calling…")
            }
            MODE_INCOMING -> {
                val requestedId = value.getStringExtra(EXTRA_CALL).orEmpty()
                val stored = PendingCallStore.get(this)
                val current = CallState.current()
                if (stored == null) {
                    if (current.callId != requestedId || !current.live) {
                        CallState.update(CallSnapshot(requestedId,
                            value.getStringExtra(EXTRA_CHAT).orEmpty(),
                            value.getStringExtra(EXTRA_PEER).orEmpty().ifBlank { "Dost" },
                            value.getStringExtra(EXTRA_CODE).orEmpty(), false, CallPhase.ENDED,
                            status = "Call is no longer available"))
                    }
                } else {
                    if (!CallState.active()) CallState.update(CallSnapshot(stored.callId, stored.chatId,
                        stored.peerName, stored.peerCode, false, CallPhase.INCOMING,
                        status = "Incoming voice call"))
                    if (value.getBooleanExtra(EXTRA_ANSWER, false)) answer(stored)
                }
            }
            else -> {
                val current = CallState.current()
                if (!current.live) {
                    val pending = PendingCallStore.get(this)
                    if (pending != null) CallState.update(CallSnapshot(pending.callId, pending.chatId,
                        pending.peerName, pending.peerCode, false, CallPhase.INCOMING,
                        status = "Incoming voice call"))
                    else finish()
                }
            }
        }
        render(CallState.current())
    }

    private fun answer(pending: PendingCall) = ensureMic {
        IncomingCallController.accepted(this, pending.callId)
        VoiceCallService.answer(this, pending)
    }

    private fun ensureMic(action: () -> Unit) {
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bluetoothGranted = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (micGranted && bluetoothGranted) { action(); return }
        pendingAction = action
        val missing = ArrayList<String>()
        if (!micGranted) missing += Manifest.permission.RECORD_AUDIO
        if (!bluetoothGranted && Build.VERSION.SDK_INT >= 31) missing += Manifest.permission.BLUETOOTH_CONNECT
        requestPermissions(missing.toTypedArray(), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_MIC) return
        val action = pendingAction; pendingAction = null
        val micAt = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val micGranted = if (micAt >= 0) results.getOrNull(micAt) == PackageManager.PERMISSION_GRANTED
            else checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) action?.invoke()
        else {
            status.text = "Microphone permission chahiye"
            Toast.makeText(this, "Mic allow kiye baghair voice call nahi chalegi", Toast.LENGTH_LONG).show()
        }
    }

    private fun build(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(hex("#170d35"), hex("#0b1027"), hex("#03040b")))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("⌄", false) { finish() }, lp(dp(42), dp(42)))
        top.addView(TextView(this).apply {
            text = "Private voice call"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        top.addView(TextView(this).apply {
            text = "🔒"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(hex("#86efac"))
        }, lp(dp(42), dp(42)))
        col.addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        avatarHost = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val ring1 = View(this).apply { background = ring(hex("#8b72ff")) }
        val ring2 = View(this).apply { background = ring(hex("#54e8ff")) }
        avatarHost.addView(ring1, FrameLayout.LayoutParams(dp(164), dp(164), Gravity.CENTER))
        avatarHost.addView(ring2, FrameLayout.LayoutParams(dp(134), dp(134), Gravity.CENTER))
        center.addView(avatarHost, lp(dp(180), dp(180)))
        pulse(ring1, 0L); pulse(ring2, 650L)

        name = TextView(this).apply {
            text = "Dost"; textSize = 28f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            maxLines = 1
        }
        center.addView(name, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })
        status = TextView(this).apply {
            text = "Connecting…"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(hex("#bdb6dd"))
        }
        center.addView(status, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(5) })
        timer = TextView(this).apply {
            text = "00:00"; textSize = 18f; gravity = Gravity.CENTER
            letterSpacing = .08f; setTextColor(Color.WHITE)
        }
        center.addView(timer, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(7) })
        center.addView(wave(), lp(dp(178), dp(42)).apply { topMargin = dp(10) })
        col.addView(center, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        col.addView(controls, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private var lastControlSig = ""
    private var sawCallState = false

    private fun render(value: CallSnapshot) {
        if (!::name.isInitialized) return
        if (value.phase != CallPhase.IDLE) sawCallState = true
        else if (sawCallState) { if (!isFinishing) finish(); return }
        name.text = value.peerName.ifBlank { "Dost" }
        status.text = value.status.ifBlank { when (value.phase) {
            CallPhase.OUTGOING -> "Calling…"; CallPhase.INCOMING -> "Incoming voice call"
            CallPhase.CONNECTING -> "Connecting…"; CallPhase.ACTIVE -> "Connected"
            CallPhase.RECONNECTING -> "Reconnecting…"; CallPhase.ENDED -> "Call ended"
            else -> "Private voice call"
        } }
        val sec = if (value.startedAt > 0) ((System.currentTimeMillis() - value.startedAt) / 1000L) else 0L
        timer.text = "%02d:%02d".format(sec / 60L, sec % 60L)
        timer.visibility = if (value.startedAt > 0) View.VISIBLE else View.INVISIBLE
        rebuildAvatar(value)
        val controlSig = "${value.phase}|${value.muted}|${value.speaker}"
        if (lastControlSig == controlSig) return
        lastControlSig = controlSig
        controls.removeAllViews()
        when (value.phase) {
            CallPhase.INCOMING -> {
                controls.addView(control("Decline", "✕", hex("#ed245a")) {
                    IncomingCallController.decline(this, value.callId); finish()
                })
                controls.addView(control("Answer", "☎", hex("#34d399")) {
                    PendingCallStore.get(this)?.let(::answer)
                }, lp(dp(92), dp(92)).apply { leftMargin = dp(38) })
            }
            CallPhase.ACTIVE, CallPhase.RECONNECTING -> {
                controls.addView(control(if (value.muted) "Unmute" else "Mute", if (value.muted) "🔇" else "🎙",
                    if (value.muted) hex("#8b72ff") else Color.argb(42,255,255,255)) {
                    VoiceCallService.command(this, VoiceCallService.ACTION_MUTE)
                })
                controls.addView(control("End", "☎", hex("#ed245a")) {
                    VoiceCallService.command(this, VoiceCallService.ACTION_END)
                }, lp(dp(92), dp(92)).apply { leftMargin = dp(20) })
                controls.addView(control(if (value.speaker) "Earpiece" else "Speaker", "🔊",
                    if (value.speaker) hex("#8b72ff") else Color.argb(42,255,255,255)) {
                    VoiceCallService.command(this, VoiceCallService.ACTION_SPEAKER)
                }, lp(dp(92), dp(92)).apply { leftMargin = dp(20) })
            }
            CallPhase.ENDED -> {
                val endedId = value.callId
                window.decorView.postDelayed({
                    val now = CallState.current()
                    if (!isFinishing && now.phase == CallPhase.ENDED && now.callId == endedId) finish()
                }, 1500L)
            }
            CallPhase.IDLE -> Unit
            else -> controls.addView(control("End call", "☎", hex("#ed245a")) {
                VoiceCallService.command(this, VoiceCallService.ACTION_END)
            })
        }
    }

    private var avatarSig = ""
    private fun rebuildAvatar(value: CallSnapshot) {
        val sig = "${value.peerName}|${value.peerCode}"
        if (avatarSig == sig) return
        avatarSig = sig
        while (avatarHost.childCount > 2) avatarHost.removeViewAt(2)
        avatarHost.addView(DpStore.circle(this, value.peerName.ifBlank { "Dost" },
            colorFor(value.peerName), 112), FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
    }

    private fun preview(peer: String, phase: CallPhase, text: String) {
        name.text = peer.ifBlank { "Dost" }; status.text = text
        timer.visibility = if (phase == CallPhase.ACTIVE) View.VISIBLE else View.INVISIBLE
    }

    private fun control(label: String, glyph: String, color: Int, onClick: () -> Unit): View =
        control(label, glyph, color, onClick, lp(dp(92), dp(92)))

    private fun control(label: String, glyph: String, color: Int, onClick: () -> Unit,
        params: LinearLayout.LayoutParams): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@VoiceCallActivity).apply {
                text = glyph; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                setOnClickListener { onClick() }
            }, lp(dp(62), dp(62)))
            addView(TextView(this@VoiceCallActivity).apply {
                text = label; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(hex("#cfc6ee"))
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(25)))
        }.also { it.layoutParams = params }
    }

    private fun button(label: String, bright: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.argb(if (bright) 60 else 28,255,255,255)); cornerRadius = dp(12).toFloat() }
        setOnClickListener { action() }
    }

    private fun ring(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(1), color)
    }

    private fun pulse(view: View, delay: Long) {
        val a = ValueAnimator.ofFloat(0.80f, 1.12f).apply {
            duration = 2600L; startDelay = delay; repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float; view.scaleX = v; view.scaleY = v
                view.alpha = (1.12f - v).div(.32f).coerceIn(.12f, .8f)
            }
            start()
        }
        animators += a
    }

    private fun wave(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        repeat(11) { i ->
            val bar = View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(hex("#54e8ff"), hex("#c38aff"))).apply { cornerRadius = dp(3).toFloat() }
            }
            row.addView(bar, lp(dp(4), dp(8 + (i * 7 % 25))).apply { leftMargin = dp(4) })
            val a = ValueAnimator.ofFloat(.35f, 1f, .45f).apply {
                duration = 700L + i * 47L; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { bar.scaleY = it.animatedValue as Float }
                start()
            }
            animators += a
        }
        return row
    }

    private fun colorFor(value: String): Int {
        val colors = intArrayOf(hex("#8b72ff"), hex("#22d3ee"), hex("#ec4899"), hex("#10b981"))
        return colors[(value.lowercase().hashCode() and Int.MAX_VALUE) % colors.size]
    }
}
