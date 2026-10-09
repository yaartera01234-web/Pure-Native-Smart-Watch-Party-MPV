package app.party.wpnative

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.sqrt

/**
 * Voice message **record** karna (🎤 dabane par).
 *
 * Website (party-final1.html) ki tarah:
 *   - zyada se zyada **60 second** (VOICE_MAX_SEC) — uske baad apne aap ruk jaye
 *   - awaaz 32kbps / mono (chhoti file -> Firestore ki 1MB had ke andar)
 *   - har 100ms mein awaaz ka zor naapa jata hai (waveform ke liye)
 */
object VoiceRec {

    const val MAX_SEC = 60

    /** Har kitne millisecond mein awaaz naapi jaye (waveform ki pattiyan isi se banti hain). */
    private const val SAMPLE_MS = 100L
    private const val BARS = 22

    private var rec: MediaRecorder? = null
    private var out: File? = null
    private var startTs = 0L
    private val samples = ArrayList<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Har 100ms chalega (screen par waqt badalne ke liye). */
    var onSec: ((Int) -> Unit)? = null
    /** 60 second pooray -> apne aap band (website WPVoice ki tarah). */
    var onLimit: (() -> Unit)? = null

    fun recording(): Boolean = rec != null

    /** Shuru karo. Mic ki ijazat pehle hi milni chahiye. */
    fun start(ctx: Context, onErr: (String) -> Unit): Boolean {
        if (rec != null) return false

        val nice = File(ctx.cacheDir, "v_${System.currentTimeMillis()}.m4a")
        val plain = File(ctx.cacheDir, "v_${System.currentTimeMillis()}.3gp")

        // pehle achhi quality (AAC 32kbps) — na chale to purana 3gp (AMR)
        val used = tryStart(ctx, nice) { r ->
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setAudioChannels(1)
        } ?: tryStart(ctx, plain) { r ->
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        }

        if (used == null) { onErr("Mic start nahi hua"); return false }

        out = used                      // kaunsi file mein record ho raha hai (yaad rahe)
        startTs = System.currentTimeMillis()
        samples.clear()
        startTick()
        return true
    }

    /** Ek koshish: recorder banao, setting lagao, chalao. Kaamyab -> wahi file. */
    private fun tryStart(ctx: Context, file: File, cfg: (MediaRecorder) -> Unit): File? {
        val r = try {
            if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        } catch (t: Throwable) { return null }
        return try {
            cfg(r)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            rec = r
            file
        } catch (t: Throwable) {
            try { r.release() } catch (t2: Throwable) {}
            try { file.delete() } catch (t3: Throwable) {}
            null
        }
    }

    private fun startTick() {
        stopTick()
        tick = object : Runnable {
            override fun run() {
                val r = rec ?: return
                try { samples.add(r.maxAmplitude) } catch (t: Throwable) {}
                val s = secs()
                onSec?.invoke(s)
                if (s >= MAX_SEC) { onLimit?.invoke(); return }
                handler.postDelayed(this, SAMPLE_MS)
            }
        }
        handler.postDelayed(tick!!, SAMPLE_MS)
    }

    private fun stopTick() { tick?.let { handler.removeCallbacks(it) }; tick = null }

    fun secs(): Int = if (rec == null) 0 else ((System.currentTimeMillis() - startTs) / 1000L).toInt()

    /**
     * Band karo. `send = false` -> record mita do (✕).
     * Wapas: (file, second, waveform) — file null ho to kuch record hi nahi hua.
     */
    fun stop(send: Boolean, done: (File?, Int, String) -> Unit) {
        val r = rec ?: return
        val dur = secs().coerceAtLeast(0)
        val w = wave()
        stopTick()
        try { r.stop() } catch (t: Throwable) {}
        try { r.release() } catch (t: Throwable) {}
        rec = null
        val f = out; out = null
        onSec = null; onLimit = null
        if (!send) { try { f?.delete() } catch (t: Throwable) {}; done(null, 0, ""); return }
        if (f == null || !f.exists() || f.length() <= 0L || dur < 1) {
            try { f?.delete() } catch (t: Throwable) {}
            done(null, dur, "")
            return
        }
        done(f, dur, w)
    }

    /** Band karo bina bheje (activity band ho to). */
    fun abort() { if (rec != null) stop(false) { _, _, _ -> } }

    /** 22 pattiyon ki kadi: "0..100,0..100,..." */
    private fun wave(): String {
        if (samples.isEmpty()) return ""
        val per = samples.size.toFloat() / BARS
        val out = ArrayList<Int>(BARS)
        for (i in 0 until BARS) {
            val a = (i * per).toInt()
            val b = ((i + 1) * per).toInt().coerceAtLeast(a + 1).coerceAtMost(samples.size)
            var peak = 0
            for (k in a until b) peak = Math.max(peak, samples[k])
            // awaaz ka zor 0..32767 -> naram kar ke 0..100 (chhoti awaaz bhi nazar aaye)
            val v = (sqrt((peak.coerceIn(0, 32767)) / 32767f) * 150f).toInt().coerceIn(6, 100)
            out.add(v)
        }
        return out.joinToString(",")
    }
}
