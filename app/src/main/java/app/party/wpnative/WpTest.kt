package app.party.wpnative

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * ⚠️⚠️ SIRF TESTING KE LIYE — test khatam hote hi ye file delete ho jayegi ⚠️⚠️
 *
 * Doosra phone chahiye hi nahi: **"Dost 1" khud-ba-khud message bhejta hai**.
 * Ye messages asli Firestore mein likhe jate hain (jaise doosre phone se aaye hon),
 * is liye poora raasta test hota hai:
 *
 *      Firestore -> BgMsgService ka listener -> notification -> (Reply) -> wapas Firestore
 *
 * Kaise chalu hota hai: Inbox khulte hi 30 second ka timer lagta hai.
 * App background kar do -> 30 second baad "Dost 1" se 4 messages (3 second ke farq se).
 */
object WpTest {

    /** TEST ke baad isko false kar dena (ya file hi delete). */
    const val ENABLED = true

    private const val DELAY_MS = 30_000L       // 30 second baad shuru
    private const val GAP_MS = 3_000L          // 4 msg ke darmiyan 3 second
    private const val COUNT = 4

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Runnable>()

    /** Inbox aate hi timer lagao (dobara aane par purana timer cancel). */
    fun armFakeIncoming(ctx: Context) {
        if (!ENABLED) return
        cancel()
        val me = WpUser.me(ctx)
        val peer = Friends.all(ctx).firstOrNull { it != me } ?: return
        val chatId = WpUser.chatId(me, peer)

        val start = Runnable {
            for (i in 1..COUNT) {
                val one = Runnable {
                    try {
                        /* from = Dost 1  ->  jaise doosre phone ne bheja ho */
                        FirebaseChat.send(
                            ctx, chatId,
                            ChatMsg(from = peer, text = "Test msg $i",
                                ts = System.currentTimeMillis(), type = "text")
                        )
                    } catch (t: Throwable) { }
                }
                pending.add(one)
                handler.postDelayed(one, (i - 1) * GAP_MS)
            }
        }
        pending.add(start)
        handler.postDelayed(start, DELAY_MS)
    }

    fun cancel() {
        for (r in pending) handler.removeCallbacks(r)
        pending.clear()
    }
}
