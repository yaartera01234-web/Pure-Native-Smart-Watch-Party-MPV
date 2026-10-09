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

    /** Cloudflare Worker ka URL — setup ke baad yahan likha jayega. */
    private const val RELAY_URL = ""

    private val io = Executors.newSingleThreadExecutor()

    fun isConfigured(): Boolean = RELAY_URL.isNotBlank()

    /** Peer ko push bhejo — sirf tab jab wo online na ho / app background mein ho. */
    fun notifyPeer(ctx: Context, peer: String, from: String, text: String) {
        if (!isConfigured() || !FirebaseChat.isReady(ctx)) return
        FirebaseChat.getToken(ctx, peer) { token, online ->
            if (token.isNullOrBlank() || online || WpActive.peer == peer) return@getToken
            io.execute {
                try {
                    val conn = URL(RELAY_URL).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = JSONObject().apply {
                        put("token", token)
                        put("title", from)
                        put("body", text)
                        put("from", from)
                        put("chatId", WpUser.chatId(from, peer))
                    }.toString()
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    conn.responseCode
                    conn.disconnect()
                } catch (t: Throwable) {
                    // push fail ho to koi baat nahi — app kholne par message mil jayega
                }
            }
        }
    }
}
