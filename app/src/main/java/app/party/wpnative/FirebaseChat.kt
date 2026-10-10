package app.party.wpnative

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/** Public Friend Code profile returned by the code directory. */
data class FriendProfile(
    val code: String,
    val name: String,
    val color: String = "#8b72ff"
)

/** First-contact request shown above the normal Inbox chat rows. */
data class FriendRequest(
    val code: String,
    val name: String,
    val color: String = "#8b72ff",
    val preview: String = "👋 Friend request",
    val ts: Long = 0L
)

/**
 * Firebase (Firestore) ka poora raasta — messages, presence, typing.
 *
 * ⚠️ Design: har function pehle `isReady()` check karta hai.
 *    `google-services.json` abhi maujood nahi hai, is liye app bina Firebase ke
 *    bhi chalega (purana demo mode) — koi crash nahi. JSON aate hi sab live ho jayega.
 *
 * Structure (Firestore):
 *   chats/{chatId}/msgs/{msgId}                    -> ChatMsg fields
 *   users/{naam}                                   -> presence/token
 *   typing/{chatId}                                -> { who, ts }
 *   friendCodes/{rawCode}                          -> public profile lookup
 *   friendRequests/{targetCode}/items/{senderCode} -> pending first contact
 */
object FirebaseChat {

    /**
     * Har chat mein **itne messages** rahenge — phone par bhi, Firestore par bhi.
     * 121waana purana message **hamesha ke liye** mit jata hai (phone se bhi,
     * Firestore se bhi). Boss ka hukm: 120.
     */
    const val MSG_KEEP = 120

    /** Ek baar check kar ke yaad rakh lete hain (baar baar try/catch na chale). */
    private var readyState: Boolean? = null

