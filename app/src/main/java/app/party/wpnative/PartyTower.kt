package app.party.wpnative

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Public MQTT tower par chalne wale live Party Room ka member. */
data class PartyMember(
    val id: String,
    val name: String,
    val color: Int,
    val ts: Long
)

/** Tower ka encrypted Room message metadata. Media khud retained blob topic mein hoti hai. */
data class PartyMessage(
    val mid: String,
    val senderId: String,
    val name: String,
    val color: Int,
    val text: String,
    val replyName: String,
    val replyText: String,
    val replyMid: String,
    val time: String,
    val ts: Long,
    val type: String,
    val mediaRef: String,
    val mediaIv: String,
    val dur: Int,
    val wave: String
)

/** Website-compatible retained playback state and transient command payloads. */
data class PartyPlaybackMedia(
    val type: String,
    val url: String,
    val videoId: String
) {
    fun key(): String = "$type:${if (type == "youtube") videoId else url}"
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("url", url); put("videoId", videoId)
    }

    companion object {
        fun fromJson(o: JSONObject): PartyPlaybackMedia = PartyPlaybackMedia(
            o.optString("type", "mp4"), o.optString("url"), o.optString("videoId"))
    }
}

data class PartyPlaybackState(
    val media: PartyPlaybackMedia,
    val time: Double,
    val playing: Boolean,
    /** Website-compatible running clock base. Frozen empty Rooms deliberately publish at=0. */
    val at: Long,
    val title: String = "",
    val queueIndex: Int = -1,
    val queueItem: PartyQueueItem? = null,
    val clockRunning: Boolean = playing,
    val frozen: Boolean = false,
    val capturedAt: Long = at,
    val revisionCounter: Long = -1L,
    val revisionOwner: String = "",
    val epochCounter: Long = -1L,
    val epochOwner: String = "",
    val updatedBy: String = ""
)

data class PartyPlaybackCommand(
    val action: String,
    val from: String,
    val by: String,
    val time: Double?,
    val playing: Boolean?,
    val user: Boolean,
    val media: PartyPlaybackMedia?,
    val raw: JSONObject
)

/** Authoritative website queue item: thumbnail/title + optional custom rename. */
data class PartyQueueItem(
    val id: String,
    val type: String,
    val url: String,
    val videoId: String,
    var label: String,
    var title: String = "",
    var name: String = "",
    val by: String = ""
) {
    fun originalName(): String = title.ifBlank { label.ifBlank { videoId.ifBlank { url.ifBlank { "video" } } } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("url", url); put("videoId", videoId)
        put("label", label); put("title", title); put("name", name); put("by", by)
    }

    companion object {
        fun fromJson(o: JSONObject): PartyQueueItem = PartyQueueItem(
            id = o.optString("id").ifBlank { "q" + Integer.toUnsignedString(o.toString().hashCode(), 36) },
            type = o.optString("type", "mp4"),
            url = o.optString("url"),
            videoId = o.optString("videoId"),
            label = o.optString("label"),
            title = o.optString("title"),
            name = o.optString("name"),
            by = o.optString("by")
        )
    }
}

interface PartyTowerListener {
    fun onPartyTowerStatus(connected: Boolean, label: String)
    fun onPartyMembers(members: List<PartyMember>)
    fun onPartyMessage(message: PartyMessage)
    fun onPartyMessageRemoved(mid: String)
    fun onPartyMedia(mid: String, bytes: ByteArray)
    fun onPartyReactions(mid: String, values: Map<String, Pair<Int, Boolean>>)
    fun onPartyQueue(items: List<PartyQueueItem>, index: Int)
    fun onPartyTyping(names: List<String>)
    fun onPartyPlaybackState(state: PartyPlaybackState)
    fun onPartyPlaybackCommand(command: PartyPlaybackCommand)
}

/**
 * Native equivalent of party-final1.html ka selected Party/room tower.
 *
 * - EMQX / HiveMQ / tyckr ki exact WSS endpoints.
 * - Room name se PBKDF2 + AES-GCM E2E, website ke envelope (`e2e/iv/ct`) jaisa.
 * - Chat ke retained native records late/rejoining active members ke liye; explicit Leave
 *   par us phone ka cursor aage chala jata hai. Aakhri member Leave kare to retained chat
 *   aur media blobs saaf, lekin playlist aur exact frozen playback snapshot save rehte hain.
 * - Disconnect/glitch kabhi Leave publish nahi karta.
 */
object PartyTower {
    private const val PREF = "wp_native"
    private const val ROOM_BASE = "YaarParty786/x7k2/room-"
    private const val E2E_SALT = "YaarParty786-e2e-v1"
    private const val KEEP = 120

