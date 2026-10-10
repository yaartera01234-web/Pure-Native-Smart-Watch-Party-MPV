package app.party.wpnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** In-app minimized call controls, independent from the Activity that originally placed the call. */
object CallMiniBar {
    private const val TAG = "wp_native_call_mini"
    private const val PREF = "wp_call_mini"
    private const val KEY_Y = "vertical_fraction"

    fun attach(activity: Activity) {
        if (activity is VoiceCallActivity || activity.isFinishing) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val old = content.findViewWithTag<View>(TAG)
        if (old != null) return
        val host = FrameLayout(activity).apply { tag = TAG; isClickable = false; isFocusable = false }
        host.addView(Mini(activity), FrameLayout.LayoutParams(-1, dp(activity, 62), Gravity.BOTTOM).apply {
            leftMargin = dp(activity, 12); rightMargin = dp(activity, 12); bottomMargin = dp(activity, 86)
        })
        content.addView(host, ViewGroup.LayoutParams(-1, -1))
    }

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()

    private class Mini(private val activity: Activity) : LinearLayout(activity) {
        private val handler = Handler(Looper.getMainLooper())
        private val title: TextView
        private val sub: TextView
        private val mute: TextView
        private val speaker: TextView
        private val avatarHost: FrameLayout
        private var avatarSig = ""
        private var lookedUpCode = ""
        private var downRawY = 0f
        private var startTranslationY = 0f
        private var dragging = false
        private val observer: (CallSnapshot) -> Unit = { render(it) }
        private val tick = object : Runnable {
            override fun run() { render(CallState.current()); handler.postDelayed(this, 1000L) }
        }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(7), dp(7), dp(7)); elevation = dp(16).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#27164f"), Color.parseColor("#101b39"))).apply {
                cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(75, 255, 255, 255))
            }
            avatarHost = FrameLayout(context)
            addView(avatarHost, LayoutParams(dp(42), dp(42)))
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            title = TextView(context).apply {
                textSize = 12.5f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
            }
            sub = TextView(context).apply { textSize = 10.5f; setTextColor(Color.parseColor("#c4b5fd")); maxLines = 1 }
            texts.addView(title); texts.addView(sub)
            addView(texts, LayoutParams(0, -2, 1f).apply { leftMargin = dp(9) })
            mute = action("🎙", VoiceCallService.ACTION_MUTE)
            speaker = action("🔊", VoiceCallService.ACTION_SPEAKER)
            addView(mute, LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(5) })
            addView(speaker, LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(5) })
            addView(action("✕", VoiceCallService.ACTION_END, Color.parseColor("#e11d48")),
                LayoutParams(dp(38), dp(38)))
            setOnClickListener { restoreFullCall() }
            // Bar ke khaali/text hissa ko pakar kar poori screen mein upar/neeche move karo.
            // Buttons apna action rakhte hain; short tap ab bhi full call restore karta hai.
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawY = event.rawY
                        startTranslationY = translationY
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - downRawY
                        if (!dragging && abs(dy) > dp(6)) dragging = true
                        if (dragging) {
                            val (min, max) = dragBounds()
                            translationY = (startTranslationY + dy).coerceIn(min, max)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) savePosition() else performClick()
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                    else -> false
                }
            }
            render(CallState.current())
            post { restorePosition() }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow(); CallState.observe(observer); handler.post(tick)
        }

        override fun onDetachedFromWindow() {
            CallState.remove(observer); handler.removeCallbacks(tick); super.onDetachedFromWindow()
        }

        private fun render(value: CallSnapshot) {
            val show = value.phase == CallPhase.OUTGOING || value.phase == CallPhase.CONNECTING ||
                value.phase == CallPhase.ACTIVE || value.phase == CallPhase.RECONNECTING
            visibility = if (show) View.VISIBLE else View.GONE
            if (!show) return
            title.text = value.peerName.ifBlank { "Voice call" }
            renderAvatar(value)
            val sec = if (value.startedAt > 0) ((System.currentTimeMillis() - value.startedAt) / 1000L) else 0L
            val clock = if (value.startedAt > 0) " · %02d:%02d".format(sec / 60, sec % 60) else ""
            sub.text = value.status.ifBlank { "Private voice call" } + clock
            mute.text = if (value.muted) "🔇" else "🎙"
            speaker.alpha = if (value.speaker) 1f else .68f
            mute.alpha = if (value.muted) 1f else .68f
        }

        private fun restoreFullCall() {
            activity.startActivity(VoiceCallActivity.openIntent(activity))
            activity.overridePendingTransition(0, 0)
        }

        /** Translation bounds: status area se neeche aur original bottom slot se upar. */
        private fun dragBounds(): Pair<Float, Float> {
            val min = (dp(12) - top).toFloat().coerceAtMost(0f)
            return min to 0f
        }

        private fun restorePosition() {
            val (min, max) = dragBounds()
            val fraction = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getFloat(KEY_Y, 1f).coerceIn(0f, 1f)
            translationY = min + (max - min) * fraction
        }

        private fun savePosition() {
            val (min, max) = dragBounds()
            val fraction = if (max > min) ((translationY - min) / (max - min)).coerceIn(0f, 1f) else 1f
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_Y, fraction).apply()
        }

        private fun renderAvatar(value: CallSnapshot) {
            val peer = value.peerName.ifBlank { "Dost" }
            val next = "$peer|${value.peerCode}|${DpStore.revision(peer)}"
            if (avatarSig != next) {
                avatarSig = next
                avatarHost.removeAllViews()
                avatarHost.addView(DpStore.circle(context, peer, colorFor(peer), 42),
                    FrameLayout.LayoutParams(dp(42), dp(42)))
            }
            if (value.peerCode.length == 8 && lookedUpCode != value.peerCode) {
                lookedUpCode = value.peerCode
                FirebaseChat.findFriendProfile(context, value.peerCode) { profile ->
                    if (profile != null) {
                        avatarSig = ""
                        renderAvatar(CallState.current())
                    }
                }
            }
        }

        private fun colorFor(value: String): Int {
            val colors = intArrayOf(Color.parseColor("#8b72ff"), Color.parseColor("#22d3ee"),
                Color.parseColor("#ec4899"), Color.parseColor("#10b981"))
            return colors[(value.lowercase().hashCode() and Int.MAX_VALUE) % colors.size]
        }

        private fun action(label: String, action: String, color: Int = Color.argb(35,255,255,255)) =
            TextView(context).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                setOnClickListener {
                    VoiceCallService.command(context, action)
                    // Do not let this button's click bubble into the bar's restore action.
                }
            }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }
}
