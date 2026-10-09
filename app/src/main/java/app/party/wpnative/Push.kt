package app.party.wpnative

import android.content.Context
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Doosre phone tak **push** pahunchana (jab wo app background/band kiye ho).
 *
 * Kyun relay chahiye: FCM message bhejne ke liye server-side key chahiye hoti hai,
 * jo app ke andar nahi rakhte (security). Is liye ek chhota **Cloudflare Worker**
 * (free, bina card) ye kaam karega.
 *
 * RELAY_URL khali hai to push skip ho jata hai — app khuli ho to message
 * Firestore se turant aa hi jata hai, to koi farq nahi padta.
 */
object Push {

    /** Relay ka URL — setup ke baad yahan likha jayega (Cloudflare Worker ya Apps Script). */
    private const val RELAY_URL = ""

    /** Apps Script wala chhota secret (us file mein APP_KEY ke barabar). */
    private const val RELAY_KEY = "wp-2026"

    private val io = Executors.newSingleThreadExecutor()

    fun isConfigured(): Boolean = RELAY_URL.isNotBlank()

    /** Peer ko push bhejo — sirf tab jab wo online na ho / app background mein ho. */
    fun notifyPeer(ctx: Context, peer: String, from: String, text: String) {
        if (!isConfigured() || !FirebaseChat.isReady(ctx)) return
        FirebaseChat.getToken(ctx, peer) { token, online ->
            if (token.isNullOrBlank() || online || WpActive.peer == peer) return@getToken
            io.execute {
                try {
                    val body = JSONObject().apply {
                        put("token", token)
                        put("title", from)
                        put("body", text)
                        put("from", from)
                        put("chatId", WpUser.chatId(from, peer))
                        put("key", RELAY_KEY)
                    }.toString()
                    post(body)
                } catch (t: Throwable) {
                    // push fail ho to koi baat nahi — app kholne par message mil jayega
                }
            }
        }
    }

    /**
     * Relay ko POST. Google Apps Script 302 deta hai (redirect), is liye
     * ek baar Location par dobara POST karte hain.
     */
    private fun post(body: String) {
        var url = RELAY_URL
        repeat(2) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if ((code == 301 || code == 302 || code == 307 || code == 308)
                    && !loc.isNullOrBlank() && attempt == 0) {
                    url = loc                    // Apps Script wala redirect
                    return@repeat
                }
                return
            } catch (t: Throwable) {
                return
            }
        }
    }
}
