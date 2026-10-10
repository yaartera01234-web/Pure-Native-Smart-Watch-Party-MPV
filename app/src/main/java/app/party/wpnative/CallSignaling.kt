package app.party.wpnative

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Decrypted transient call command. Media never passes through Firestore. */
data class CallWireSignal(
    val docId: String,
    val callId: String,
    val chatId: String,
    val fromCode: String,
    val toCode: String,
    val action: String,
    val body: JSONObject,
    val ts: Long,
    val nonce: String
)

/**
 * Uses a reserved chat document under the already-deployed msgs rule for tiny, short-lived
 * encrypted SDP/control packets. This avoids a new Firebase collection/rules deployment and
 * keeps signaling completely outside the user's visible chat/history. Receiver deletes each
 * packet only after the active call consumer accepts it.
 */
object CallSignaling {
    private const val TYPE = "call_signal"
    private const val SALT = "YaarParty786-native-call-v1"
    private const val MAX_AGE = 75_000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wp-call-signal").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val keyCache = HashMap<String, SecretKey>()

    fun send(
        ctx: Context,
        chatId: String,
        peerCode: String,
        callId: String,
        action: String,
        extra: JSONObject = JSONObject(),
        done: ((Boolean) -> Unit)? = null
    ) {
        val app = ctx.applicationContext
        val mine = WpUser.friendCodeRaw(app)
        val peer = WpUser.normalizeFriendCode(peerCode)
        if (chatId.isBlank() || peer.length != 8 || callId.isBlank() || !FirebaseChat.isReady(app)) {
            done?.invoke(false); return
        }
        io.execute {
            try {
                val now = System.currentTimeMillis()
                val nonce = "n" + java.util.UUID.randomUUID().toString().replace("-", "").take(18)
                val body = JSONObject().apply {
                    put("k", "voice-call-v1"); put("a", action); put("id", callId)
                    put("from", mine); put("to", peer); put("ts", now); put("n", nonce)
                    extra.keys().forEach { key -> put(key, extra.opt(key)) }
                }
                val locked = encrypt(mine, peer, body.toString().toByteArray(Charsets.UTF_8))
                val doc = FirebaseFirestore.getInstance().collection("chats").document(signalChat(chatId))
                    .collection("msgs").document()
                val map = linkedMapOf<String, Any>(
                    "id" to doc.id,
                    "from" to WpUser.me(app),
                    "text" to "",
                    "ts" to now,
                    "read" to true,
                    "replyName" to "",
                    "replyText" to "",
                    "type" to TYPE,
                    "media" to locked,
                    "dur" to 0,
                    "wave" to "",
                    "deleted" to false,
                    "callFrom" to mine,
                    "callTo" to peer,
                    "callId" to callId,
                    "expiresAt" to (now + MAX_AGE)
                )
                doc.set(map).addOnSuccessListener { done?.let { main.post { it(true) } } }
                    .addOnFailureListener { done?.let { cb -> main.post { cb(false) } } }
            } catch (_: Throwable) {
                done?.let { main.post { it(false) } }
            }
        }
    }

    /** Listen only inside one stable Friend-Code chat. */
    fun listen(ctx: Context, chatId: String, peerCode: String,
        callback: (CallWireSignal) -> Boolean): ListenerRegistration? {
        val app = ctx.applicationContext
        val mine = WpUser.friendCodeRaw(app)
        val peer = WpUser.normalizeFriendCode(peerCode)
        if (chatId.isBlank() || peer.length != 8 || !FirebaseChat.isReady(app)) return null
        val inFlight = HashSet<String>()
        return try {
            FirebaseFirestore.getInstance().collection("chats").document(signalChat(chatId)).collection("msgs")
                .orderBy("ts", Query.Direction.DESCENDING).limit(50)
                .addSnapshotListener { snap, _ ->
                    val docs = snap?.documents.orEmpty().filter {
                        it.getString("type") == TYPE && it.getString("callTo") == mine &&
                            it.getString("callFrom") == peer
                    }.sortedBy { it.getLong("ts") ?: 0L }
                    docs.forEach { doc ->
                        synchronized(inFlight) { if (!inFlight.add(doc.id)) return@forEach }
                        val ts = doc.getLong("ts") ?: 0L
                        val expires = doc.getLong("expiresAt") ?: (ts + MAX_AGE)
                        if (System.currentTimeMillis() > expires || ts <= 0L) {
                            doc.reference.delete(); synchronized(inFlight) { inFlight.remove(doc.id) }
                            return@forEach
                        }
                        val locked = doc.getString("media").orEmpty()
                        io.execute {
                            try {
                                val body = JSONObject(String(decrypt(mine, peer, locked), Charsets.UTF_8))
                                val action = body.optString("a")
                                val callId = body.optString("id")
                                val nonce = body.optString("n")
                                val valid = body.optString("k") == "voice-call-v1" &&
                                    WpUser.normalizeFriendCode(body.optString("from")) == peer &&
                                    WpUser.normalizeFriendCode(body.optString("to")) == mine &&
                                    action in setOf("invite", "accept", "offer", "answer", "candidate",
                                        "decline", "busy", "unavailable", "cancel", "hangup") &&
                                    callId.isNotBlank() && nonce.isNotBlank()
                                if (valid) main.post {
                                    val consumed = runCatching {
                                        callback(CallWireSignal(doc.id, callId, chatId, peer, mine,
                                            action, body, ts, nonce))
                                    }.getOrDefault(false)
                                    if (consumed) doc.reference.delete()
                                }
                            } catch (_: Throwable) {
                                // Keep an undecryptable packet until expiry; it may belong to a
                                // temporarily mismatched/legacy identity and must never crash chat.
                            } finally {
                                synchronized(inFlight) { inFlight.remove(doc.id) }
                            }
                        }
                    }
                }
        } catch (_: Throwable) { null }
    }

    private fun signalChat(chatId: String) = "_wp_call_${chatId.take(80)}"

    private fun pairKey(aRaw: String, bRaw: String): SecretKey {
        val pair = listOf(aRaw.uppercase(Locale.ROOT), bRaw.uppercase(Locale.ROOT)).sorted().joinToString("|")
        synchronized(keyCache) { keyCache[pair]?.let { return it } }
        val spec = PBEKeySpec(("call|$pair").toCharArray(), SALT.toByteArray(Charsets.UTF_8), 150_000, 256)
        val made = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        val key = javax.crypto.spec.SecretKeySpec(made.encoded, "AES")
        synchronized(keyCache) { keyCache[pair] = key }
        return key
    }

    private fun encrypt(a: String, b: String, plain: ByteArray): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pairKey(a, b), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun decrypt(a: String, b: String, locked: String): ByteArray {
        val pieces = locked.split('.', limit = 2)
        require(pieces.size == 2)
        val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
        val data = Base64.decode(pieces[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, pairKey(a, b), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }
}
