package app.party.wpnative

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Exact semantic paint tokens from party-final1.html. Geometry stays in the native
 * screens; this is the one source of truth for every themed surface and control.
 */
internal data class WpGlow(val x: Float, val y: Float, val radius: Float, val color: Int)

internal data class WpTheme(
    val key: String,
    val name: String,
    val bg: IntArray,
    val j: IntArray,
    val page: IntArray,
    val glows: List<WpGlow>,
    val joinCard: IntArray,
    val panel: IntArray,
    val head: IntArray,
    val soft: Int,
    val soft2: Int,
    val chip: Int,
    val inputBar: Int,
    val input: IntArray,
    val menu: IntArray,
    val music: IntArray,
    val musicGlowA: Int,
    val musicGlowB: Int,
    val fill: IntArray,
    val fill2: IntArray,
    val dot: Int,
    val art: IntArray,
    val bar: IntArray,
    val focus: Int,
    val accentText: Int,
    val joinStroke: Int,
    val panelStroke: Int,
    val itemStroke: Int,
    val headerStroke: Int,
    val inputStroke: Int,
    val sourcePlay: IntArray,
    val buttonText: Int
) {
    val accent: IntArray get() = j
    val glowA: Int get() = glows.getOrNull(0)?.color ?: Color.TRANSPARENT
    val glowB: Int get() = glows.getOrNull(1)?.color ?: Color.TRANSPARENT
    val glowC: Int get() = glows.getOrNull(2)?.color ?: Color.TRANSPARENT
}

internal object WpThemes {
    const val DEFAULT_INDEX = 0 // Lobby Neon; saved user choices always override this.

    private fun c(value: String) = Color.parseColor(value)
    private fun cs(vararg values: String) = values.map(::c).toIntArray()
    private fun glow(x: Float, y: Float, radius: Float, value: String) = WpGlow(x, y, radius, c(value))

