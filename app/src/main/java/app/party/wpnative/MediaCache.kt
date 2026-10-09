package app.party.wpnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Photo / voice ka **phone-wala** khazana + photo chhoti karna.
 *
 * Firestore ki har document ki had 1 MB hai, is liye photo ko
 * ~800px / quality 65 tak daba kar bhejte hain (~70-100 KB → base64 ~130 KB).
 * Display 220px hai, to 800px fullscreen ke liye bhi kaafi hai.
 *
 * Ek baar download hui media phone mein save ho jati hai — dobara net nahi chalta.
 */
object MediaCache {

    private const val MAX_PX = 800
    private const val MAX_BYTES = 620 * 1024      // base64 ~830KB (Firestore had 1MB)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun file(ctx: Context, key: String): File =
        File(ctx.filesDir, "m_" + key.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    /** Media ka asli fayl (voice chalane ke liye MediaPlayer ko rasta chahiye). */
    fun fileOf(ctx: Context, key: String): File? =
        try { val f = file(ctx, key); if (f.exists()) f else null } catch (t: Throwable) { null }

    fun has(ctx: Context, key: String): Boolean =
        try { file(ctx, key).exists() } catch (t: Throwable) { false }

    fun save(ctx: Context, key: String, bytes: ByteArray) {
        try { FileOutputStream(file(ctx, key)).use { it.write(bytes) } } catch (t: Throwable) { }
    }

    /** Bhejne ke baad chaabi badal do (L<waqt> -> asli Firestore id). */
    fun rename(ctx: Context, from: String, to: String) {
        try {
            val a = file(ctx, from); val b = file(ctx, to)
            if (a.exists() && !b.exists()) a.renameTo(b)
        } catch (t: Throwable) { }
    }

    /** Phone se ye media mita do (jab Firestore se bhi hat chuki ho). */
    fun delete(ctx: Context, key: String) {
        try { file(ctx, key).delete() } catch (t: Throwable) { }
    }

    /**
     * Phone ki woh purani media mitao jo ab kahin kaam ki nahi.
     * `keep` = woh chaabiyan jin ki media abhi Firestore mein maujood hai.
     */
    fun purgeOlderThan(ctx: Context, cutoff: Long, keep: Set<String>) {
        try {
            val dir = ctx.filesDir
            val names = dir.list() ?: return
            for (n in names) {
                if (!n.startsWith("m_")) continue
                val f = File(dir, n)
                if (f.isDirectory || f.lastModified() > cutoff) continue
                val key = n.removePrefix("m_")
                if (keep.contains(key)) continue        // abhi chahiye — mat mitao
                f.delete()
            }
        } catch (t: Throwable) { }
    }

    fun load(ctx: Context, key: String): ByteArray? =
        try { val f = file(ctx, key); if (f.exists()) f.readBytes() else null } catch (t: Throwable) { null }

    /** Gallery ki photo chhoti karo (Firestore ki 1MB had ke andar rakhne ke liye). */
    fun compress(ctx: Context, uri: Uri): ByteArray? {
        return try {
            val bmp = decode(ctx, uri) ?: return null
            var q = 70
            var out = encode(bmp, q)
            while (out.size > MAX_BYTES && q > 35) { q -= 12; out = encode(bmp, q) }
            out
        } catch (t: Throwable) { null }
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap? {
        val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o1) }
        var sample = 1
        val max = Math.max(o1.outWidth, o1.outHeight)
        while (max / sample > MAX_PX) sample *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    private fun encode(bmp: Bitmap, q: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
        return bos.toByteArray()
    }

    /** Phone mein padi media se bitmap banao (background mein, screen na ruke). */
    fun bitmapAsync(ctx: Context, key: String, cb: (Bitmap?) -> Unit) {
        io.execute {
            val bmp = load(ctx, key)?.let { decodeBytes(it) }
            main.post { cb(bmp) }
        }
    }

    fun decodeBytes(bytes: ByteArray): Bitmap? =
        try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (t: Throwable) { null }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.DEFAULT)
    fun unb64(s: String): ByteArray? =
        try { Base64.decode(s, Base64.DEFAULT) } catch (t: Throwable) { null }
}
