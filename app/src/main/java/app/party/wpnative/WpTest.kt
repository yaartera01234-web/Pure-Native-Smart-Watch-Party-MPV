package app.party.wpnative

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * ⚠️⚠️ SIRF TESTING KE LIYE — test khatam hote hi ye file delete ho jayegi ⚠️⚠️
 *
 * Kaam: jab koi message aaye (aur app background mein ho), to **30 second baad**
 * usi ko **4 sample messages** wapas bhejta hai. Is se aap akele hi
 * background-notification ka poora chakkar test kar sakte ho:
 *
 *   Phone A se msg bhejo -> Phone B ko background mein mila? -> 30s baad
 *   Phone B se 4 sample msg -> Phone A par 4 notification? ✅
 *
 * Loop na chale is liye "Test msg" se shuru hone wale message ka jawab nahi bhejta.
 */
object WpTest {

    /** TEST ke baad isko false kar dena (ya file hi delete). */
    const val ENABLED = true

    private const val MARK = "Test msg"
    private const val DELAY_MS = 30_000L          // 30 second
    private const val GAP_MS = 3_000L             // 4 msg ke darmiyan 3 second
    private val done = HashSet<String>()

    fun onIncoming(ctx: Context, from: String, text: String, chatId: String) {
        if (!ENABLED) return
        if (text.startsWith(MARK)) return          // apna hi test msg -> jawab nahi
        if (WpActive.peer == from) return          // yahi chat khuli hai
        val key = "$chatId|${text.hashCode()}"
        synchronized(done) { if (!done.add(key)) return }

        val me = WpUser.me(ctx)
        Handler(Looper.getMainLooper()).postDelayed({
            for (i in 1..4) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        FirebaseChat.send(
                            ctx, chatId,
                            ChatMsg(from = me, text = "$MARK $i",
                                ts = System.currentTimeMillis(), type = "text")
                        )
                    } catch (t: Throwable) { }
                }, (i - 1) * GAP_MS)
            }
        }, DELAY_MS)
    }
}
