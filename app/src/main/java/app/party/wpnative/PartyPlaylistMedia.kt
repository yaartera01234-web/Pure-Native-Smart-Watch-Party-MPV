package app.party.wpnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** YouTube playlist thumbnail/title — website ke mqdefault + oEmbed behavior ka native version. */
object PartyPlaylistMedia {
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val mem = HashMap<String, Bitmap>()

    fun thumbnail(ctx: Context, videoId: String, target: ImageView) {
        if (videoId.isBlank()) return
        target.tag = videoId
        synchronized(mem) { mem[videoId] }?.let { target.setImageBitmap(it); return }
        io.execute {
            val bmp = try {
                val file = File(ctx.cacheDir, "pl_${videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.jpg")
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else {
                    val got = URL("https://img.youtube.com/vi/$videoId/mqdefault.jpg").openConnection().let { raw ->
                        (raw as HttpURLConnection).apply {
                            connectTimeout = 7000; readTimeout = 7000; useCaches = true
                        }.inputStream.use { BitmapFactory.decodeStream(it) }
                    }
                    if (got != null) try { FileOutputStream(file).use { got.compress(Bitmap.CompressFormat.JPEG, 82, it) } } catch (_: Throwable) {}
                    got
                }
            } catch (_: Throwable) { null }
            if (bmp != null) {
                synchronized(mem) { mem[videoId] = bmp }
                main.post { if (target.tag == videoId) target.setImageBitmap(bmp) }
            }
        }
    }

    fun title(videoId: String, done: (String) -> Unit) {
        if (videoId.isBlank()) return
        io.execute {
            val title = try {
                val watch = "https://www.youtube.com/watch?v=" + URLEncoder.encode(videoId, "UTF-8")
                val url = URL("https://www.youtube.com/oembed?url=${URLEncoder.encode(watch, "UTF-8")}&format=json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 7000
                }
                conn.inputStream.bufferedReader().use { JSONObject(it.readText()).optString("title") }
            } catch (_: Throwable) { "" }
            if (title.isNotBlank()) main.post { done(title.take(120)) }
        }
    }
}
