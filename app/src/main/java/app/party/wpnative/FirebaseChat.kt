package app.party.wpnative

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/**
 * Firebase (Firestore) ka poora raasta — messages, presence, typing.
 *
 * ⚠️ Design: har function pehle `isReady()` check karta hai.
 *    `google-services.json` abhi maujood nahi hai, is liye app bina Firebase ke
 *    bhi chalega (purana demo mode) — koi crash nahi. JSON aate hi sab live ho jayega.
 *
 * Structure (Firestore):
 *   chats/{chatId}/msgs/{msgId}   -> ChatMsg fields
 *   users/{naam}                  -> { online, seenAt }
 *   typing/{chatId}               -> { who, ts }
 */
object FirebaseChat {

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

    /** Bhejo. Local id turant mil jati hai (cache ke liye). */
    fun send(ctx: Context, chatId: String, m: ChatMsg): Boolean {
        if (!isReady(ctx)) return false
        return try {
            val doc = msgs(ctx, chatId).document()
            m.id = doc.id
            doc.set(m.toMap())
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
    fun listenNew(
        ctx: Context,
        chatId: String,
        sinceTs: Long,
        onAdded: (List<ChatMsg>) -> Unit
    ): ListenerRegistration? {
        if (!isReady(ctx)) return null
        return try {
            msgs(ctx, chatId)
                .whereGreaterThan("ts", sinceTs)
                .orderBy("ts", Query.Direction.ASCENDING)
                .addSnapshotListener { snap, _ ->
                    if (snap == null) return@addSnapshotListener
                    val out = snap.documents.mapNotNull { d -> d.data?.let { ChatMsg.fromMap(d.id, it) } }
                    if (out.isNotEmpty()) onAdded(out)
                }
        } catch (t: Throwable) {
            null
        }
    }

    /** Ek message mitao (delete). */
    fun delete(ctx: Context, chatId: String, id: String) {
        if (!isReady(ctx) || id.isBlank()) return
        try { msgs(ctx, chatId).document(id).delete() } catch (t: Throwable) { }
    }

    /** Chat clear: jo messages is device ke paas hain wo sab mita do (batch). */
    fun deleteAll(ctx: Context, chatId: String, ids: List<String>) {
        if (!isReady(ctx)) return
        try {
            val batch = db(ctx).batch()
            ids.filter { it.isNotBlank() }.forEach { batch.delete(msgs(ctx, chatId).document(it)) }
            batch.commit()
        } catch (t: Throwable) { }
    }

    // ------------------------------------------------------------ presence

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
}
