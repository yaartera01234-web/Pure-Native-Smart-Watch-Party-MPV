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
    val color: String = "#8b72ff",
    val avatarType: String = "",
    val avatarData: String = ""
)

/** First-contact request shown above the normal Inbox chat rows. */
data class FriendRequest(
    val code: String,
    val name: String,
    val color: String = "#8b72ff",
    val preview: String = "👋 Friend request",
    val ts: Long = 0L,
    val avatarType: String = "",
    val avatarData: String = ""
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
 *   chats/_wp_friend_codes_v1/msgs/{rawCode}       -> public profile lookup
 *   chats/_wp_friend_requests_{target}/msgs/{from} -> pending first contact
 *
 * Friend metadata valid ChatMsg envelope mein rakha hai taa-ke already-deployed DM rules
 * ke saath bhi APK turant chale; kisi nayi Firebase collection permission ka intezar nahi.
 */
object FirebaseChat {

    /**
     * Har chat mein **itne messages** rahenge — phone par bhi, Firestore par bhi.
     * 121waana purana message **hamesha ke liye** mit jata hai (phone se bhi,
     * Firestore se bhi). Boss ka hukm: 120.
     */
    const val MSG_KEEP = 120
    private const val FRIEND_CODE_CHAT = "_wp_friend_codes_v1"
    private fun friendRequestChat(targetCode: String) = "_wp_friend_requests_$targetCode"

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

    /** WebRTC signaling shares the already-deployed msgs path but never becomes a bubble. */
    private fun visibleMessage(data: Map<String, Any?>): Boolean =
        data["type"] as? String != "call_signal"

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
                val out = snap.documents.mapNotNull { d ->
                    d.data?.takeIf(::visibleMessage)?.let { ChatMsg.fromMap(d.id, it) }
                }
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
                        d.data?.takeIf(::visibleMessage)?.let { ChatMsg.fromMap(d.id, it) }
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
                val out = snap.documents.mapNotNull { d ->
                    d.data?.takeIf(::visibleMessage)?.let { ChatMsg.fromMap(d.id, it) }
                }
                onDone(out.asReversed())
            }
            .addOnFailureListener { onDone(emptyList()) }
    }

    /** Live: sirf wahi messages jo `sinceTs` ke baad aaye (purane dobara nahi aate). */
    /**
     * Chat ki **live nazar**: retained aakhri 120 messages.
     * Naya/delete/read/reaction sab isi se dono phones par turant aata hai.
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
                .limit(MSG_KEEP.toLong())
                .addSnapshotListener { snap, _ ->
                    if (snap == null) return@addSnapshotListener
                    val out = snap.documents.mapNotNull { d ->
                        d.data?.takeIf(::visibleMessage)?.let { ChatMsg.fromMap(d.id, it) }
                    }
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
                    val data = d.data?.takeIf(::visibleMessage) ?: return@addSnapshotListener
                    onMsg(ChatMsg.fromMap(d.id, data))
                }
        } catch (t: Throwable) { null }
    }

    /** Inbox row ke liye latest preview + exact unread count (retained native limit 120). */
    fun listenInboxSummary(
        ctx: Context,
        chatId: String,
        onMessages: (List<ChatMsg>) -> Unit
    ): ListenerRegistration? {
        if (!isReady(ctx)) return null
        return try {
            msgs(ctx, chatId)
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(MSG_KEEP.toLong())
                .addSnapshotListener { snap, _ ->
                    if (snap == null) return@addSnapshotListener
                    onMessages(snap.documents.mapNotNull { d ->
                        d.data?.takeIf(::visibleMessage)?.let { ChatMsg.fromMap(d.id, it) }
                    })
                }
        } catch (_: Throwable) { null }
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

    /**
     * Recipient ne khuli hui conversation mein jo messages dekh liye un par read=true.
     * Sender ka live listener isi server transition par ticks ko blue karta hai — online
     * presence ya local timer kabhi seen receipt nahi banate.
     */
    fun markRead(ctx: Context, chatId: String, ids: Collection<String>) {
        val clean = ids.asSequence().filter { it.isNotBlank() }.distinct().take(400).toList()
        if (!isReady(ctx) || chatId.isBlank() || clean.isEmpty()) return
        try {
            val batch = db(ctx).batch()
            clean.forEach { id ->
                batch.set(msgs(ctx, chatId).document(id), mapOf("read" to true), SetOptions.merge())
            }
            batch.commit()
        } catch (_: Throwable) { }
    }

    /**
     * Ek actor ki reaction original message document par transaction se merge hoti hai.
     * Is se simultaneous dono-phone reactions ek doosre ko overwrite nahi kartin aur koi
     * fake reaction-message/unread badge bhi nahi banta. Khali emoji = apni reaction hatao.
     */
    fun setReaction(
        ctx: Context,
        chatId: String,
        messageId: String,
        actor: String,
        emoji: String,
        onDone: (Boolean) -> Unit = {}
    ) {
        if (!isReady(ctx) || chatId.isBlank() || messageId.isBlank() || actor.isBlank()) {
            onDone(false); return
        }
        try {
            val ref = msgs(ctx, chatId).document(messageId)
            db(ctx).runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) throw IllegalStateException("message missing")
                val reactions = linkedMapOf<String, String>()
                (snap.get("reactions") as? Map<*, *>)?.forEach { (key, value) ->
                    if (key is String && value is String && key.isNotBlank() && value.isNotBlank()) {
                        reactions[key] = value
                    }
                }
                if (emoji.isBlank()) reactions.remove(actor) else reactions[actor] = emoji
                tx.update(ref, "reactions", reactions)
            }.addOnSuccessListener { onDone(true) }
                .addOnFailureListener { onDone(false) }
        } catch (_: Throwable) { onDone(false) }
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
            val now = System.currentTimeMillis()
            val avatar = DpStore.shareableAvatar(ctx)
            db(ctx).collection("chats").document(FRIEND_CODE_CHAT)
                .collection("msgs").document(code).set(
                    mapOf(
                        // Existing isValidMsg envelope:
                        "from" to name, "text" to "#8b72ff", "ts" to now,
                        // Friend directory fields, including the exact selected DP:
                        "code" to code, "name" to name, "color" to "#8b72ff",
                        "avatarType" to avatar.type, "avatarData" to avatar.data,
                        "updatedAt" to now
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
            db(ctx).collection("chats").document(FRIEND_CODE_CHAT)
                .collection("msgs").document(raw).get()
                .addOnSuccessListener { snap ->
                    val name = (snap.getString("name") ?: snap.getString("from"))
                        ?.trim().orEmpty().take(40)
                    if (!snap.exists() || name.isBlank()) cb(null)
                    else {
                        val avatarType = snap.getString("avatarType").orEmpty()
                        val avatarData = snap.getString("avatarData").orEmpty()
                        DpStore.rememberRemote(ctx, name, avatarType, avatarData)
                        cb(FriendProfile(raw, name, snap.getString("color") ?: "#8b72ff",
                            avatarType, avatarData))
                    }
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
            val name = WpUser.me(ctx).take(40)
            val shortPreview = preview.take(160)
            val avatar = DpStore.shareableAvatar(ctx)
            db(ctx).collection("chats").document(friendRequestChat(target))
                .collection("msgs").document(mine)
                .set(
                    mapOf(
                        // Existing isValidMsg envelope:
                        "from" to name, "text" to shortPreview, "ts" to System.currentTimeMillis(),
                        // Request UI fields + sender ki selected original DP:
                        "code" to mine, "name" to name, "color" to "#8b72ff",
                        "avatarType" to avatar.type, "avatarData" to avatar.data,
                        "preview" to shortPreview
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
            db(ctx).collection("chats").document(friendRequestChat(mine)).collection("msgs")
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null) { onRequests(emptyList()); return@addSnapshotListener }
                    val list = snap.documents.mapNotNull { d ->
                        val code = WpUser.normalizeFriendCode(d.getString("code") ?: d.id)
                        val name = (d.getString("name") ?: d.getString("from"))
                            ?.trim().orEmpty().take(40)
                        if (code.length != 8 || name.isBlank() || code == mine) null
                        else {
                            val avatarType = d.getString("avatarType").orEmpty()
                            val avatarData = d.getString("avatarData").orEmpty()
                            DpStore.rememberRemote(ctx, name, avatarType, avatarData)
                            FriendRequest(
                                code = code,
                                name = name,
                                color = d.getString("color") ?: "#8b72ff",
                                preview = d.getString("preview") ?: d.getString("text") ?: "👋 Friend request",
                                ts = d.getLong("ts") ?: 0L,
                                avatarType = avatarType,
                                avatarData = avatarData
                            )
                        }
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
            db(ctx).collection("chats").document(friendRequestChat(mine))
                .collection("msgs").document(sender).delete()
                .addOnSuccessListener { cb?.invoke(true) }
                .addOnFailureListener { cb?.invoke(false) }
        } catch (_: Throwable) { cb?.invoke(false) }
    }
}
