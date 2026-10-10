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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Profile pictures ka single source of truth.
 *
 * Apni selected photo/cartoon `wp_native` mein rehti hai. Doosre members ki advertised
 * photo/cartoon Friend Code profile ya encrypted Room presence se [rememberRemote] mein
 * aati hai. Sirf jab kisi purane/unknown contact ka profile abhi available na ho tab naam
 * se deterministic DiceBear fallback dikhaya jata hai.
 */
object DpStore {

    data class Avatar(val type: String, val data: String = "")

    private const val PREF = "wp_native"
    private const val TYPE_KEY = "avatarType"
    private const val DATA_KEY = "avatarData"
    private const val REMOTE_PREF = "wp_remote_avatars"
    private const val REMOTE_KEY = "profiles_v1"
    private const val MAX_SHARED_AVATAR = 300_000

    private val mem = HashMap<String, Bitmap>()
    private val missed = HashSet<String>()
    private val revisions = HashMap<String, Long>()
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun myAvatar(ctx: Context): Avatar {
        val type = normalizeType(prefs(ctx).getString(TYPE_KEY, "letter"))
        val data = prefs(ctx).getString(DATA_KEY, "").orEmpty()
        return if (type == "letter" || data.isBlank() || data.length > MAX_SHARED_AVATAR) Avatar("letter")
        else Avatar(type, data)
    }

    /** Public profile/Room presence mein bhejne ke liye already-compressed safe payload. */
    fun shareableAvatar(ctx: Context): Avatar = myAvatar(ctx)

    /** Naam se cartoon DP (legacy/unknown contact fallback). */
    fun cartoonUrl(name: String): String =
        "https://api.dicebear.com/9.x/fun-emoji/png?seed=" +
            URLEncoder.encode(name.trim().lowercase(Locale.ROOT).ifEmpty { "wp" }, "UTF-8") + "&size=128"

    private fun normalizedName(name: String) = name.trim().lowercase(Locale.ROOT).take(80)

    private fun normalizeType(value: String?): String = when (value?.lowercase(Locale.ROOT)) {
        "upload" -> "upload"
        "dicebear" -> "dicebear"
        else -> "letter"
    }

    private fun remoteMap(ctx: Context): JSONObject {
        val raw = ctx.getSharedPreferences(REMOTE_PREF, Context.MODE_PRIVATE)
            .getString(REMOTE_KEY, "{}").orEmpty()
        return try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
    }

    fun remoteAvatar(ctx: Context, name: String): Avatar? {
        val key = normalizedName(name)
        if (key.isBlank()) return null
        val value = remoteMap(ctx).optJSONObject(key) ?: return null
        val type = normalizeType(value.optString("type", "letter"))
        val data = value.optString("data", "")
        return if (type == "letter") Avatar("letter")
        else data.takeIf { it.isNotBlank() && it.length <= MAX_SHARED_AVATAR }?.let { Avatar(type, it) }
    }

    /**
     * Friend directory/Room se mili asli DP yaad rakho. Return true ka matlab visible
     * avatar badla; caller apni already-bound rows ko refresh kar sakta hai.
     */
    fun rememberRemote(ctx: Context, name: String, typeValue: String?, dataValue: String?): Boolean {
        val nameKey = normalizedName(name)
        if (nameKey.isBlank() || typeValue.isNullOrBlank()) return false
        val type = normalizeType(typeValue)
        val data = dataValue.orEmpty()
        if (data.length > MAX_SHARED_AVATAR || (type != "letter" && data.isBlank())) return false
        val next = Avatar(type, if (type == "letter") "" else data)
        val old = remoteAvatar(ctx, name)
        if (old == next) return false

        val oldCacheKey = keyFor(ctx, name, false)
        val all = remoteMap(ctx)
        all.put(nameKey, JSONObject().put("type", next.type).put("data", next.data))
        ctx.getSharedPreferences(REMOTE_PREF, Context.MODE_PRIVATE)
            .edit().putString(REMOTE_KEY, all.toString()).apply()
        val newCacheKey = keyFor(ctx, name, false)

        synchronized(lock) {
            mem.remove(oldCacheKey); mem.remove(newCacheKey)
            missed.remove(oldCacheKey); missed.remove(newCacheKey)
            revisions[nameKey] = (revisions[nameKey] ?: 0L) + 1L
        }
        try { file(ctx, oldCacheKey).delete() } catch (_: Throwable) { }
        return true
    }

