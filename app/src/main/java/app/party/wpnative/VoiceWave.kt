package app.party.wpnative

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Voice message ki **awaaz ki lakiren** (website `.cv-wave`).
 *
 * Website ke hisaab se: 26px unchi jagah, 22 pattiyan, har patti 3px chaudai,
 * darmiyan 2px ka faasla, kone 2px gol. Jo hissa chal chuka hai wo rang badalta hai:
 *   - doosre ka bubble : #54e8ff
 *   - apna bubble      : #150c26
 * Baqi pattiyan halki (safed 42% ya apne bubble mein gehra 30%).
 */
class VoiceWave(ctx: Context, private val mine: Boolean) : View(ctx) {

    private companion object { const val BARS = 22 }

    private val dens = ctx.resources.displayMetrics.density
    private val barW = 3f * dens
    private val gap = 2f * dens
    private val rad = 2f * dens
    private val minH = 6f * dens
    private val maxH = 20f * dens

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var amp = FloatArray(BARS)
    private var prog = 0f

    /** Ungli rakhi/ghumai to bataya jaye: 0..1 (kitni door se sun'na hai). */
    var onSeek: ((Float) -> Unit)? = null

    /** played wala rang / baqi ka rang (website ke theek rang). */
    private val onCol = if (mine) Color.parseColor("#150c26") else Color.parseColor("#54e8ff")
    private val offCol = if (mine) Color.argb(77, 20, 8, 33) else Color.argb(107, 255, 255, 255)

    init {
        setWave("")
        isClickable = true      // ungli se aage peechhe (scrub)
    }

    /** Waveform par ungli -> usi jagah se chalao. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)   // bubble ka swipe na chale
                if (w > 0f) onSeek?.invoke((e.x / w).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (w > 0f) onSeek?.invoke((e.x / w).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /**
     * "12,40,80,..." wali kadi (har 100ms ki awaaz).
     * Khaali ho to website wala seedha pattern (6 + ((i*7)%15)) — design waisa hi rahe.
     */
    fun setWave(s: String) {
        val parts = if (s.isBlank()) emptyList() else s.split(",")
        for (i in 0 until BARS) {
            amp[i] = if (parts.size >= BARS) {
                (parts[i].trim().toIntOrNull() ?: 0).coerceIn(0, 100) / 100f
            } else {
                ((i * 7) % 15) / 15f                 // website ka pattern
            }
        }
        invalidate()
    }

    /** 0..1 — kitni der chal chuki hai (is se pehle wali pattiyan rang badlengi). */
    fun setProgress(v: Float) {
        prog = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val want = (BARS * barW + (BARS - 1) * gap).toInt()
        setMeasuredDimension(resolveSize(want, w), resolveSize((26f * dens).toInt(), h))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val hgt = height.toFloat()
        var x = 0f
        for (i in 0 until BARS) {
            val hh = (minH + (maxH - minH) * amp[i])
            val top = (hgt - hh) / 2f
            rect.set(x, top, x + barW, top + hh)
            paint.color = if ((i.toFloat() / BARS) <= prog) onCol else offCol
            c.drawRoundRect(rect, rad, rad, paint)
            x += barW + gap
        }
    }
}
