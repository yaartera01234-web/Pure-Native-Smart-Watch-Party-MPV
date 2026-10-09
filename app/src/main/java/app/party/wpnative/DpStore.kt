package app.party.wpnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * **DP (profile picture) — sab jagah ek hi jagah se.**
 *
 *  - Apni DP: join page par chuni hui (📷 Photo / 🎲 Cartoon) → `wp_native` prefs
 *  - Dost ki DP: cartoon jo **naam se** banta hai → dono phone par bilkul ek jaisa
 *
 * Memory + phone (disk) dono mein cache — baar baar net nahi chalta.
 * Image aane tak **pehla harf** (rangin golay mein) dikhta hai, phir DP aa jati hai.
 */
object DpStore {

    private const val PREF = "wp_native"
    private const val TYPE_KEY = "avatarType"
    private const val DATA_KEY = "avatarData"

    private val mem = HashMap<String, Bitmap>()
    private val missed = HashSet<String>()
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun myType(ctx: Context) = prefs(ctx).getString(TYPE_KEY, "letter") ?: "letter"
    private fun myData(ctx: Context) = prefs(ctx).getString(DATA_KEY, null)

    /** Naam se cartoon DP (dono phone par ek jaisa). */
    fun cartoonUrl(name: String): String =
        "https://api.dicebear.com/9.x/fun-emoji/png?seed=" +
            URLEncoder.encode(name.trim().lowercase().ifEmpty { "wp" }, "UTF-8") + "&size=128"

    /** Cache ki chaabi — apni DP badalte hi nayi ban jaye. */
    private fun keyFor(ctx: Context, name: String, isMe: Boolean): String =
        if (isMe) "me|" + (myData(ctx)?.hashCode() ?: name)
        else "u|" + name.trim().lowercase()

    /** DP lao — cache ho to turant, warna background se (phone mein bhi save). */
    fun load(ctx: Context, name: String, isMe: Boolean = false, cb: (Bitmap) -> Unit) {
        val key = keyFor(ctx, name, isMe)
        synchronized(lock) {
            mem[key]?.let { cb(it); return }
            if (key in missed) return
        }
        io.execute {
            val bmp = try { fetch(ctx, key, name, isMe) } catch (t: Throwable) { null }
            synchronized(lock) {
                if (bmp != null) mem[key] = bmp else missed.add(key)
            }
            if (bmp != null) main.post { cb(bmp) }
        }
    }

    private fun fetch(ctx: Context, key: String, name: String, isMe: Boolean): Bitmap? {
        disk(ctx, key)?.let { return it }
        val bmp = if (isMe && myType(ctx) == "upload") decodeBase64(myData(ctx))
                  else targetUrl(ctx, name, isMe)?.let { download(it) }
        if (bmp != null) saveDisk(ctx, key, bmp)
        return bmp
    }

    private fun targetUrl(ctx: Context, name: String, isMe: Boolean): String? {
        if (!isMe) return cartoonUrl(name)
        return when (myType(ctx)) {
            "dicebear" -> myData(ctx)      // join page par chuna hua cartoon
            else -> null                   // upload = base64, letter = koi image nahi
        }
    }

    private fun decodeBase64(s: String?): Bitmap? {
        if (s.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(s, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) { null }
    }

    private fun download(url: String): Bitmap? = try {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (t: Throwable) { null }

    // ------------------------------------------------------------ cache (phone)

    private fun file(ctx: Context, key: String): File =
        File(ctx.cacheDir, "dp_" + key.hashCode().toString().replace("-", "m") + ".png")

    private fun disk(ctx: Context, key: String): Bitmap? {
        val f = file(ctx, key)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (t: Throwable) { null }
    }

    private fun saveDisk(ctx: Context, key: String, bmp: Bitmap) {
        try {
            FileOutputStream(file(ctx, key)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (t: Throwable) { }
    }

    // ------------------------------------------------------------ ready-made gol DP

    /**
     * Gol DP ka View: pehle **pehla harf** (rangin golay mein), image aate hi wo upar.
     * Chat ki har line, call history, inbox — sab yahi use karein.
     */
    fun circle(ctx: Context, name: String, color: Int, sizeDp: Int, isMe: Boolean = false): View {
        val frame = FrameLayout(ctx)

        val letter = TextView(ctx).apply {
            text = name.trim().firstOrNull()?.uppercase() ?: "?"
            textSize = sizeDp * 0.42f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }

        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
        }

        frame.addView(letter, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(img, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        load(ctx, name, isMe) { bmp ->
            img.setImageBitmap(bmp)
            img.visibility = View.VISIBLE
        }
        return frame
    }
}
