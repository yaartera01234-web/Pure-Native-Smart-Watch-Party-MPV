package app.party.wpnative

import android.os.Handler
import android.os.Looper
import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Original Smart Music search chain, now pure native:
 *
 *  1) Piped instances (no key/quota), 3.2s each, failed host 10 minutes benched.
 *  2) Existing YouTube Data API key after 6s or immediately when every Piped host fails.
 *  3) First useful answer wins; stale/cancelled searches never repaint the dialog.
 */
internal object YouTubeSearch {
    private const val CACHE_MS = 10 * 60 * 1000L
    private const val BENCH_MS = 10 * 60 * 1000L
    private const val PIPED_TIMEOUT_MS = 3_200
    private const val API_TIMEOUT_MS = 8_000
    private const val API_RACE_DELAY_MS = 6_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /* Same fallback key as the authoritative Original page. Piped remains primary. */
    private const val YOUTUBE_API_KEY = "AIzaSyD1o9TiriHymDsPvpadIsccQDHf7lJPZFo"

    private val piped = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.ducks.party",
        "https://pipedapi.adminforge.de",
        "https://api.piped.yt"
    )

    data class Item(
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSeconds: Long = 0L
    )

    data class Outcome(
        val items: List<Item>,
        val source: String,
        val instance: String
    )

    class Handle internal constructor(private val cancelAction: () -> Unit) {
        fun cancel() = cancelAction()
    }

    private data class Cached(val at: Long, val outcome: Outcome)
    private val cache = LinkedHashMap<String, Cached>()
    private val bench = HashMap<String, Long>()
    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "wp-youtube-search").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wp-youtube-search-race").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun search(query: String, done: (Outcome?) -> Unit): Handle {
        val clean = query.trim()
        val key = clean.lowercase(Locale.ROOT)
        val run = Run(done)
        if (clean.isBlank()) {
            main.post { if (!run.cancelled.get()) done(null) }
            return Handle(run::cancel)
        }

        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && System.currentTimeMillis() - hit.at < CACHE_MS) {
                main.post { if (!run.cancelled.get()) done(hit.outcome.copy(source = "cache")) }
                return Handle(run::cancel)
            }
            cache.entries.removeAll { System.currentTimeMillis() - it.value.at >= CACHE_MS }
        }

        run.apiTimer = timer.schedule({ startApi(run, clean, key) },
            API_RACE_DELAY_MS, TimeUnit.MILLISECONDS)
        io.execute {
            val answer = runCatching { searchPiped(run, clean) }.getOrNull()
            synchronized(run.lock) { run.pipedFinished = true }
            if (answer != null) complete(run, key, answer)
            else {
                run.apiTimer?.cancel(false)
                startApi(run, clean, key)
                finishIfBothFailed(run)
            }
        }
        return Handle(run::cancel)
    }

    private fun startApi(run: Run, query: String, key: String) {
        synchronized(run.lock) {
            if (run.done.get() || run.cancelled.get() || run.apiStarted) return
            run.apiStarted = true
        }
        io.execute {
            val answer = runCatching { searchYouTubeApi(query) }.getOrNull()
            synchronized(run.lock) { run.apiFinished = true }
            if (answer != null) complete(run, key, answer) else finishIfBothFailed(run)
        }
    }

    private fun complete(run: Run, key: String, outcome: Outcome) {
        if (run.cancelled.get() || !run.done.compareAndSet(false, true)) return
        run.apiTimer?.cancel(false)
        synchronized(cache) {
            cache[key] = Cached(System.currentTimeMillis(), outcome)
            while (cache.size > 20) cache.remove(cache.keys.first())
        }
        main.post { if (!run.cancelled.get()) run.callback(outcome) }
    }

    private fun finishIfBothFailed(run: Run) {
        val failed = synchronized(run.lock) {
            run.pipedFinished && run.apiStarted && run.apiFinished
        }
        if (!failed || run.cancelled.get() || !run.done.compareAndSet(false, true)) return
        main.post { if (!run.cancelled.get()) run.callback(null) }
    }

    private fun searchPiped(run: Run, query: String): Outcome? {
        for (base in piped) {
            if (run.cancelled.get() || run.done.get()) return null
            val skip = synchronized(bench) {
                val failedAt = bench[base] ?: 0L
                System.currentTimeMillis() - failedAt < BENCH_MS
            }
            if (skip) continue
            try {
                val url = "$base/search?q=${enc(query)}&filter=videos"
                val root = getJson(url, PIPED_TIMEOUT_MS)
                val source = root.optJSONArray("items") ?: continue
                val out = ArrayList<Item>()
                for (i in 0 until source.length()) {
                    val value = source.optJSONObject(i) ?: continue
                    if (value.optString("type") != "stream") continue
                    val id = videoId(value.optString("url")) ?: continue
                    val title = cleanText(value.optString("title"))
                    if (title.isBlank()) continue
                    out += Item(
                        videoId = id,
                        title = title,
                        channel = cleanText(value.optString("uploaderName")).ifBlank { "YouTube" },
                        durationSeconds = value.optLong("duration", 0L).coerceAtLeast(0L)
                    )
                    if (out.size == 20) break
                }
                if (out.isEmpty()) throw IllegalStateException("empty")
                return Outcome(out, "Piped", base.removePrefix("https://"))
            } catch (_: Throwable) {
                synchronized(bench) { bench[base] = System.currentTimeMillis() }
            }
        }
        return null
    }

    private fun searchYouTubeApi(query: String): Outcome? {
        if (YOUTUBE_API_KEY.isBlank()) return null
        val api = "https://www.googleapis.com/youtube/v3/"
        val search = getJson(
            api + "search?part=snippet&type=video&videoEmbeddable=true&maxResults=20&q=" +
                enc(query) + "&key=" + enc(YOUTUBE_API_KEY),
            API_TIMEOUT_MS
        )
        if (search.has("error")) return null
        val raw = search.optJSONArray("items") ?: return null
        val items = ArrayList<Item>()
        for (i in 0 until raw.length()) {
            val value = raw.optJSONObject(i) ?: continue
            val id = value.optJSONObject("id")?.optString("videoId").orEmpty()
            if (!VIDEO_ID.matches(id)) continue
            val snippet = value.optJSONObject("snippet")
            val title = cleanText(snippet?.optString("title").orEmpty())
            if (title.isBlank()) continue
            items += Item(id, title,
                cleanText(snippet?.optString("channelTitle").orEmpty()).ifBlank { "YouTube" })
            if (items.size == 20) break
        }
        if (items.isEmpty()) return null

        // Duration is a cheap videos.list call. Search results remain useful if it fails.
        val withDurations = try {
            val ids = items.joinToString(",") { it.videoId }
            val details = getJson(api + "videos?part=contentDetails&id=" + enc(ids) +
                "&key=" + enc(YOUTUBE_API_KEY), API_TIMEOUT_MS)
            val durations = HashMap<String, Long>()
            val values = details.optJSONArray("items")
            if (values != null) for (i in 0 until values.length()) {
                val value = values.optJSONObject(i) ?: continue
                durations[value.optString("id")] = isoDuration(
                    value.optJSONObject("contentDetails")?.optString("duration").orEmpty())
            }
            items.map { it.copy(durationSeconds = durations[it.videoId] ?: 0L) }
        } catch (_: Throwable) { items }

        return Outcome(withDurations, "YouTube backup", "youtube.googleapis.com")
    }

    private fun getJson(address: String, timeoutMs: Int): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
        }
        return try {
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299 || body.isBlank()) throw IllegalStateException("http")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun videoId(raw: String): String? {
        val exact = YtAudioSource.videoIdOf(raw)
        if (exact != null) return exact
        return Regex("(?:[?&]v=|/)([A-Za-z0-9_-]{11})(?:[?&#/]|$)")
            .find(raw)?.groupValues?.getOrNull(1)?.takeIf(VIDEO_ID::matches)
    }

    private fun cleanText(raw: String): String = try {
        @Suppress("DEPRECATION")
        Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    } catch (_: Throwable) { raw.trim() }

    private fun isoDuration(value: String): Long {
        val match = ISO_DURATION.matchEntire(value) ?: return 0L
        val day = match.groupValues[1].toLongOrNull() ?: 0L
        val hour = match.groupValues[2].toLongOrNull() ?: 0L
        val minute = match.groupValues[3].toLongOrNull() ?: 0L
        val second = match.groupValues[4].toLongOrNull() ?: 0L
        return day * 86_400L + hour * 3_600L + minute * 60L + second
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val ISO_DURATION = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$")

    private class Run(val callback: (Outcome?) -> Unit) {
        val lock = Any()
        val done = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        var pipedFinished = false
        var apiStarted = false
        var apiFinished = false
        var apiTimer: ScheduledFuture<*>? = null
        fun cancel() {
            cancelled.set(true)
            done.set(true)
            apiTimer?.cancel(false)
        }
    }
}
