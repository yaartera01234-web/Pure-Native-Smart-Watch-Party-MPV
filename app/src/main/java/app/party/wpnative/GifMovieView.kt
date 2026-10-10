package app.party.wpnative

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Lightweight animated-GIF view: visible bubble hi animate hota hai, bytes MediaCache mein. */
@Suppress("DEPRECATION")
class GifMovieView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 190
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private var movie: Movie? = null
    private var startedAt = 0L
    private var boundKey = ""
    private var generation = 0
    private var loading = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.argb(35, 255, 255, 255))
    }

    fun bind(cacheKey: String, httpsUrl: String) {
        if (cacheKey == boundKey && (movie != null || loading)) return
        boundKey = cacheKey
        generation++
        val mine = generation
        movie = null
        startedAt = 0L
        loading = true
        Thread {
            var bytes = MediaCache.load(context, cacheKey)
            var downloaded = false
            if (bytes == null && httpsUrl.startsWith("https://", ignoreCase = true)) {
                bytes = download(httpsUrl)
                downloaded = bytes != null
            }
            post {
                if (mine != generation || cacheKey != boundKey) return@post
                if (bytes != null) {
                    if (downloaded) MediaCache.save(context, cacheKey, bytes!!)
                    install(cacheKey, mine, bytes!!)
                } else {
                    loading = false
                    invalidate()
                }
            }
        }.start()
    }

    fun clear() {
        generation++
        boundKey = ""
        movie = null
        loading = false
        invalidate()
    }

    private fun install(cacheKey: String, token: Int, bytes: ByteArray): Boolean {
        if (token != generation || cacheKey != boundKey) return false
        val decoded = try { Movie.decodeByteArray(bytes, 0, bytes.size) } catch (_: Throwable) { null }
        if (decoded == null) {
            loading = false
            invalidate()
            return false
        }
        movie = decoded
        loading = false
        startedAt = android.os.SystemClock.uptimeMillis()
        requestLayout()
        invalidate()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = (220 * density).toInt()
        val maxH = (240 * density).toInt()
        val minW = (150 * density).toInt()
        val minH = (110 * density).toInt()
        val m = movie
        var w = if (m != null && m.width() > 0) m.width() else (190 * density).toInt()
        var h = if (m != null && m.height() > 0) m.height() else (150 * density).toInt()
        val scale = minOf(1f, maxW.toFloat() / w.coerceAtLeast(1), maxH.toFloat() / h.coerceAtLeast(1))
        w = (w * scale).toInt().coerceAtLeast(minW)
        h = (h * scale).toInt().coerceAtLeast(minH)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = movie
        if (m == null) {
            canvas.drawText(if (loading) "GIF…" else "GIF unavailable",
                width / 2f, height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
            return
        }
        val duration = m.duration().takeIf { it > 0 } ?: 1000
        val now = android.os.SystemClock.uptimeMillis()
        m.setTime(((now - startedAt) % duration).toInt())
        val sx = width.toFloat() / m.width().coerceAtLeast(1)
        val sy = height.toFloat() / m.height().coerceAtLeast(1)
        val scale = minOf(sx, sy)
        val dx = (width - m.width() * scale) / 2f
        val dy = (height - m.height() * scale) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f)
        canvas.restore()
        if (visibility == VISIBLE && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun download(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("User-Agent", "WatchParty-Android")
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_REMOTE_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray().takeIf(::isGif)
            }
        } catch (_: Throwable) { null }
        finally { try { connection?.disconnect() } catch (_: Throwable) { } }
    }

    companion object {
        private const val MAX_REMOTE_BYTES = 8 * 1024 * 1024
        fun isGif(bytes: ByteArray): Boolean = bytes.size >= 6 &&
            String(bytes, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }
    }
}