    fun revision(name: String): Long = synchronized(lock) { revisions[normalizedName(name)] ?: 0L }

    /** DP badalne par cache key bhi badalti hai, is liye purana cartoon/photo wapas nahi aata. */
    private fun keyFor(ctx: Context, name: String, isMe: Boolean): String {
        val avatar = if (isMe) myAvatar(ctx) else remoteAvatar(ctx, name)
        val sig = avatar?.let { "${it.type}|${it.data.hashCode()}" } ?: "fallback"
        return (if (isMe) "me|" else "u|${normalizedName(name)}|") + sig
    }

    /** DP lao — cache ho to turant, warna background se phone/network par resolve karo. */
    fun load(ctx: Context, name: String, isMe: Boolean = false, cb: (Bitmap) -> Unit) {
        val app = ctx.applicationContext
        val key = keyFor(app, name, isMe)
        synchronized(lock) {
            mem[key]?.let { cb(it); return }
            if (key in missed) return
        }
        io.execute {
            val bmp = try { fetch(app, key, name, isMe) } catch (_: Throwable) { null }
            synchronized(lock) {
                if (bmp != null) mem[key] = bmp else missed.add(key)
            }
            if (bmp != null) main.post { cb(bmp) }
        }
    }

    private fun fetch(ctx: Context, key: String, name: String, isMe: Boolean): Bitmap? {
        disk(ctx, key)?.let { return it }
        val avatar = if (isMe) myAvatar(ctx) else remoteAvatar(ctx, name)
        val bmp = when (avatar?.type) {
            "upload" -> decodeBase64(avatar.data)
            "dicebear" -> download(avatar.data)
            "letter" -> null
            else -> download(cartoonUrl(name))
        }
        if (bmp != null) saveDisk(ctx, key, bmp)
        return bmp
    }

    private fun decodeBase64(s: String?): Bitmap? {
        if (s.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(s, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) { null }
    }

    private fun download(url: String): Bitmap? = try {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) { null }

    // ------------------------------------------------------------ cache (phone)

    private fun file(ctx: Context, key: String): File =
        File(ctx.cacheDir, "dp_" + key.hashCode().toString().replace("-", "m") + ".png")

    private fun disk(ctx: Context, key: String): Bitmap? {
        val f = file(ctx, key)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
    }

    private fun saveDisk(ctx: Context, key: String, bmp: Bitmap) {
        try {
            FileOutputStream(file(ctx, key)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Throwable) { }
    }

    /** Friend hat gaya -> uski original/fallback DP aur descriptor bhi local phone se saaf. */
    fun forget(ctx: Context, name: String) {
        val nameKey = normalizedName(name)
        val oldKey = keyFor(ctx, name, false)
        val all = remoteMap(ctx)
        all.remove(nameKey)
        ctx.getSharedPreferences(REMOTE_PREF, Context.MODE_PRIVATE)
            .edit().putString(REMOTE_KEY, all.toString()).apply()
        synchronized(lock) {
            mem.keys.filter { it.startsWith("u|$nameKey|") }.forEach(mem::remove)
            missed.removeAll { it.startsWith("u|$nameKey|") }
            revisions.remove(nameKey)
        }
        try { file(ctx, oldKey).delete() } catch (_: Throwable) { }
        try { file(ctx, "u|$nameKey|fallback").delete() } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------ ready-made gol DP

    /** Gol DP: image resolve hone tak first-letter placeholder. */
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