    /** Native order is intentionally preserved for existing numeric preferences. */
    val all: List<WpTheme> = listOf(
        WpTheme(
            "neon", "Lobby Neon (default)", cs("#050719", "#16112d", "#0b1829"),
            cs("#fa35de", "#c03cff", "#36d9fa"), cs("#050719", "#050719"),
            listOf(glow(1f, 0f, .58f, "#42721d75"), glow(0f, 1f, .58f, "#3d146183")),
            cs("#ef291337", "#ee121d35"), cs("#e81e1431", "#e80d1d33"),
            cs("#ed231036", "#ed111b34"), c("#80331640"), c("#80152439"), c("#26b44bed"),
            c("#ed101328"), cs("#ff2a153c", "#ff132a40"), cs("#fa1c122c", "#fa1c122c"),
            cs("#ff27133f", "#ff27133f"), c("#99f043e8"), c("#6622d2e1"),
            cs("#fa35de", "#b564ff", "#66efff"), cs("#fa35de", "#c03cff", "#8260ff", "#36d9fa"),
            c("#f88bed"), cs("#dd2dd5", "#8b46ec", "#34bce0"), cs("#8bedff", "#ee91ff"),
            c("#ed9cff"), c("#e0acff"), c("#67d680f2"), c("#4fb95bf3"), c("#44c679e3"),
            c("#45c575ef"), c("#77dc77ef"), cs("#247b84", "#4867c7"), Color.WHITE
        ),
        WpTheme(
            "purple", "Night Purple", cs("#0f0c29", "#302b63", "#24243e"),
            cs("#ff66bd", "#a477ff", "#55baff"), cs("#080812", "#17132f", "#0a1127"),
            listOf(glow(.08f, -.10f, .48f, "#3dff64c3"), glow(1f, 0f, .47f, "#3d5b7eff"), glow(.5f, 1.15f, .52f, "#2e6f49d9")),
            cs("#d6211b44", "#d10c0d1f"), cs("#b81c183b", "#8c080918"), cs("#94060713", "#94060713"),
            c("#0effffff"), c("#05ffffff"), c("#13ffffff"), c("#3d050612"),
            cs("#5703040f", "#5703040f"), cs("#fa0d0a20", "#fa0d0a20"),
            cs("#15112e", "#251743", "#0b1228"), c("#4dff5ebc"), c("#2e54e8ff"),
            cs("#ff5ebc", "#8b72ff", "#54e8ff"), cs("#ff5ebc", "#8b72ff"), c("#ff5ebc"),
            cs("#ff66bd", "#8b5cf6", "#26318d"), cs("#54e8ff", "#ff5ebc"), c("#bfff5ebc"),
            c("#d4caff"), c("#2effffff"), c("#1fffffff"), c("#1affffff"), c("#21ffffff"),
            c("#24ffffff"), cs("#1AD07A", "#0ABF6A"), Color.WHITE
        ),
        WpTheme(
            "blue", "Ocean Cyan", cs("#051219", "#11232d", "#0b2927"),
            cs("#358efa", "#3cb7ff", "#36faed"), cs("#051219", "#051219"),
            listOf(glow(1f, 0f, .58f, "#421d5575"), glow(0f, 1f, .58f, "#3d14837c")),
            cs("#ef132a37", "#ee123533"), cs("#e8142631", "#e80d3330"),
            cs("#ed102836", "#ed113432"), c("#80163140"), c("#80153937"), c("#264bb2ed"),
            c("#ed101f28"), cs("#ff152e3c", "#ff13403d"), cs("#fa12222c", "#fa12222c"),
            cs("#ff132f3f", "#ff132f3f"), c("#994391f0"), c("#6622e1d4"),
            cs("#358efa", "#64c6ff", "#66fff5"), cs("#358efa", "#3cb7ff", "#60c5ff", "#36faed"),
            c("#93bdf0"), cs("#2d7cdd", "#46afec", "#34e0d5"), cs("#99f0eb", "#9fd3f1"),
            c("#a8d7f3"), c("#b6def5"), c("#6784c7ee"), c("#4f5bbbf3"), c("#4479bce3"),
            c("#4575c2ef"), c("#7777c3ef"), cs("#24847e", "#4898c7"), c("#031724")
        ),
        WpTheme(
            "sunset", "Sunset Rose", cs("#19050c", "#2d111b", "#29180b"),
            cs("#fa3549", "#ff3c83", "#fa8e36"), cs("#19050c", "#19050c"),
            listOf(glow(1f, 0f, .58f, "#42751d3d"), glow(0f, 1f, .58f, "#3d834614")),
            cs("#ef371320", "#ee352212"), cs("#e831141f", "#e8331e0d"),
            cs("#ed36101e", "#ed342111"), c("#80401625"), c("#80392515"), c("#26ed4b86"),
            c("#ed281019"), cs("#ff3c1523", "#ff402713"), cs("#fa2c121c", "#fa2c121c"),
            cs("#ff3f1323", "#ff3f1323"), c("#99f04354"), c("#66e17822"),
            cs("#fa3549", "#ff649d", "#ffab66"), cs("#fa3549", "#ff3c83", "#ff609a", "#fa8e36"),
            c("#f0939d"), cs("#dd2d3f", "#ec4683", "#e08134"), cs("#f0c199", "#f19fbd"),
            c("#f3a8c4"), c("#f5b6cd"), c("#67ee84ab"), c("#4ff35b93"), c("#44e379a0"),
            c("#45ef75a2"), c("#77ef77a3"), cs("#844f24", "#c74877"), Color.WHITE
        ),
        WpTheme(
            "emerald", "Emerald Glow", cs("#05190f", "#112d1f", "#0b2729"),
            cs("#35fac5", "#3cff9e", "#36eafa"), cs("#05190f", "#05190f"),
            listOf(glow(1f, 0f, .58f, "#421d7549"), glow(0f, 1f, .58f, "#3d147a83")),
            cs("#ef133725", "#ee123235"), cs("#e8143123", "#e80d3033"),
            cs("#ed103623", "#ed113134"), c("#8016402b"), c("#80153639"), c("#264bed9c"),
            c("#ed10281c"), cs("#ff153c29", "#ff133c40"), cs("#fa122c1f", "#fa122c1f"),
            cs("#ff133f29", "#ff133f29"), c("#9943f0c2"), c("#6622d1e1"),
            cs("#35fac5", "#64ffb2", "#66f2ff"), cs("#35fac5", "#3cff9e", "#60ffb0", "#36eafa"),
            c("#93f0d7"), cs("#2dddae", "#46ec99", "#34d2e0"), cs("#99e9f0", "#9ff1c8"),
            c("#a8f3ce"), c("#b6f5d6"), c("#6784eeb9"), c("#4f5bf3a7"), c("#4479e3ae"),
            c("#4575efb2"), c("#7777efb3"), cs("#247c84", "#48c788"), c("#041b12")
        ),
        WpTheme(
            "amoled", "Champagne Gold", cs("#191305", "#2d2511", "#29270b"),
            cs("#e88f47", "#eabc51", "#e8db48"), cs("#191305", "#191305"),
            listOf(glow(1f, 0f, .58f, "#42755b1d"), glow(0f, 1f, .58f, "#3d837a14")),
            cs("#ef372c13", "#ee353212"), cs("#e8312814", "#e833300d"),
            cs("#ed362b10", "#ed343111"), c("#80403316"), c("#80393615"), c("#26e9bb4f"),
            c("#ed282110"), cs("#ff3c3015", "#ff403c13"), cs("#fa2c2412", "#fa2c2412"),
            cs("#ff3f3213", "#ff3f3213"), c("#99e9924a"), c("#66e1d122"),
            cs("#e88f47", "#eeca75", "#eee477"), cs("#f0b756", "#e0b95f", "#f2dda0"),
            c("#f0bd93"), cs("#dd7c2d", "#e9b949", "#e0d234"), cs("#f0e999", "#f1d89f"),
            c("#f3dca8"), c("#f5e2b6"), c("#67eece84"), c("#4fecc262"), c("#44e3c379"),
            c("#45eeca76"), c("#77eecb78"), cs("#847c24", "#c7a148"), c("#291b07")
        )
    )

