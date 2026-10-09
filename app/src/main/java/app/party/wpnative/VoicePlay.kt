package app.party.wpnative

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Voice message **chalana** (bubble ke ▶ par tap).
 *
 * Ek waqt mein sirf ek awaaz chale (dusri dabate hi pehli ruk jaye) —
 * website bhi yahi karti hai.
 *
 * Naya: **aage peechhe** bhi kar sakte hain —
 *   - ▶/⏸ se roko (jahin ruki wahin se phir chalegi, shuru se nahi)
 *   - waveform par ungli rakho/ghumao (scrub) -> usi jagah se chalegi
 */
object VoicePlay {

    /** Har 100ms aur har seek par: (chaabi, kitna chala 0..1, kitne second baqi). */
    var onTick: ((String, Float, Int) -> Unit)? = null
    /** Khatam / roka gaya: (chaabi). */
    var onStop: ((String) -> Unit)? = null

    private var mp: MediaPlayer? = null
    private var key: String? = null
    private var dur = 0                 // poori lambai (millisecond)
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Ye awaaz abhi chal rahi hai? */
    fun playing(k: String): Boolean = k.isNotBlank() && key == k && mp?.isPlaying == true

    /** Kaunsi awaaz abhi "pakdi" hui hai (chale ya ruki ho — dono)? */
    fun current(): String? = key

    /** 0..1 — kitna chal chuka (ruki hui ho to jahan ruki hai wahan ka). */
    fun progressOf(k: String): Float {
        if (k.isBlank() || key != k) return 0f
        if (dur <= 0) return 0f
        return (pos().toFloat() / dur).coerceIn(0f, 1f)
    }

    /** Kitne second baqi hain (ruki hui ho to bhi sahi jawab). */
    fun remainOf(k: String): Int {
        if (k.isBlank() || key != k) return 0
        if (dur <= 0) return 0
        return Math.max(0, (dur - pos()) / 1000)
    }

    private fun pos(): Int = try { mp?.currentPosition ?: 0 } catch (t: Throwable) { 0 }

    /** ▶ dabaya: chal rahi ho to ruko, ruki ho to wahin se phir chalao. */
    fun toggle(ctx: Context, k: String) {
        if (k.isBlank()) return
        val cur = mp
        if (cur != null && key == k) {
            if (cur.isPlaying) {
                try { cur.pause() } catch (t: Throwable) {}
                stopTick()
                fire(k)                     // ▶ wapas + jahan ruki wahin ki lakiren
            } else {
                try { cur.start(); startTick(k) } catch (t: Throwable) { fire(k) }
            }
            return
        }
        stop()
        val f: File = MediaCache.fileOf(ctx, k) ?: return
        if (!f.exists()) return
        try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { fire(k); stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare()
            dur = p.duration
            p.start()
            mp = p
            key = k
            startTick(k)
            fire(k)
        } catch (t: Throwable) {
            try { mp?.release() } catch (t2: Throwable) {}
            mp = null; key = null; dur = 0
        }
    }

    /** Waveform par ungli -> usi jagah se chalao (scrub). */
    fun seek(ctx: Context, k: String, frac: Float) {
        if (k.isBlank()) return
        if (key != k) { toggle(ctx, k); if (key != k) return }
        val p = mp ?: return
        val to = (dur * frac.coerceIn(0f, 1f)).toInt()
        try { p.seekTo(to) } catch (t: Throwable) {}
        if (!p.isPlaying) { try { p.start() } catch (t: Throwable) {} }
        startTick(k)
        fire(k)
    }

    /** Rok do (jab screen band ho ya koi aur awaaz chale). */
    fun stop() {
        stopTick()
        val k = key
        try { mp?.stop() } catch (t: Throwable) {}
        try { mp?.release() } catch (t: Throwable) {}
        mp = null; key = null; dur = 0
        if (k != null) onStop?.invoke(k)
    }

    private fun startTick(k: String) {
        stopTick()
        tick = object : Runnable {
            override fun run() {
                val p = mp ?: return
                if (p.isPlaying) {
                    fire(k)
                    handler.postDelayed(this, 100L)
                }
            }
        }
        handler.postDelayed(tick!!, 100L)
    }

    private fun stopTick() { tick?.let { handler.removeCallbacks(it) }; tick = null }

    private fun fire(k: String) {
        try { onTick?.invoke(k, progressOf(k), remainOf(k)) } catch (t: Throwable) {}
    }
}