    fun isReady(ctx: Context): Boolean {
        readyState?.let { return it }
        val ok = try {
            if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx)
            FirebaseFirestore.getInstance()
            true
        } catch (t: Throwable) {
            false
        }
        readyState = ok
        return ok
    }

    private fun db(ctx: Context): FirebaseFirestore = FirebaseFirestore.getInstance()

    private fun msgs(ctx: Context, chatId: String) =
        db(ctx).collection("chats").document(chatId).collection("msgs")

    // ------------------------------------------------------------ messages

    /** Bhejo. Local id turant; server ne pakka save kiya to `onSaved`. */
    fun send(ctx: Context, chatId: String, m: ChatMsg, onSaved: (() -> Unit)? = null): Boolean {
        if (!isReady(ctx)) return false
        return try {
            val doc = msgs(ctx, chatId).document()
            m.id = doc.id
            doc.set(m.toMap()).addOnSuccessListener { onSaved?.invoke() }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Chat khulte hi: aakhri `limit` (default 20) messages — naye se purane. */
    fun loadLast(ctx: Context, chatId: String, limit: Long = 20L, onDone: (List<ChatMsg>) -> Unit) {
        if (!isReady(ctx)) { onDone(emptyList()); return }
        msgs(ctx, chatId)
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snap ->
                val out = snap.documents.mapNotNull { d -> d.data?.let { ChatMsg.fromMap(d.id, it) } }
                onDone(out.asReversed())          // purane upar, naye neeche
            }
            .addOnFailureListener { onDone(emptyList()) }
    }

    /**
     * Message se **photo/voice ka asli maal (base64) hata do** — 3 din baad.
     * Baqi sab (text, waqt, second) wahin rahta hai, bas media jati hai.
     * (Firestore TTL Blaze par hi milti hai — ye hamara apna, muft tareeqa hai.)
     */
    fun dropMedia(ctx: Context, chatId: String, msgId: String, onDone: (Boolean) -> Unit) {
        if (!isReady(ctx) || chatId.isBlank() || msgId.isBlank()) { onDone(false); return }
        try {
            msgs(ctx, chatId).document(msgId)
                .update("media", FieldValue.delete())
                .addOnSuccessListener { onDone(true) }
                .addOnFailureListener { onDone(false) }
        } catch (t: Throwable) { onDone(false) }
    }

    /**
     * **120 se upar purane messages hamesha ke liye mitao** — Firestore se bhi.
     *
     * `cutTs = 0`: chat khulte hi naye 120 laa kar un se purane sab mita deta hai.
     * Wahi 120 `onDone` ko bhi milte hain, is liye alag 20-read query nahi chalti.
     * `cutTs > 0`: naya message bhejte waqt sirf is waqt se purane mita deta hai.
     *
     * `onDone(null)` ka matlab internet/query fail — phone ka cache tab nahi chhedenge.
     */
    fun prune(
        ctx: Context,
        chatId: String,
        cutTs: Long = 0L,
        onDone: ((List<ChatMsg>?) -> Unit)? = null
    ) {
        if (!isReady(ctx)) { onDone?.invoke(null); return }
        if (cutTs > 0L) {
            deleteOlder(ctx, chatId, cutTs, 50) { onDone?.invoke(emptyList()) }
            return
        }
        try {
            msgs(ctx, chatId)
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(MSG_KEEP.toLong())
                .get()
                .addOnSuccessListener { snap ->
                    val latest = snap.documents.mapNotNull { d ->
                        d.data?.let { ChatMsg.fromMap(d.id, it) }
                    }
                    if (snap.size() < MSG_KEEP) {
                        onDone?.invoke(latest.asReversed())
                        return@addOnSuccessListener
                    }
                    // `cut` khud 120waana message hai — sirf us se PURANE mitao,
                    // warna galti se 120waana bhi mit kar 119 reh jayenge.
                    val cut = snap.documents.lastOrNull()?.getLong("ts") ?: 0L
                    if (cut <= 0L) { onDone?.invoke(latest.asReversed()); return@addOnSuccessListener }
                    deleteOlder(ctx, chatId, cut, 400) {
                        onDone?.invoke(latest.asReversed())
                    }
                }
                .addOnFailureListener { onDone?.invoke(null) }
        } catch (t: Throwable) { onDone?.invoke(null) }
    }

    /** `cut` se sakht purane messages batches mein mitao (120waana khud mehfooz). */
    private fun deleteOlder(
        ctx: Context,
        chatId: String,
        cut: Long,
        limit: Long,
        onDone: () -> Unit
    ) {
        try {
            msgs(ctx, chatId)
                .whereLessThan("ts", cut)
                .limit(limit)
                .get()
                .addOnSuccessListener { snap ->
                    val docs = snap.documents
                    if (docs.isEmpty()) { onDone(); return@addOnSuccessListener }
                    val batch = db(ctx).batch()
                    docs.forEach { batch.delete(it.reference) }
                    batch.commit()
                        .addOnSuccessListener {
                            // 400 se zyada purane thay to agla batch; fail ho to agli
                            // dafa chat khulne par phir koshish ho jayegi.
                            if (docs.size >= limit) deleteOlder(ctx, chatId, cut, limit, onDone)
                            else onDone()
                        }
                        .addOnFailureListener { onDone() }
                }
                .addOnFailureListener { onDone() }
        } catch (t: Throwable) { onDone() }
    }

    /** Upar scroll: is se bhi purane `limit` messages. */
    fun loadBefore(ctx: Context, chatId: String, oldestTs: Long, limit: Long = 20L, onDone: (List<ChatMsg>) -> Unit) {
        if (!isReady(ctx)) { onDone(emptyList()); return }
        msgs(ctx, chatId)
            .whereLessThan("ts", oldestTs)
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snap ->
                val out = snap.documents.mapNotNull { d -> d.data?.let { ChatMsg.fromMap(d.id, it) } }
                onDone(out.asReversed())
            }
            .addOnFailureListener { onDone(emptyList()) }
    }

    /** Live: sirf wahi messages jo `sinceTs` ke baad aaye (purane dobara nahi aate). */
    /**
     * Chat ki **live nazar**: aakhri 50 messages.
     * Naya message bhi isi se aata hai, aur koi message mita (deleted nishan) to wo bhi.
     */
    fun listenNew(
        ctx: Context,
        chatId: String,
        onDocs: (List<ChatMsg>) -> Unit
    ): ListenerRegistration? {
        if (!isReady(ctx)) return null
        return try {
            msgs(ctx, chatId)
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snap, _ ->
                    if (snap == null) return@addSnapshotListener
                    val out = snap.documents.mapNotNull { d -> d.data?.let { ChatMsg.fromMap(d.id, it) } }
                    onDocs(out)
                }
        } catch (t: Throwable) {
            null
        }
    }

    fun listenLast(ctx: Context, chatId: String, onMsg: (ChatMsg) -> Unit): ListenerRegistration? {
        if (!isReady(ctx)) return null
        return try {
            msgs(ctx, chatId)
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(1)
                .addSnapshotListener { snap, _ ->
                    val d = snap?.documents?.firstOrNull() ?: return@addSnapshotListener
                    val m = d.data?.let { ChatMsg.fromMap(d.id, it) } ?: return@addSnapshotListener
                    onMsg(m)
                }
        } catch (t: Throwable) { null }
    }

    /**
     * Message mitao — **sabke phone se** (sirf apni screen se nahi).
     *
     * Seedha document delete karne se doosre phone ko pata hi nahi chalta
     * (wo kabhi na kabhi cache se wapas la deta hai). Is liye:
     *   text khaali + deleted = true  ->  ye "nishan" har device turant dekh leta hai.
     */
    fun delete(ctx: Context, chatId: String, id: String) {
        if (!isReady(ctx) || id.isBlank()) return
        try {
            msgs(ctx, chatId).document(id)
                .set(mapOf("deleted" to true, "text" to ""), SetOptions.merge())
        } catch (t: Throwable) { }
    }

    /** Chat clear: saare messages par wahi "mita hua" nishan (batch). */
    fun deleteAll(ctx: Context, chatId: String, ids: List<String>) {
        if (!isReady(ctx)) return
        try {
            val batch = db(ctx).batch()
            ids.filter { it.isNotBlank() }.forEach {
                batch.set(msgs(ctx, chatId).document(it),
                    mapOf("deleted" to true, "text" to ""), SetOptions.merge())
            }
            batch.commit()
        } catch (t: Throwable) { }
    }

    fun setPresence(ctx: Context, name: String, online: Boolean) {
        if (!isReady(ctx)) return
        try {
            db(ctx).collection("users").document(name)
                .set(mapOf("online" to online, "seenAt" to System.currentTimeMillis()), SetOptions.merge())
        } catch (t: Throwable) { }
    }

    /** Apna FCM token save karo (doosra phone isi par push bhejega). */
    fun saveToken(ctx: Context, name: String, token: String) {
        if (!isReady(ctx)) return
        try {
            db(ctx).collection("users").document(name)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
        } catch (t: Throwable) { }
    }

    /** Doosre ka FCM token + online haalat (push bhejne se pehle). */
    fun getToken(ctx: Context, name: String, cb: (String?, Boolean) -> Unit) {
        if (!isReady(ctx)) { cb(null, false); return }
        try {
            db(ctx).collection("users").document(name).get()
                .addOnSuccessListener { snap ->
                    cb(snap.getString("fcmToken"), snap.getBoolean("online") ?: false)
                }
                .addOnFailureListener { cb(null, false) }
        } catch (t: Throwable) { cb(null, false) }
    }

    /**
     * Background service ke liye: is chat ka **sabse naya** message, live.
     * (naye message par turant callback — service ko notification dikhane ke liye)
     */
    fun listenPresence(ctx: Context, name: String, onState: (Boolean, Long) -> Unit): ListenerRegistration? {
        if (!isReady(ctx)) return null
        return try {
            db(ctx).collection("users").document(name)
                .addSnapshotListener { snap, _ ->
                    if (snap == null || !snap.exists()) return@addSnapshotListener
                    onState(snap.getBoolean("online") ?: false, snap.getLong("seenAt") ?: 0L)
                }
        } catch (t: Throwable) { null }
    }

    // ------------------------------------------------------------ typing

    /** Apni taraf se "main likh raha hoon" (blank who = chhup jao). */
    fun setTyping(ctx: Context, chatId: String, who: String, typing: Boolean) {
        if (!isReady(ctx)) return
        try {
            db(ctx).collection("typing").document(chatId)
                .set(mapOf("who" to if (typing) who else "", "ts" to System.currentTimeMillis()), SetOptions.merge())
        } catch (t: Throwable) { }
    }

    /** Doosra wala likh raha hai? (4 second purana signal = chhup jao) */
    fun listenTyping(ctx: Context, chatId: String, me: String, onTyping: (Boolean) -> Unit): ListenerRegistration? {
        if (!isReady(ctx)) return null
        return try {
            db(ctx).collection("typing").document(chatId)
                .addSnapshotListener { snap, _ ->
                    if (snap == null || !snap.exists()) { onTyping(false); return@addSnapshotListener }
                    val who = snap.getString("who") ?: ""
                    val ts = snap.getLong("ts") ?: 0L
                    val fresh = System.currentTimeMillis() - ts < 4000L
                    onTyping(who.isNotBlank() && who != me && fresh)
                }
        } catch (t: Throwable) { null }
    }

    // ----------------------------------------------------- Friend Codes / Requests

    /** Apna stable WP1 code directory mein publish/update karo. */
    fun publishFriendProfile(ctx: Context) {
        if (!isReady(ctx)) return
        val code = WpUser.friendCodeRaw(ctx)
        val name = WpUser.me(ctx).trim().take(40)
        if (code.length != 8 || name.isBlank()) return
        try {
            db(ctx).collection("friendCodes").document(code).set(
                mapOf(
                    "code" to code,
                    "name" to name,
                    "color" to "#8b72ff",
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
        } catch (_: Throwable) { }
    }

    /** Code se asli public display name/profile dhoondo. */
    fun findFriendProfile(ctx: Context, code: String, cb: (FriendProfile?) -> Unit) {
        val raw = WpUser.normalizeFriendCode(code)
        if (!isReady(ctx) || raw.length != 8) { cb(null); return }
        try {
            db(ctx).collection("friendCodes").document(raw).get()
                .addOnSuccessListener { snap ->
                    val name = snap.getString("name")?.trim().orEmpty().take(40)
                    if (!snap.exists() || name.isBlank()) cb(null)
                    else cb(FriendProfile(raw, name, snap.getString("color") ?: "#8b72ff"))
                }
                .addOnFailureListener { cb(null) }
        } catch (_: Throwable) { cb(null) }
    }

    /** Target code ke Inbox mein first-contact request rakho/refresh karo. */
    fun sendFriendRequest(
        ctx: Context,
        targetCode: String,
        preview: String,
        cb: (Boolean) -> Unit
    ) {
        val target = WpUser.normalizeFriendCode(targetCode)
        val mine = WpUser.friendCodeRaw(ctx)
        if (!isReady(ctx) || target.length != 8 || mine.length != 8 || target == mine) {
            cb(false); return
        }
        try {
            db(ctx).collection("friendRequests").document(target)
                .collection("items").document(mine)
                .set(
                    mapOf(
                        "code" to mine,
                        "name" to WpUser.me(ctx).take(40),
                        "color" to "#8b72ff",
                        "preview" to preview.take(160),
                        "ts" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
                .addOnSuccessListener { cb(true) }
                .addOnFailureListener { cb(false) }
        } catch (_: Throwable) { cb(false) }
    }

    /** Apne code par aane wali requests live suno. */
    fun listenFriendRequests(
        ctx: Context,
        onRequests: (List<FriendRequest>) -> Unit
    ): ListenerRegistration? {
        if (!isReady(ctx)) { onRequests(emptyList()); return null }
        val mine = WpUser.friendCodeRaw(ctx)
        return try {
            db(ctx).collection("friendRequests").document(mine).collection("items")
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null) { onRequests(emptyList()); return@addSnapshotListener }
                    val list = snap.documents.mapNotNull { d ->
                        val code = WpUser.normalizeFriendCode(d.getString("code") ?: d.id)
                        val name = d.getString("name")?.trim().orEmpty().take(40)
                        if (code.length != 8 || name.isBlank() || code == mine) null
                        else FriendRequest(
                            code = code,
                            name = name,
                            color = d.getString("color") ?: "#8b72ff",
                            preview = d.getString("preview") ?: "👋 Friend request",
                            ts = d.getLong("ts") ?: 0L
                        )
                    }.sortedByDescending { it.ts }
                    onRequests(list)
                }
        } catch (_: Throwable) {
            onRequests(emptyList())
            null
        }
    }

    fun removeFriendRequest(ctx: Context, senderCode: String, cb: ((Boolean) -> Unit)? = null) {
        val mine = WpUser.friendCodeRaw(ctx)
        val sender = WpUser.normalizeFriendCode(senderCode)
        if (!isReady(ctx) || sender.length != 8) { cb?.invoke(false); return }
        try {
            db(ctx).collection("friendRequests").document(mine)
                .collection("items").document(sender).delete()
                .addOnSuccessListener { cb?.invoke(true) }
                .addOnFailureListener { cb?.invoke(false) }
        } catch (_: Throwable) { cb?.invoke(false) }
    }
}