    private val themeBubble = mapOf(0 to 0, 2 to 8, 3 to 10, 4 to 9, 5 to 11)
    private fun isFour(index: Int) = index in setOf(2, 3, 4, 5)
    fun linkedBubble(index: Int): Int? = themeBubble[index]

    data class Selection(val theme: Int, val bubble: Int)

    /** The Original's Neon/four-theme bubble coupling, including previous-bubble restore. */
    fun select(prefs: SharedPreferences, oldTheme: Int, oldBubble: Int, newTheme: Int): Selection {
        var bubble = oldBubble
        var beforeNeon = prefs.getInt("bubble_prev_neon", 1).coerceIn(WpBubbles.all.indices)
        var beforeFour = prefs.getInt("bubble_prev_four", 1).coerceIn(WpBubbles.all.indices)
        val oldLinked = linkedBubble(oldTheme)

        if (isFour(oldTheme) && !isFour(newTheme) && bubble == oldLinked) bubble = beforeFour
        if (isFour(newTheme)) {
            if (oldTheme == 0 && bubble == 0) bubble = beforeNeon
            if (!isFour(oldTheme) || bubble != oldLinked) beforeFour = bubble
            bubble = linkedBubble(newTheme) ?: bubble
        }
        if (newTheme == 0 && oldTheme != 0) {
            beforeNeon = bubble
            bubble = 0
        } else if (newTheme != 0 && oldTheme == 0 && bubble == 0) {
            bubble = beforeNeon
        }
        prefs.edit()
            .putInt("theme", newTheme).putInt("bubble", bubble)
            .putInt("bubble_prev_neon", beforeNeon).putInt("bubble_prev_four", beforeFour)
            .apply()
        return Selection(newTheme, bubble)
    }

    /** One-time migration for users whose saved linked theme predates Original coupling. */
    fun migrateCoupling(prefs: SharedPreferences, theme: Int, bubble: Int): Selection {
        if (prefs.getBoolean("theme_coupling_v1", false)) return Selection(theme, bubble)
        val linked = linkedBubble(theme)
        val e = prefs.edit().putBoolean("theme_coupling_v1", true)
        var selected = bubble
        if (linked != null) {
            if (theme == 0) e.putInt("bubble_prev_neon", bubble)
            else e.putInt("bubble_prev_four", bubble)
            selected = linked
            e.putInt("bubble", selected)
        }
        e.apply()
        return Selection(theme, selected)
    }
}

internal data class WpBubbleTheme(
    val name: String,
    val short: String,
    val own: IntArray,
    val other: IntArray,
    val ownText: Int,
    val otherText: Int,
    val ownGlow: Int,
    val otherGlow: Int,
    val edge: Int?
)

internal object WpBubbles {
    const val LOBBY_NEON_INDEX = 0