    private val towers = arrayOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://broker.hivemq.com:8884/mqtt",
        "wss://mqtt.tyckr.io:8081"
    )
    private val towerNames = arrayOf("EMQX", "HiveMQ", "tyckr")
    private val colors = intArrayOf(
        0xffff5c8a.toInt(), 0xffff9f45.toInt(), 0xffffd93d.toInt(), 0xff6bcb77.toInt(),
        0xff4d96ff.toInt(), 0xff9b5de5.toInt(), 0xfff15bb5.toInt(), 0xff00bbf9.toInt(),
        0xff00f5d4.toInt(), 0xffff6b6b.toInt(), 0xff4ecdc4.toInt()
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val secure = SecureRandom()
    private var app: Context? = null
    private var listener: PartyTowerListener? = null
    private var client: MqttAsyncClient? = null
    private var roomName = ""
    private var base = ""
    private var memberId = ""
    private var myName = ""
    private var myColor = colors[0]
    private var towerIndex = 0
    private var secret: SecretKey? = null
    private var connected = false
    private var joining = false
    private var explicitLeaving = false
    private var visibleAfter = 0L

    private val members = java.util.concurrent.ConcurrentHashMap<String, PartyMember>()
    private val typingUsers = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private var lastTypingSentAt = 0L
    private val messages = java.util.concurrent.ConcurrentHashMap<String, PartyMessage>()
    private val messageJson = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val encryptedBlobs = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val deliveredMedia = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val reactions = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
    private var queue = mutableListOf<PartyQueueItem>()
    private var queueIndex = -1
    @Volatile private var playbackState: PartyPlaybackState? = null
    @Volatile private var playbackCheckpoint: PartyPlaybackState? = null
    private var lastPlaybackPayload = ""
    private var stateRevisionCounter = 0L
    private val playbackLock = Any()
    private val seenPlaybackCommands = LinkedHashSet<String>()

    private val presenceBeat = object : Runnable {
        override fun run() {
            if (connected && !explicitLeaving) publishPresence()
            main.postDelayed(this, 15_000L)
        }
    }
    private val typingSweep = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val expired = typingUsers.entries.filter { it.value.second < now }.map { it.key }
            expired.forEach { typingUsers.remove(it) }
            if (expired.isNotEmpty()) emitTyping()
            if (typingUsers.isNotEmpty()) main.postDelayed(this, 1_000L)
        }
    }

    fun towerLabel(index: Int): String = towerNames[index.coerceIn(towerNames.indices)]
    fun isConnected(): Boolean = connected
    fun hasLiveSession(): Boolean = !explicitLeaving && (joining || connected || client != null)
    fun currentMemberId(): String = memberId

    /** Lobby ke Enter Party ke baad hi call hota hai. */
    @Synchronized
    fun enter(ctx: Context, room: String, name: String, selectedTower: Int, target: PartyTowerListener) {
        val clean = cleanRoom(room)
        val idx = selectedTower.coerceIn(towers.indices)
        listener = target
        app = ctx.applicationContext
        if (base == ROOM_BASE + clean && towerIndex == idx && client != null) {
            emitSnapshot()
            if (connected) publishPresence()
            return
        }

        disconnectClient()
        resetTransient()
        roomName = clean
        base = ROOM_BASE + clean
        towerIndex = idx
        myName = name.trim().take(20).ifBlank { "Friend" }
        myColor = colors[(myName.lowercase().hashCode() and Int.MAX_VALUE) % colors.size]
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val sk = sessionKey()
        memberId = prefs.getString(sk, "")?.takeIf { it.isNotBlank() }
            ?: ("u" + UUID.randomUUID().toString().replace("-", "").take(17)).also {
                prefs.edit().putString(sk, it).apply()
            }
        visibleAfter = prefs.getLong(leaveCursorKey(), 0L)
        prefs.edit().putBoolean("party_live", true).putString("party_live_room", clean)
            .putInt("party_live_tower", idx).apply()
        explicitLeaving = false
        joining = true
        postStatus(false, "📻 ${towerLabel(idx)} se jur raha hai…")

        io.execute {
            try {
                secret = deriveKey(clean)
                connect()
            } catch (_: Throwable) {
                joining = false
                postStatus(false, "❌ ${towerLabel(idx)} connect nahi hua")
            }
        }
    }

    fun attach(target: PartyTowerListener) {
        if (listener === target) return
        listener = target
        emitSnapshot()
    }

    fun detach(target: PartyTowerListener) {
        if (listener === target) listener = null
    }

    private fun connect() {
        val id = "wpn_" + memberId.takeLast(18)
        val c = MqttAsyncClient(towers[towerIndex], id, MemoryPersistence())
        client = c
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                connected = true
                joining = false
                subscribeAll()
                clearPendingExplicitLeave()
                publishPresence()
                publishEvent("join")
                main.removeCallbacks(presenceBeat)
                main.post(presenceBeat)
                postStatus(true, "🗼 ${towerLabel(towerIndex)} connected")
            }

            override fun connectionLost(cause: Throwable?) {
                connected = false
                if (!explicitLeaving) postStatus(false, "⚠️ Tower reconnect ho raha hai…")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                try { handle(topic, message.payload) } catch (_: Throwable) { }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 11
            keepAliveInterval = 30
            maxInflight = 300
        }
        c.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {}
            override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) {
                connected = false
                joining = false
                postStatus(false, "❌ ${towerLabel(towerIndex)} nahi mil raha")
            }
        })
    }

    private fun subscribeAll() {
        val c = client ?: return
        val topics = arrayOf(
            "$base/chat", "$base/chat/msg/+", "$base/chat/blob/+", "$base/events",
            "$base/react", "$base/members/+", "$base/typing/+", "$base/queue",
            "$base/cmd", "$base/state"
        )
        try { c.subscribe(topics, IntArray(topics.size) { 1 }) } catch (_: Throwable) { }
    }

    private fun publishPresence() {
        val avatar = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.let { p ->
            JSONObject().apply {
                put("type", p.getString("avatarType", "letter") ?: "letter")
                val data = p.getString("avatarData", null)
                if (!data.isNullOrBlank() && data.length < 200_000) {
                    if (data.startsWith("http")) put("url", data) else put("data", data)
                }
            }
        } ?: JSONObject().put("type", "letter")
        val body = JSONObject().apply {
            put("name", myName); put("color", colorString(myColor)); put("avatar", avatar)
            put("ts", System.currentTimeMillis())
        }
        publish("$base/members/$memberId", body.toString().toByteArray(), true)
    }

    private fun publishEvent(type: String) {
        val body = JSONObject().apply {
            put("t", type); put("name", myName); put("from", memberId); put("mid", randomId())
        }
        publish("$base/events", body.toString().toByteArray(), false)
    }

    /** Text/photo/voice bhejo. Return mid milte hi caller local media temp cache mein rakh sakta hai. */
    fun sendMessage(
        text: String,
        replyName: String = "",
        replyText: String = "",
        replyMid: String = "",
        type: String = "text",
        media: ByteArray? = null,
        dur: Int = 0,
        wave: String = ""
    ): String? {
        if (!connected || secret == null) return null
        val mid = randomId()
        val ts = System.currentTimeMillis()
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))

        // Optimistic local echo: sender ko broker round-trip ka intezar na karna pare.
        // MQTT se apna echo aayega to messages map ki same mid us duplicate ko rok degi.
        val local = PartyMessage(
            mid = mid,
            senderId = memberId,
            name = myName,
            color = myColor,
            text = text.take(500),
            replyName = replyName.take(20),
            replyText = replyText.take(120),
            replyMid = replyMid,
            time = time,
            ts = ts,
            type = type,
            mediaRef = if (media != null && media.isNotEmpty()) "mqtt:$mid" else "",
            mediaIv = "",
            dur = dur,
            wave = wave
        )
        messages[mid] = local
        trimRetainedIfNeeded()
        main.post { listener?.onPartyMessage(local) }

        io.execute {
            try {
                var mediaRef = ""
                var mediaIv = ""
                if (media != null && media.isNotEmpty()) {
                    val enc = encryptBytes(media)
                    mediaRef = "mqtt:$mid"
                    mediaIv = enc.first
                    val token = client?.publish("$base/chat/blob/$mid", enc.second, 1, true)
                    token?.waitForCompletion(20_000L)
                }
                val plain = JSONObject().apply {
                    put("name", myName); put("color", colorString(myColor)); put("text", text.take(500))
                    if (replyText.isNotBlank()) put("reply", JSONObject().apply {
                        put("mid", replyMid); put("name", replyName.take(20)); put("text", replyText.take(120))
                    })
                    put("time", time); put("senderId", memberId); put("mid", mid); put("ts", ts)
                    if (type == "photo") { put("img", mediaRef); put("iv", mediaIv) }
                    if (type == "voice") {
                        put("aud", mediaRef); put("iv", mediaIv); put("dur", dur); put("wave", wave)
                    }
                }
                val envelope = encryptJson(plain).toString().toByteArray(StandardCharsets.UTF_8)
                // Website/current members ko live topic; native late join ko retained per-message topic.
                client?.publish("$base/chat", envelope, 1, false)?.waitForCompletion(20_000L)
                client?.publish("$base/chat/msg/$mid", envelope, 1, true)?.waitForCompletion(20_000L)
            } catch (_: Throwable) {
                postStatus(false, "⚠️ Message tower tak nahi gaya")
            }
        }
        return mid
    }

    fun sendReaction(mid: String, emoji: String) {
        if (!connected || mid.isBlank() || emoji.isBlank()) return
        val ev = JSONObject().apply {
            put("t", "react"); put("mid", mid); put("e", emoji); put("from", memberId)
            put("by", myName); put("id", randomId())
        }
        publish("$base/react", ev.toString().toByteArray(), false)
    }

    /** Real Room typing pulse; no demo/fake typer is ever generated. */
    fun sendTyping(active: Boolean) {
        if (!connected || memberId.isBlank()) return
        val now = System.currentTimeMillis()
        if (active && now - lastTypingSentAt < 1_200L) return
        if (active) {
            lastTypingSentAt = now
            val body = JSONObject().apply { put("name", myName); put("ts", now) }
            publish("$base/typing/$memberId", body.toString().toByteArray(StandardCharsets.UTF_8), false)
        } else {
            lastTypingSentAt = 0L
            publish("$base/typing/$memberId", ByteArray(0), false)
        }
    }

    fun deleteMessage(mid: String) {
        if (!connected || mid.isBlank()) return
        val m = messages[mid]
        publish("$base/chat/msg/$mid", ByteArray(0), true)
        val blob = m?.mediaRef?.removePrefix("mqtt:").orEmpty()
        if (blob.isNotBlank()) publish("$base/chat/blob/$blob", ByteArray(0), true)
        val ev = JSONObject().apply { put("t", "purge"); put("mid", mid); put("id", randomId()) }
        publish("$base/events", ev.toString().toByteArray(), false)
        messages.remove(mid); messageJson.remove(mid); deliveredMedia.remove(mid)
        main.post { listener?.onPartyMessageRemoved(mid) }
    }

    fun publishQueue(items: List<PartyQueueItem>, index: Int = queueIndex): Boolean {
        if (!connected) return false
        val body = JSONObject().apply {
            val a = JSONArray(); items.forEach { a.put(it.toJson()) }
            put("items", a); put("index", index); put("lastAdvance", System.currentTimeMillis())
        }
        queue = items.map { it.copy() }.toMutableList()
        queueIndex = index
        publish("$base/queue", body.toString().toByteArray(), true)
        emitQueue()
        return true
    }

    /** Keep a fresh in-process native checkpoint for header Leave and Recents swipe-away. */
    fun updatePlaybackCheckpoint(
        media: PartyPlaybackMedia,
        time: Double,
        playing: Boolean,
        title: String,
        itemIndex: Int,
        item: PartyQueueItem?,
        epoch: JSONArray?
    ) {
        val now = System.currentTimeMillis()
        val previous = playbackState
        val parsedEpoch = epochParts(epoch)
        playbackCheckpoint = PartyPlaybackState(
            media = media,
            time = time.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            playing = playing,
            at = now,
            title = title.take(200),
            queueIndex = itemIndex,
            queueItem = item?.copy(),
            clockRunning = playing,
            frozen = false,
            capturedAt = now,
            revisionCounter = previous?.revisionCounter ?: -1L,
            revisionOwner = previous?.revisionOwner.orEmpty(),
            epochCounter = parsedEpoch.first.takeIf { it >= 0 } ?: previous?.epochCounter ?: -1L,
            epochOwner = parsedEpoch.second.takeIf { parsedEpoch.first >= 0 } ?: previous?.epochOwner.orEmpty(),
            updatedBy = memberId
        )
    }

    /**
     * Retained /state remains website-compatible, while v2 metadata makes Room restoration
     * atomic for native clients. A frozen state keeps playing=true but at=0, so old website
     * clients also resume at the exact Leave position instead of adding empty-Room time.
     */
    fun publishPlaybackState(
        media: PartyPlaybackMedia,
        time: Double,
        playing: Boolean,
        title: String = "",
        itemIndex: Int = queueIndex,
        item: PartyQueueItem? = queue.getOrNull(itemIndex),
        clockRunning: Boolean = playing,
        frozen: Boolean = false,
        epoch: JSONArray? = null
    ): Boolean {
        if (!connected) return false
        val now = System.currentTimeMillis()
        val revision = nextStateRevision()
        val parsedEpoch = epochParts(epoch)
        val state = PartyPlaybackState(
            media = media,
            time = time.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            playing = playing,
            at = if (frozen) 0L else now,
            title = title.take(200),
            queueIndex = itemIndex,
            queueItem = item?.copy(),
            clockRunning = clockRunning && playing && !frozen,
            frozen = frozen,
            capturedAt = now,
            revisionCounter = revision.first,
            revisionOwner = revision.second,
            epochCounter = parsedEpoch.first,
            epochOwner = parsedEpoch.second,
            updatedBy = memberId
        )
        val body = playbackStateJson(state)
        synchronized(playbackLock) {
            playbackState = state
            playbackCheckpoint = state
            lastPlaybackPayload = body.toString()
        }
        publish("$base/state", body.toString().toByteArray(StandardCharsets.UTF_8), true)
        return true
    }

    private fun nextStateRevision(): Pair<Long, String> = synchronized(playbackLock) {
        stateRevisionCounter += 1L
        stateRevisionCounter to memberId
    }

    private fun epochParts(value: JSONArray?): Pair<Long, String> {
        if (value == null || value.length() != 2) return -1L to ""
        val counter = value.optLong(0, -1L)
        val owner = value.optString(1)
        return if (counter in 0 until 1_000_000_000_000L && owner.length <= 160) counter to owner
        else -1L to ""
    }

    private fun playbackStateJson(state: PartyPlaybackState): JSONObject = state.media.toJson().apply {
        put("stateVersion", 2)
        put("time", state.time)
        put("playing", state.playing)
        put("at", state.at)
        put("capturedAt", state.capturedAt)
        put("clockRunning", state.clockRunning)
        put("frozen", state.frozen)
        put("title", state.title)
        put("queueIndex", state.queueIndex)
        state.queueItem?.let { put("queueItem", it.toJson()) }
        if (state.revisionCounter >= 0) {
            put("rev", JSONArray().put(state.revisionCounter).put(state.revisionOwner))
        }
        if (state.epochCounter >= 0) {
            put("epoch", JSONArray().put(state.epochCounter).put(state.epochOwner))
        }
        put("updatedBy", state.updatedBy)
    }

    private fun compareVersion(aCounter: Long, aOwner: String, bCounter: Long, bOwner: String): Int =
        if (aCounter != bCounter) aCounter.compareTo(bCounter) else aOwner.compareTo(bOwner)

    /** Explicit playback controls are transient. [_wp4] carries the no-host sync epoch. */
    fun publishPlaybackCommand(
        action: String,
        time: Double? = null,
        playing: Boolean? = null,
        media: PartyPlaybackMedia? = null,
        user: Boolean = false,
        wp4: JSONObject? = null
    ): Boolean {
        if (!connected) return false
        val body = JSONObject().apply {
            put("action", action); put("from", memberId); put("by", myName); put("rid", randomId())
            if (time != null && time.isFinite() && time >= 0) put("time", time)
            if (playing != null) put("playing", playing)
            if (media != null) put("video", media.toJson())
            if (user) put("user", true)
            if (wp4 != null) put("_wp4", wp4)
        }
        publish("$base/cmd", body.toString().toByteArray(StandardCharsets.UTF_8), false)
        return true
    }

    fun publishSyncPacket(payload: JSONObject): Boolean {
        if (!connected) return false
        val body = JSONObject().apply {
            put("action", "wp-sync4"); put("from", memberId); put("by", myName)
            put("rid", randomId()); put("payload", payload)
        }
        publish("$base/cmd", body.toString().toByteArray(StandardCharsets.UTF_8), false)
        return true
    }

    /** Explicit confirmed Leave only. Disconnect/glitch kabhi yahan nahi aata. */
    fun leave(done: (() -> Unit)? = null) {
        if (explicitLeaving) return
        sendTyping(false)
        explicitLeaving = true
        main.removeCallbacks(presenceBeat)
        val now = System.currentTimeMillis()
        app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.let { prefs ->
            prefs.edit()
                .putLong(leaveCursorKey(), now)
                .putBoolean("party_live", false)
                .apply {
                    if (!connected) putString(pendingLeaveKey(), memberId)
                    remove(sessionKey())
                }
                .apply()
        }

        io.execute {
            try {
                if (connected) {
                    publishEvent("leave")
                    client?.publish("$base/members/$memberId", ByteArray(0), 1, true)
                        ?.waitForCompletion(5_000L)
                    // Kisi aur ka just-before-us explicit Leave callback process hone do;
                    // glitch member retained rahega, isliye usay ghalti se last nahi samjhenge.
                    try { Thread.sleep(350L) } catch (_: Throwable) {}
                    if (members.keys.none { it != memberId }) {
                        freezeRetainedPlayback()
                        clearRetainedChat()
                    }
                }
            } catch (_: Throwable) { }
            disconnectClient()
            resetTransient()
            main.post { done?.invoke() }
        }
    }

    /** Final proper member freezes exact native time; the empty Room clock never keeps running. */
    private fun freezeRetainedPlayback() {
        val c = client ?: return
        val source = playbackCheckpoint ?: playbackState ?: return
        val now = System.currentTimeMillis()
        val revision = nextStateRevision()
        val index = source.queueIndex.takeIf { it >= 0 } ?: queueIndex
        val item = source.queueItem?.copy() ?: queue.getOrNull(index)?.copy()
        val frozenState = source.copy(
            at = 0L,
            title = source.title.take(200),
            queueIndex = index,
            queueItem = item,
            clockRunning = false,
            frozen = true,
            capturedAt = now,
            revisionCounter = revision.first,
            revisionOwner = revision.second,
            updatedBy = memberId
        )
        val body = playbackStateJson(frozenState)
        synchronized(playbackLock) {
            playbackState = frozenState
            playbackCheckpoint = frozenState
            lastPlaybackPayload = body.toString()
        }
        try {
            c.publish("$base/state", body.toString().toByteArray(StandardCharsets.UTF_8), 1, true)
                .waitForCompletion(8_000L)
        } catch (_: Throwable) {}
    }

    /** Aakhri explicit member: retained chat/media clean; playback and queue survive. */
    private fun clearRetainedChat() {
        val c = client ?: return
        var last: IMqttDeliveryToken? = null
        messages.values.toList().forEach { m ->
            try { last = c.publish("$base/chat/msg/${m.mid}", ByteArray(0), 1, true) } catch (_: Throwable) {}
            val blob = m.mediaRef.removePrefix("mqtt:")
            if (blob.isNotBlank()) {
                try { last = c.publish("$base/chat/blob/$blob", ByteArray(0), 1, true) } catch (_: Throwable) {}
            }
        }
        try { last?.waitForCompletion(8_000L) } catch (_: Throwable) {}
    }

    private fun handle(topic: String, payload: ByteArray) {
        when {
            topic.startsWith("$base/members/") -> handleMember(topic.substringAfterLast('/'), payload)
            topic.startsWith("$base/typing/") -> handleTyping(topic.substringAfterLast('/'), payload)
            topic.startsWith("$base/chat/blob/") -> handleBlob(topic.substringAfterLast('/'), payload)
            topic.startsWith("$base/chat/msg/") -> handleRetainedMessage(topic.substringAfterLast('/'), payload)
            topic == "$base/chat" -> if (payload.isNotEmpty()) handleMessageEnvelope(payload)
            topic == "$base/react" -> if (payload.isNotEmpty()) handleReaction(payload)
            topic == "$base/events" -> if (payload.isNotEmpty()) handleEvent(payload)
            topic == "$base/queue" -> handleQueue(payload)
            topic == "$base/state" -> handlePlaybackState(payload)
            topic == "$base/cmd" -> if (payload.isNotEmpty()) handlePlaybackCommand(payload)
        }
    }

    private fun handleMember(id: String, payload: ByteArray) {
        if (payload.isEmpty()) {
            members.remove(id)
            if (typingUsers.remove(id) != null) emitTyping()
        } else {
            val o = JSONObject(String(payload, StandardCharsets.UTF_8))
            members[id] = PartyMember(id, o.optString("name", "Friend"),
                parseColor(o.optString("color")), o.optLong("ts", System.currentTimeMillis()))
        }
        emitMembers()
    }

    private fun handleTyping(id: String, payload: ByteArray) {
        if (id == memberId) return
        if (payload.isEmpty()) typingUsers.remove(id) else {
            val o = JSONObject(String(payload, StandardCharsets.UTF_8))
            val name = o.optString("name", members[id]?.name ?: "Friend").take(20)
            typingUsers[id] = name to (System.currentTimeMillis() + 4_000L)
        }
        emitTyping()
        main.removeCallbacks(typingSweep)
        if (typingUsers.isNotEmpty()) main.postDelayed(typingSweep, 1_000L)
    }

    private fun handleRetainedMessage(mid: String, payload: ByteArray) {
        if (payload.isEmpty()) {
            messages.remove(mid); messageJson.remove(mid); deliveredMedia.remove(mid)
            main.post { listener?.onPartyMessageRemoved(mid) }
        } else handleMessageEnvelope(payload)
    }

    private fun handleMessageEnvelope(payload: ByteArray) {
        val plain = decryptJson(JSONObject(String(payload, StandardCharsets.UTF_8))) ?: return
        val m = parseMessage(plain)
        messageJson[m.mid] = plain
        val fresh = messages.put(m.mid, m) == null
        trimRetainedIfNeeded()
        if (fresh && m.ts > visibleAfter) main.post { listener?.onPartyMessage(m) }
        tryDeliverMedia(m)
        emitReaction(m.mid)
    }

    private fun parseMessage(o: JSONObject): PartyMessage {
        val reply = o.optJSONObject("reply")
        val img = o.optString("img")
        val aud = o.optString("aud")
        val type = when { img.isNotBlank() -> "photo"; aud.isNotBlank() -> "voice"; else -> "text" }
        return PartyMessage(
            mid = o.optString("mid").ifBlank { randomId() },
            senderId = o.optString("senderId"),
            name = o.optString("name", "Friend"),
            color = parseColor(o.optString("color")),
            text = o.optString("text"),
            replyName = reply?.optString("name") ?: "",
            replyText = reply?.optString("text") ?: "",
            replyMid = reply?.optString("mid") ?: "",
            time = o.optString("time"),
            ts = o.optLong("ts", System.currentTimeMillis()),
            type = type,
            mediaRef = if (type == "photo") img else if (type == "voice") aud else "",
            mediaIv = o.optString("iv"),
            dur = o.optInt("dur"),
            wave = o.optString("wave")
        )
    }

    private fun handleBlob(id: String, payload: ByteArray) {
        if (payload.isEmpty()) { encryptedBlobs.remove(id); return }
        encryptedBlobs[id] = payload
        messages.values.filter { it.mediaRef == "mqtt:$id" }.forEach(::tryDeliverMedia)
    }

    private fun tryDeliverMedia(m: PartyMessage) {
        if (m.mediaRef.isBlank() || m.mediaIv.isBlank() || m.mid in deliveredMedia) return
        val id = m.mediaRef.removePrefix("mqtt:")
        val bytes = encryptedBlobs[id] ?: return
        try {
            val raw = decryptBytes(m.mediaIv, bytes)
            deliveredMedia.add(m.mid)
            encryptedBlobs.remove(id)
            if (m.ts > visibleAfter) main.post { listener?.onPartyMedia(m.mid, raw) }
        } catch (_: Throwable) { }
    }

    private fun handleReaction(payload: ByteArray) {
        val o = JSONObject(String(payload, StandardCharsets.UTF_8))
        if (o.optString("t") != "react") return
        val mid = o.optString("mid"); val emoji = o.optString("e"); val from = o.optString("from")
        if (mid.isBlank() || emoji.isBlank() || from.isBlank()) return
        val byEmoji = reactions.getOrPut(mid) { LinkedHashMap() }
        // Ek member ki ek reaction: purani emoji se hatao, same emoji ho to toggle off.
        val already = byEmoji[emoji]?.contains(from) == true
        byEmoji.values.forEach { it.remove(from) }
        if (!already) byEmoji.getOrPut(emoji) { LinkedHashSet() }.add(from)
        byEmoji.entries.removeAll { it.value.isEmpty() }
        emitReaction(mid)
    }

    private fun emitReaction(mid: String) {
        val summary = linkedMapOf<String, Pair<Int, Boolean>>()
        reactions[mid]?.forEach { (emoji, ids) ->
            if (ids.isNotEmpty()) summary[emoji] = ids.size to ids.contains(memberId)
        }
        main.post { listener?.onPartyReactions(mid, summary) }
    }

    private fun handleEvent(payload: ByteArray) {
        val o = JSONObject(String(payload, StandardCharsets.UTF_8))
        when (o.optString("t")) {
            "leave" -> {
                val id = o.optString("from")
                members.remove(id)
                if (typingUsers.remove(id) != null) emitTyping()
                emitMembers()
            }
            "purge" -> {
                val mid = o.optString("mid")
                if (mid.isNotBlank()) {
                    messages.remove(mid); messageJson.remove(mid); deliveredMedia.remove(mid)
                    main.post { listener?.onPartyMessageRemoved(mid) }
                }
            }
        }
    }

    private fun handleQueue(payload: ByteArray) {
        if (payload.isEmpty()) { queue.clear(); queueIndex = -1; emitQueue(); return }
        val o = JSONObject(String(payload, StandardCharsets.UTF_8))
        val a = o.optJSONArray("items") ?: JSONArray()
        val next = mutableListOf<PartyQueueItem>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { next.add(PartyQueueItem.fromJson(it)) }
        queue = next
        queueIndex = o.optInt("index", -1)
        emitQueue()
    }

    private fun handlePlaybackState(payload: ByteArray) {
        if (payload.isEmpty()) {
            synchronized(playbackLock) {
                playbackState = null; playbackCheckpoint = null; lastPlaybackPayload = ""
            }
            return
        }
        val raw = String(payload, StandardCharsets.UTF_8)
        synchronized(playbackLock) { if (raw == lastPlaybackPayload) return }
        val o = JSONObject(raw)
        val media = PartyPlaybackMedia.fromJson(o)
        if (media.type.isBlank() || media.type == "none") return
        val revision = epochParts(o.optJSONArray("rev"))
        val commandEpoch = epochParts(o.optJSONArray("epoch"))
        val playing = o.optBoolean("playing")
        val frozen = o.optBoolean("frozen", false)
        val wireAt = o.optLong("at", 0L).coerceAtLeast(0L)
        val state = PartyPlaybackState(
            media = media,
            time = o.optDouble("time", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            playing = playing,
            at = wireAt,
            title = o.optString("title").take(200),
            queueIndex = o.optInt("queueIndex", -1),
            queueItem = o.optJSONObject("queueItem")?.let(PartyQueueItem::fromJson),
            clockRunning = if (o.has("clockRunning")) o.optBoolean("clockRunning")
                else playing && wireAt > 0L,
            frozen = frozen,
            capturedAt = o.optLong("capturedAt", wireAt).coerceAtLeast(0L),
            revisionCounter = revision.first,
            revisionOwner = revision.second,
            epochCounter = commandEpoch.first,
            epochOwner = commandEpoch.second,
            updatedBy = o.optString("updatedBy")
        )
        val accepted = synchronized(playbackLock) {
            val current = playbackState
            if (revision.first >= 0) {
                stateRevisionCounter = maxOf(stateRevisionCounter, revision.first)
                if (current != null && current.revisionCounter >= 0 &&
                    compareVersion(revision.first, revision.second,
                        current.revisionCounter, current.revisionOwner) <= 0) {
                    false
                } else true
            } else true
        }
        if (!accepted) return
        synchronized(playbackLock) {
            playbackState = state
            playbackCheckpoint = state
            lastPlaybackPayload = raw
        }
        main.post { listener?.onPartyPlaybackState(state) }
    }

    private fun handlePlaybackCommand(payload: ByteArray) {
        val raw = JSONObject(String(payload, StandardCharsets.UTF_8))
        if (raw.optString("from") == memberId) return
        val rid = raw.optString("rid")
        if (rid.isNotBlank()) {
            synchronized(seenPlaybackCommands) {
                if (!seenPlaybackCommands.add(rid)) return
                while (seenPlaybackCommands.size > 500) {
                    val first = seenPlaybackCommands.firstOrNull() ?: break
                    seenPlaybackCommands.remove(first)
                }
            }
        }
        val media = raw.optJSONObject("video")?.let(PartyPlaybackMedia::fromJson)
        val time = raw.optDouble("time", Double.NaN).takeIf { it.isFinite() && it >= 0 }
        val playing = if (raw.has("playing")) raw.optBoolean("playing") else null
        val command = PartyPlaybackCommand(raw.optString("action"), raw.optString("from"),
            raw.optString("by", "Friend"), time, playing, raw.optBoolean("user"), media, raw)
        main.post { listener?.onPartyPlaybackCommand(command) }
    }

    private fun trimRetainedIfNeeded() {
        if (messages.size <= KEEP) return
        val old = messages.values.sortedBy { it.ts }.take(messages.size - KEEP)
        old.forEach { m ->
            messages.remove(m.mid); messageJson.remove(m.mid)
            if (connected) {
                publish("$base/chat/msg/${m.mid}", ByteArray(0), true)
                val blob = m.mediaRef.removePrefix("mqtt:")
                if (blob.isNotBlank()) publish("$base/chat/blob/$blob", ByteArray(0), true)
                val ev = JSONObject().apply { put("t", "purge"); put("mid", m.mid); put("id", randomId()) }
                publish("$base/events", ev.toString().toByteArray(), false)
            }
            main.post { listener?.onPartyMessageRemoved(m.mid) }
        }
    }

    private fun emitSnapshot() {
        postStatus(connected, if (connected) "🗼 ${towerLabel(towerIndex)} connected" else "📻 Tower reconnect ho raha hai…")
        emitMembers(); emitQueue(); emitTyping()
        playbackState?.let { state -> main.post { listener?.onPartyPlaybackState(state) } }
        val list = messages.values.filter { it.ts > visibleAfter }.sortedBy { it.ts }
        list.forEach { m -> main.post { listener?.onPartyMessage(m) } }
        list.forEach(::tryDeliverMedia)
    }

    private fun emitMembers() {
        val list = members.values.sortedBy { it.name.lowercase() }
        main.post { listener?.onPartyMembers(list) }
    }

    private fun emitQueue() {
        val copy = queue.map { it.copy() }
        val index = queueIndex
        main.post { listener?.onPartyQueue(copy, index) }
    }

    private fun emitTyping() {
        val now = System.currentTimeMillis()
        val names = typingUsers.values.filter { it.second >= now }.map { it.first }.distinct().sorted()
        main.post { listener?.onPartyTyping(names) }
    }

    private fun postStatus(up: Boolean, text: String) {
        main.post { listener?.onPartyTowerStatus(up, text) }
    }

    private fun publish(topic: String, payload: ByteArray, retained: Boolean) {
        try { client?.takeIf { connected }?.publish(topic, payload, 1, retained) } catch (_: Throwable) { }
    }

    private fun disconnectClient() {
        connected = false
        joining = false
        main.removeCallbacks(presenceBeat)
        main.removeCallbacks(typingSweep)
        if (typingUsers.isNotEmpty()) {
            typingUsers.clear()
            emitTyping()
        }
        val c = client
        client = null
        try { c?.disconnectForcibly(500L, 500L, false) } catch (_: Throwable) { }
        try { c?.close() } catch (_: Throwable) { }
    }

    private fun resetTransient() {
        members.clear(); typingUsers.clear(); lastTypingSentAt = 0L
        messages.clear(); messageJson.clear(); encryptedBlobs.clear()
        deliveredMedia.clear(); reactions.clear(); queue.clear(); queueIndex = -1
        synchronized(playbackLock) {
            playbackState = null; playbackCheckpoint = null; lastPlaybackPayload = ""
            stateRevisionCounter = 0L
        }
        synchronized(seenPlaybackCommands) { seenPlaybackCommands.clear() }
    }

    private fun cleanRoom(raw: String): String = raw.trim().replace(Regex("[#+\\u0000]"), "").take(20).ifBlank { "main" }
    private fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(20)
    private fun sessionKey(): String = "party_session_${towerIndex}_${roomName.lowercase().hashCode()}"
    private fun leaveCursorKey(): String = "party_left_${towerIndex}_${roomName.lowercase().hashCode()}"
    private fun pendingLeaveKey(): String = "party_pending_left_${towerIndex}_${roomName.lowercase().hashCode()}"

    private fun clearPendingExplicitLeave() {
        val prefs = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE) ?: return
        val oldId = prefs.getString(pendingLeaveKey(), "").orEmpty()
        if (oldId.isBlank()) return
        try { client?.publish("$base/members/$oldId", ByteArray(0), 1, true) } catch (_: Throwable) {}
        prefs.edit().remove(pendingLeaveKey()).apply()
    }

    private fun colorString(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
    private fun parseColor(s: String): Int = try { android.graphics.Color.parseColor(s) } catch (_: Throwable) { 0xff54e8ff.toInt() }

    private fun deriveKey(room: String): SecretKey {
        val material = "wp|" + room.lowercase().trim()
        val spec = PBEKeySpec(material.toCharArray(), E2E_SALT.toByteArray(StandardCharsets.UTF_8), 150_000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun encryptJson(value: JSONObject): JSONObject {
        val key = secret ?: throw IllegalStateException("room key missing")
        val iv = ByteArray(12).also(secure::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val out = cipher.doFinal(value.toString().toByteArray(StandardCharsets.UTF_8))
        return JSONObject().apply {
            put("e2e", 1)
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ct", Base64.encodeToString(out, Base64.NO_WRAP))
        }
    }

    private fun decryptJson(envelope: JSONObject): JSONObject? {
        return try {
            if (envelope.optInt("e2e") != 1) return envelope
            val iv = Base64.decode(envelope.getString("iv"), Base64.DEFAULT)
            val data = Base64.decode(envelope.getString("ct"), Base64.DEFAULT)
            val key = secret ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(data), StandardCharsets.UTF_8))
        } catch (_: Throwable) { null }
    }

    private fun encryptBytes(value: ByteArray): Pair<String, ByteArray> {
        val key = secret ?: throw IllegalStateException("room key missing")
        val iv = ByteArray(12).also(secure::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv, Base64.NO_WRAP) to cipher.doFinal(value)
    }

    private fun decryptBytes(ivText: String, value: ByteArray): ByteArray {
        val key = secret ?: throw IllegalStateException("room key missing")
        val iv = Base64.decode(ivText, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(value)
    }
}
