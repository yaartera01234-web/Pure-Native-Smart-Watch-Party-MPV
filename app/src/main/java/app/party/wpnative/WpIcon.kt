package app.party.wpnative

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** Composer ke icons — website ke SVG se utare gaye: "photo" aur "mic". */
class WpIcon(ctx: Context, private val kind: String) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val s = width / 24f
        c.save()
        c.scale(s, s)
        p.clearShadowLayer()
        if (kind == "photo") {
            // frame
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.9f
            box.set(3f, 5f, 21f, 19f)
            c.drawRoundRect(box, 2f, 2f, p)
            // sooraj
            p.style = Paint.Style.FILL
            c.drawCircle(8.6f, 9.6f, 2.1f, p)
            // pahad
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.7f
            path.reset()
            path.moveTo(3f, 18.2f)
            path.lineTo(8.6f, 11.6f)
            path.lineTo(12.8f, 16.6f)
            path.lineTo(15.8f, 13.4f)
            path.lineTo(21f, 18.6f)
            c.drawPath(path, p)
        } else if (kind == "phone") {
            // website #dm-call-btn ka phone SVG (Lucide phone) + purple glow
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.setShadowLayer(3f, 0f, 1f, Color.argb(110, 139, 114, 255))
            path.reset()
            path.moveTo(22f, 16.92f)
            path.lineTo(22f, 19.92f)
            path.quadTo(21.6f, 21.6f, 19.82f, 21.92f)
            path.quadTo(15.96f, 21.5f, 11.19f, 18.85f)
            path.quadTo(7.5f, 16.6f, 5.19f, 12.85f)
            path.quadTo(2.73f, 8.1f, 2.12f, 4.18f)
            path.quadTo(2.2f, 2.2f, 4.11f, 2f)
            path.lineTo(7.11f, 2f)
            path.quadTo(9.11f, 2f, 9.11f, 3.72f)
            path.quadTo(8.9f, 5.2f, 9.81f, 6.51f)
            path.quadTo(8.9f, 7.7f, 9.36f, 8.62f)
            path.lineTo(8.09f, 9.91f)
            path.quadTo(10.4f, 12.2f, 14.09f, 15.91f)
            path.lineTo(15.36f, 14.64f)
            path.quadTo(16.6f, 13.8f, 17.47f, 14.19f)
            path.quadTo(18.9f, 14.1f, 20.26f, 14.89f)
            path.quadTo(21.6f, 14.9f, 22f, 16.92f)
            path.close()
            c.drawPath(path, p)
        } else {
            // mic ka capsule
            p.style = Paint.Style.FILL
            box.set(9f, 2f, 15f, 13f)
            c.drawRoundRect(box, 3f, 3f, p)
            // neeche wali U
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            path.reset()
            box.set(5f, 5f, 19f, 19f)
            path.arcTo(box, 0f, 180f)
            c.drawPath(path, p)
            // dandi + base
            c.drawLine(12f, 19f, 12f, 21.6f, p)
            c.drawLine(8.4f, 21.6f, 15.6f, 21.6f, p)
            // chamak (chhote sitare)
            p.style = Paint.Style.FILL
            star(c, 19.2f, 3.2f, 1.7f)
            star(c, 21.6f, 6.2f, 1.1f)
            star(c, 16.6f, 5.6f, 1f)
        }
        c.restore()
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.42f, cy - r * 0.42f)
        path.lineTo(cx + r, cy)
        path.lineTo(cx + r * 0.42f, cy + r * 0.42f)
        path.lineTo(cx, cy + r)
        path.lineTo(cx - r * 0.42f, cy + r * 0.42f)
        path.lineTo(cx - r, cy)
        path.lineTo(cx - r * 0.42f, cy - r * 0.42f)
        path.close()
        c.drawPath(path, p)
    }
}

/**
 * Instagram wala "typing..." indicator: 3 chhote dots jo baari-baari upar koodte hain.
 *
 * Website ke #dm-typing .tdots se liya gaya (1.2s cycle, har dot 0.15 phase peechhe):
 *   0%,60%,100% { opacity:.55; translateY(0) }   30% { opacity:1; translateY(-4dp) }
 * Farq sirf itna hai ke website dots bubble ke bahar thi, yahan bubble ke andar hain
 * (Instagram ki tarah) — user ne sample dekh kar yahi chuna.
 */
class TypingDots(ctx: Context) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#c9ccd6")
        style = Paint.Style.FILL
    }
    private var phase = 0f
    private var anim: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        val dot = 7f * d          // website 5px tha, IG jaisa karne ke liye 7dp
        val gap = 5f * d
        val bounce = 4f * d       // kitna upar jayega
        val baseTop = bounce      // neeche wali jagah chhod kar upar koodta hai

        for (i in 0..2) {
            var t = phase - i * 0.15f
            t -= kotlin.math.floor(t.toDouble()).toFloat()
            val k = when {
                t < 0.30f -> t / 0.30f
                t < 0.60f -> 1f - (t - 0.30f) / 0.30f
                else -> 0f
            }
            p.alpha = (255 * (0.55f + 0.45f * k)).toInt().coerceIn(0, 255)
            val cx = i * (dot + gap) + dot / 2f
            val cy = baseTop - bounce * k + dot / 2f
            c.drawCircle(cx, cy, dot / 2f, p)
        }
    }

    /** Bubble ke andar isko jitni jagah chahiye (3 dot + 2 gap, bounce ke liye height). */
    fun desiredWidth(): Int = (31 * resources.displayMetrics.density).toInt()
    fun desiredHeight(): Int = (11 * resources.displayMetrics.density).toInt()
}