    private fun c(value: String) = Color.parseColor(value)
    private fun cs(vararg values: String) = values.map(::c).toIntArray()
    val all: List<WpBubbleTheme> = listOf(
        WpBubbleTheme("Lobby Neon · Violet + Midnight", "Violet + Midnight",
            cs("#cf30bd", "#a342e3", "#6b55e4"), cs("#261c40", "#1d2340", "#12253b"),
            Color.WHITE, c("#efe8fb"), c("#33ba39e6"), c("#118750d0"), c("#4f976cce")),
        WpBubbleTheme("1 · Pink + Cyan (default)", "Pink + Cyan",
            cs("#f472b6", "#a78bfa"), cs("#51c9c2", "#7182e9", "#aa7ced"),
            Color.WHITE, Color.WHITE, c("#40a78bfa"), c("#3b5291dc"), null),
        WpBubbleTheme("2 · Orange + Blue", "Orange + Blue",
            cs("#fb923c", "#ea580c"), cs("#38bdf8", "#3b82f6", "#1d4ed8"),
            c("#3a1a04"), Color.WHITE, c("#66fb923c"), c("#663b82f6"), null),
        WpBubbleTheme("3 · Green + Magenta", "Green + Magenta",
            cs("#22c55e", "#15803d"), cs("#f472b6", "#e11d48", "#be123c"),
            c("#02240f"), Color.WHITE, c("#6622c55e"), c("#66e11d48"), null),
        WpBubbleTheme("4 · Yellow + Violet", "Yellow + Violet",
            cs("#facc15", "#eab308"), cs("#a78bfa", "#7c3aed", "#5b21b6"),
            c("#3a2d02"), Color.WHITE, c("#61facc15"), c("#6b7c3aed"), null),
        WpBubbleTheme("5 · Red + Teal", "Red + Teal",
            cs("#ef4444", "#b91c1c"), cs("#2dd4bf", "#14b8a6", "#0f766e"),
            Color.WHITE, c("#04201d"), c("#66ef4444"), c("#6b14b8a6"), null),
        WpBubbleTheme("6 · Lime + Purple", "Lime + Purple",
            cs("#a3e635", "#65a30d"), cs("#c084fc", "#8b5cf6", "#6d28d9"),
            c("#1a2e02"), Color.WHITE, c("#61a3e635"), c("#6b8b5cf6"), null),
        WpBubbleTheme("7 · Black + White", "Black + White",
            cs("#0b1220", "#1f2937"), cs("#ffffff", "#e2e8f0", "#cbd5e1"),
            Color.WHITE, c("#0b1220"), c("#8c000000"), c("#4dffffff"), null),
        WpBubbleTheme("Ocean Cyan · Matching bubbles", "Ocean Cyan",
            cs("#3078cf", "#42a8e3", "#55b0e4"), cs("#1c3340", "#1c3340", "#123b38"),
            c("#031724"), c("#e8f4fb"), c("#3339a7e6"), c("#1150a1d0"), c("#4f6caace")),
        WpBubbleTheme("Emerald Glow · Matching bubbles", "Emerald Glow",
            cs("#30cfa5", "#42e393", "#55e49d"), cs("#1c402e", "#1c402e", "#12383b"),
            c("#041b12"), c("#e8fbf2"), c("#3339e690"), c("#1150d090"), c("#4f6cce9d")),
        WpBubbleTheme("Sunset Rose · Matching bubbles", "Sunset Rose",
            cs("#ce3758", "#c72b66", "#ae275d"), cs("#401c29", "#401c29", "#3b2412"),
            Color.WHITE, c("#fbe8ef"), c("#33e63978"), c("#11d0507f"), c("#4fce6c90")),
        WpBubbleTheme("Champagne Gold · Matching bubbles", "Champagne Gold",
            cs("#e6b25b", "#dcaa4d", "#d1bc78"), cs("#40351c", "#40351c", "#3b3812"),
            c("#291b07"), c("#fbf5e8"), c("#33e6b239"), c("#11d0aa50"), c("#4fceb16c"))
    )
}

