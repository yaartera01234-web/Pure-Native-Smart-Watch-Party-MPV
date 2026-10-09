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
 */
object VoicePlay {

    /** Chalte waqt har 100ms: (chaabi, kitna chala 0..1, kitne second baqi). */
    var onTick: ((String, Float, Int) -> Unit)? = null
    /** Khatam / roka gaya: (chaabi). */
    var onStop: ((String) -> Unit)? = null

    private var mp: MediaPlayer? = null
    private var key: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Abhi kaun si awaaz chal rahi hai (koi nahi to null). */
    fun playing(): String? = if (mp?.isPlaying == true) key else null

    /** ▶ dabaya: agar yahi chal rahi ho to ruko, warna (dobara) chalao. */
    fun toggle(ctx: Context, k: String) {
        if (k.isBlank()) return
        val cur = mp
        if (cur != null && key == k) {
            if (cur.isPlaying) { pause(); return }
            try { cur.start(); startTick(k) } catch (t: Throwable) { fire(k, 0f, 0) }
            return
        }
        stop()
        val f: File = MediaCache.fileOf(ctx, k) ?: return
        if (!f.exists()) return
        try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { fire(k, 1f, 0); stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare()
            p.start()
            mp = p
            key = k
            startTick(k)
        } catch (t: Throwable) {
            try { mp?.release() } catch (t2: Throwable) {}
            mp = null; key = null
        }
    }

    private fun pause() {
        val k = key ?: return
        try { mp?.pause() } catch (t: Throwable) {}
        stopTick()
        val pos = try { mp?.currentPosition ?: 0 } catch (t: Throwable) { 0 }
        fire(k, prog(pos), remain(pos))
    }

    /** Rok do (jab screen band ho ya koi aur awaaz chale). */
    fun stop() {
        stopTick()
        val k = key
        try { mp?.stop() } catch (t: Throwable) {}
        try { mp?.release() } catch (t: Throwable) {}
        mp = null; key = null
        if (k != null) onStop?.invoke(k)
    }

    private fun startTick(k: String) {
        stopTick()
        tick = object : Runnable {
            override fun run() {
                val p = mp ?: return
                if (p.isPlaying) {
                    val pos = try { p.currentPosition } catch (t: Throwable) { 0 }
                    fire(k, prog(pos), remain(pos))
                    handler.postDelayed(this, 100L)
                }
            }
        }
        handler.postDelayed(tick!!, 100L)
    }

    private fun stopTick() { tick?.let { handler.removeCallbacks(it) }; tick = null }

    private fun prog(pos: Int): Float {
        val d = try { mp?.duration ?: 0 } catch (t: Throwable) { 0 }
        return if (d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    private fun remain(pos: Int): Int {
        val d = try { mp?.duration ?: 0 } catch (t: Throwable) { 0 }
        return if (d > 0) Math.max(0, (d - pos) / 1000) else 0
    }

    private fun fire(k: String, p: Float, rem: Int) { try { onTick?.invoke(k, p, rem) } catch (t: Throwable) {} }
}
