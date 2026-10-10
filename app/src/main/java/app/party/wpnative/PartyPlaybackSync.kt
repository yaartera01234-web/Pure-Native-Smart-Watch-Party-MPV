package app.party.wpnative

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** Native sample exposed to the same wp-sync4 wire used by the browser/ACT7 player. */
internal data class PartySyncSample(
    val media: String,
    val position: Double,
    val playing: Boolean,
    val buffering: Boolean,
    val rate: Double,
    val ready: Boolean
)

/**
 * No-host, slowest-member coordinator ported from Smart Music Watch Party's sync-room.js.
 * Explicit play/pause/seek remains immediate; these probes only remove ordinary drift.
 */
internal class PartyPlaybackSync(
    private val myId: () -> String,
    private val mediaKey: () -> String?,
    private val sample: () -> PartySyncSample?,
    private val send: (JSONObject) -> Unit,
    private val apply: (seek: Double?, speed: Double, playing: Boolean?) -> Unit
) {
    enum class RetainedDecision { REJECT, METADATA_ONLY, APPLY }

    private data class Epoch(val counter: Long, val owner: String)
    private data class Peer(
        val position: Double,
        val playing: Boolean,
        val moving: Boolean,
        val rate: Double,
        val at: Double,
        val sent: Double,
        val media: String
    )

    private val session = UUID.randomUUID().toString().replace("-", "").take(12)
    private var sequence = 0L
    private var epoch = Epoch(0L, "")
    private var retainedAppliedEpoch: Epoch? = null
    private val pending = LinkedHashMap<String, Double>()
    private val peers = LinkedHashMap<String, Peer>()
    private var needAnchor = true
    private var wasReady = false
    private var readyAt = 0.0
    private var quietUntil = 0.0
    private var speed = 1.0
    private var filtered: Double? = null
    private var level = 0
    private var lastSeek = -100.0

    private fun now() = SystemClock.elapsedRealtime() / 1000.0

    fun reset(anchor: Boolean) {
        normal()
        peers.clear(); pending.clear()
        needAnchor = anchor
        wasReady = false
        quietUntil = now() + 2.5
    }

    /** Background/disconnect peers cannot remain a slow Room reference or keep a bent speed. */
    fun suspend() {
        normal(); peers.clear(); pending.clear(); needAnchor = true; wasReady = false
    }

    fun currentEpoch(): JSONArray = epochJson(epoch)

    /**
     * A retained snapshot also carries the last explicit command epoch. It seeds a fresh
     * Activity before its first command, but an equal snapshot which followed an already
     * applied live command must not rewind that active player a second time.
     */
    fun onRetainedState(counter: Long, owner: String, requireRestore: Boolean): RetainedDecision {
        if (counter >= 0L) {
            val incoming = Epoch(counter, owner)
            if (counter >= 1_000_000_000_000L || owner.length > 160) return RetainedDecision.REJECT
            val order = compare(incoming, epoch)
            if (order < 0) return RetainedDecision.REJECT
            if (order == 0 && !requireRestore) return RetainedDecision.METADATA_ONLY
            if (order > 0) epoch = incoming
            retainedAppliedEpoch = incoming
        } else retainedAppliedEpoch = null
        reset(anchor = true)
        return RetainedDecision.APPLY
    }

    /** Metadata attached to an explicit legacy command, understood by current browser peers. */
    fun localCommand(): JSONObject {
        epoch = Epoch(epoch.counter + 1, myId())
        retainedAppliedEpoch = null
        reset(anchor = false)
        return JSONObject().put("epoch", epochJson(epoch)).put("media", mediaKey() ?: "none:")
    }

    /** Reject stale/mismatched modern commands, while accepting legacy clients unchanged. */
    fun acceptRemote(command: JSONObject): Boolean {
        val action = command.optString("action")
        if (action == "wp-sync4") {
            receive(command.optString("from"), command.optJSONObject("payload"))
            return false
        }
        val modern = command.optJSONObject("_wp4")
        if (modern == null) {
            reset(anchor = false)
            return true
        }
        val incoming = parseEpoch(modern.optJSONArray("epoch")) ?: return false
        if (action != "load") {
            val expected = mediaKey()
            if (expected != null && modern.optString("media") != expected) return false
        }
        if (compare(incoming, epoch) < 0) return false
        if (retainedAppliedEpoch == incoming) {
            retainedAppliedEpoch = null
            return false
        }
        epoch = incoming
        retainedAppliedEpoch = null
        reset(anchor = false)
        return true
    }

    fun receive(from: String, packet: JSONObject?) {
        if (from.isBlank() || from == myId() || from.length > 160 || packet == null || packet.optInt("v") != 4) return
        val incoming = parseEpoch(packet.optJSONArray("epoch")) ?: return
        when (packet.optString("kind")) {
            "probe" -> {
                val token = packet.optString("token")
                if (token.isBlank() || token.length > 80) return
                if (compare(incoming, epoch) > 0) {
                    epoch = incoming
                    reset(anchor = true)
                }
                send(packet("sample").put("to", from).put("token", token)
                    .put("sample", sampleJson(currentSample())))
            }
            "sample" -> {
                if (packet.optString("to") != myId()) return
                val token = packet.optString("token")
                val sent = pending[token] ?: return
                val receivedAt = now()
                val rtt = receivedAt - sent
                if (rtt !in 0.0..2.5) return
                if (compare(incoming, epoch) > 0) {
                    epoch = incoming
                    reset(anchor = true)
                    return
                }
                if (compare(incoming, epoch) != 0) return
                val value = packet.optJSONObject("sample") ?: run { peers.remove(from); return }
                if (!value.optBoolean("ready") || value.optBoolean("anchor")) {
                    peers.remove(from); return
                }
                val self = currentSample() ?: return
                val media = value.optString("media")
                val position = value.optDouble("pos", Double.NaN)
                val remoteRate = value.optDouble("rate", 1.0)
                val remotePlayingValue = value.opt("playing")
                if (media != self.media || !position.isFinite() || position < 0 || position > 100_000_000 ||
                    remotePlayingValue !is Boolean || remoteRate !in listOf(.95, .995, 1.0, 1.005)) return
                val old = peers[from]
                if (old != null && sent <= old.sent) return
                if (peers.size >= 64 && old == null) return
                val remotePlaying = remotePlayingValue
                val moving = remotePlaying && !value.optBoolean("buffering")
                peers[from] = Peer(position + if (moving) rtt * .5 * remoteRate else 0.0,
                    remotePlaying, moving, remoteRate, receivedAt, sent, media)
            }
        }
    }

    fun tick() {
        val t = now()
        val self = currentSample()
        if (self == null) {
            if (t - lastSeek >= 3.0) suspend()
            return
        }
        if (!wasReady) { wasReady = true; readyAt = t }
        pending.entries.removeAll { t - it.value > 3.0 }
        peers.entries.removeAll { t - it.value.at > 3.5 || it.value.media != self.media }
        val token = "$session:${++sequence}"
        pending[token] = t
        send(packet("probe").put("token", token))
        if (!self.ready) { normal(); return }

        var target: Double? = null
        var targetPlaying = self.playing
        var targetIsOther = false
        peers.forEach { (id, peer) ->
            if (!needAnchor && peer.playing != self.playing) return@forEach
            val position = peer.position + if (peer.moving) (t - peer.at) * peer.rate else 0.0
            if (target == null || position < target!!) {
                target = position; targetPlaying = peer.playing; targetIsOther = id != myId()
            }
        }
        if (target == null) {
            normal()
            if (t - readyAt >= 4.0) needAnchor = false
            return
        }
        val first = needAnchor
        needAnchor = false
        if (!first && self.position <= target!!) {
            target = self.position; targetIsOther = false
        }
        decide(t, self, target!!, targetPlaying, first, targetIsOther)
    }

    private fun decide(t: Double, self: PartySyncSample, target: Double, targetPlaying: Boolean,
                       first: Boolean, targetIsOther: Boolean) {
        if (first) {
            normal()
            lastSeek = t; quietUntil = t + 2.5; peers.clear()
            apply(target.coerceAtLeast(0.0), 1.0, targetPlaying)
            return
        }
        if (t - lastSeek < 2.5) { normal(); return }
        val delta = self.position - target
        if (delta > 8.0) {
            normal(); lastSeek = t; quietUntil = t + 2.5; peers.clear()
            apply(target.coerceAtLeast(0.0), 1.0, null)
            return
        }
        if (!self.playing) { normal(); return }
        if (speed == .95 && delta >= .1) { setSpeed(.95); return }
        if (delta > 1.5 && targetIsOther) {
            filtered = null; level = 0; setSpeed(.95); return
        }
        if (speed == .95) { filtered = null; level = 0; setSpeed(1.0); return }
        filtered = filtered?.let { it + .3 * (delta - it) } ?: delta
        if (level == 0 && filtered!! > .15) level = -1
        else if (level == -1 && filtered!! < .03) level = 0
        setSpeed(1.0 + level * .005)
    }

    private fun currentSample(): PartySyncSample? {
        val value = sample() ?: return null
        if (!value.ready || value.media.isBlank() || !value.position.isFinite() || value.position < 0 ||
            !value.rate.isFinite()) return null
        return value.copy(ready = now() >= quietUntil)
    }

    private fun setSpeed(value: Double) {
        if (abs(speed - value) < .00001) return
        speed = value
        apply(null, value, null)
    }

    private fun normal() {
        filtered = null; level = 0
        setSpeed(1.0)
    }

    private fun packet(kind: String) = JSONObject()
        .put("v", 4).put("kind", kind).put("session", session).put("epoch", epochJson(epoch))

    private fun sampleJson(value: PartySyncSample?): Any {
        if (value == null) return JSONObject.NULL
        return JSONObject().put("media", value.media).put("pos", value.position).put("age", 0)
            .put("playing", value.playing).put("buffering", value.buffering).put("rate", value.rate)
            .put("ready", value.ready).put("anchor", needAnchor)
    }

    private fun epochJson(value: Epoch) = JSONArray().put(value.counter).put(value.owner)
    private fun parseEpoch(value: JSONArray?): Epoch? {
        if (value == null || value.length() != 2) return null
        val counter = value.optLong(0, -1)
        val owner = value.optString(1)
        if (counter !in 0 until 1_000_000_000_000L || owner.length > 160) return null
        return Epoch(counter, owner)
    }
    private fun compare(a: Epoch, b: Epoch): Int =
        if (a.counter != b.counter) a.counter.compareTo(b.counter) else a.owner.compareTo(b.owner)
}