/** Page background with the Original's ordered linear base and radial aurora glows. */
internal class WpPageDrawable(
    private val theme: WpTheme,
    private val density: Float = 1f
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    /**
     * Dots are painted last with a very small alpha. Paint keeps that alpha between draw calls,
     * so a later invalidation (for example an EditText cursor blink while typing) used to make
     * the page shaders almost transparent and expose the grey window background. Reset every
     * shader pass explicitly so focus, typing and IME resize redraw the exact same full theme.
     */
    private fun useShader(shader: Shader) {
        paint.shader = shader
        paint.color = Color.WHITE
        paint.alpha = drawableAlpha
        paint.colorFilter = drawableColorFilter
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        useShader(LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            theme.page, null, Shader.TileMode.CLAMP
        ))
        canvas.drawRect(b, paint)
        theme.glows.forEach { glow ->
            val color = glow.color
            useShader(RadialGradient(
                b.left + glow.x * w, b.top + glow.y * h, max(w, h) * glow.radius,
                color, color and 0x00ffffff, Shader.TileMode.CLAMP
            ))
            canvas.drawRect(b, paint)
        }
        paint.shader = null
        paint.colorFilter = drawableColorFilter
        // body::before: .6px white dots at 5px, fading before the lower edge.
        val step = 5f * density
        val radius = .55f * density
        var y = b.top + step
        var row = 0
        while (y < b.top + h * .85f) {
            val fade = (1f - ((y - b.top) / (h * .85f))).coerceIn(0f, 1f)
            val dotAlpha = (13f * fade * drawableAlpha / 255f).toInt().coerceIn(0, 255)
            paint.color = Color.argb(dotAlpha, 255, 255, 255)
            var x = b.left + if (row % 2 == 0) step else step * .5f
            while (x < b.right) { canvas.drawCircle(x, y, radius, paint); x += step }
            y += step; row++
        }
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (drawableAlpha == next) return
        drawableAlpha = next
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (drawableColorFilter === colorFilter) return
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** CSS-like gradient chat bubble with glow, edge and inset top gloss. */
internal class WpBubbleDrawable(
    private val colors: IntArray,
    private val mine: Boolean,
    private val density: Float,
    private val glow: Int,
    private val border: Int?,
    private val glossy: Boolean,
    private val angle: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    private fun alphaColor(color: Int): Int {
        val alpha = Color.alpha(color) * drawableAlpha / 255
        return (color and 0x00ffffff) or (alpha shl 24)
    }

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        if (r.width() <= 0f || r.height() <= 0f) return
        val big = 16f * density
        val small = 4f * density
        val radii = if (mine)
            floatArrayOf(big, big, small, small, big, big, big, big)
        else floatArrayOf(small, small, big, big, big, big, big, big)
        path.reset(); path.addRoundRect(r, radii, android.graphics.Path.Direction.CW)

        val radians = Math.toRadians(angle.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val distance = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
        val cx = r.centerX(); val cy = r.centerY()
        paint.style = Paint.Style.FILL
        // Border/gloss are translucent and are painted last. Reset Paint before every fill;
        // otherwise their 25% alpha survives the next RecyclerView redraw and dims the whole
        // message bubble a few seconds after it was sent.
        paint.color = Color.WHITE
        paint.alpha = drawableAlpha
        paint.colorFilter = drawableColorFilter
        paint.shader = LinearGradient(cx - dx * distance, cy - dy * distance,
            cx + dx * distance, cy + dy * distance, colors,
            if (colors.size == 3) floatArrayOf(0f, .52f, 1f) else null, Shader.TileMode.CLAMP)
        if (glow != Color.TRANSPARENT) {
            paint.setShadowLayer(9f * density, 0f, 5f * density, alphaColor(glow))
        }
        canvas.drawPath(path, paint)
        paint.clearShadowLayer(); paint.shader = null

        if (border != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = alphaColor(border)
            canvas.drawPath(path, paint)
        }
        if (glossy) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density
            paint.color = Color.argb((if (mine) 64 else 69) * drawableAlpha / 255, 255, 255, 255)
            val inset = .75f * density
            val top = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            path.reset(); path.addRoundRect(top, radii, android.graphics.Path.Direction.CW)
            canvas.save(); canvas.clipRect(r.left, r.top, r.right, r.top + max(big, r.height() * .48f))
            canvas.drawPath(path, paint); canvas.restore()
        }
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (drawableAlpha == next) return
        drawableAlpha = next
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (drawableColorFilter === colorFilter) return
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
