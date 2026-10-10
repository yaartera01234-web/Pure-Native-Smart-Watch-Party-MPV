package app.party.wpnative

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.firebase.firestore.ListenerRegistration
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** Pure-native, audio-only WebRTC call owner. Activities may disappear; this service stays. */
class VoiceCallService : Service() {
    companion object {
        const val ACTION_OUTGOING = "app.party.wpnative.call.OUTGOING"
        const val ACTION_ANSWER = "app.party.wpnative.call.ANSWER"
        const val ACTION_END = "app.party.wpnative.call.END_SERVICE"
        const val ACTION_MUTE = "app.party.wpnative.call.MUTE_SERVICE"
        const val ACTION_SPEAKER = "app.party.wpnative.call.SPEAKER_SERVICE"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_CALL = "call"
        private const val NOTE = CallNotify.ONGOING_ID

        @Volatile private var running = false
        @Volatile private var initialized = false

        fun startOutgoing(ctx: Context, peer: String, code: String, chatId: String, callId: String) {
            start(ctx, Intent(ctx, VoiceCallService::class.java).setAction(ACTION_OUTGOING)
                .putExtra(EXTRA_PEER, peer).putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_CHAT, chatId).putExtra(EXTRA_CALL, callId), foreground = true)
        }

        fun answer(ctx: Context, pending: PendingCall) {
            start(ctx, Intent(ctx, VoiceCallService::class.java).setAction(ACTION_ANSWER)
                .putExtra(EXTRA_PEER, pending.peerName).putExtra(EXTRA_CODE, pending.peerCode)
                .putExtra(EXTRA_CHAT, pending.chatId).putExtra(EXTRA_CALL, pending.callId), foreground = true)
        }

        fun command(ctx: Context, action: String) {
            start(ctx, Intent(ctx, VoiceCallService::class.java).setAction(action), foreground = !running)
        }

        private fun start(ctx: Context, intent: Intent, foreground: Boolean) {
            try {
                if (foreground && Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Throwable) { }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val rtc = Executors.newSingleThreadExecutor { r -> Thread(r, "wp-native-webrtc") }
    private val handled = LinkedHashSet<String>()
    private val queuedCandidates = ArrayList<IceCandidate>()
    private lateinit var audioManager: AudioManager
    private var proximity: PowerManager.WakeLock? = null
    private var selectedRoute = AudioDeviceInfo.TYPE_UNKNOWN
    private var routeCallbackRegistered = false
    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refreshRoute() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { refreshRoute() }
        private fun refreshRoute() {
            main.post {
                if (!ending && CallState.active()) {
                    applyAudioRoute(); updateProximity()
                }
            }
        }
    }
    private var tone: ToneGenerator? = null
    private var ringTask: Runnable? = null
    private var timeoutTask: Runnable? = null
    private var signalReg: ListenerRegistration? = null
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var source: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var remoteTrack: AudioTrack? = null
    private class SpeechGate(var noiseFloor: Double) {
        var candidateSince = 0L
        var lastAboveAt = 0L
    }
    private val speechGateLock = Any()
    private val localSpeechGate = SpeechGate(0.006)
    private val remoteSpeechGate = SpeechGate(0.004)
    @Volatile private var lastVadDispatchAt = 0L
    private var movieDucked = false
    private var unduckTask: Runnable? = null
    private val remoteSpeechSink = object : AudioTrackSink {
        override fun onData(audioData: ByteBuffer, bitsPerSample: Int, sampleRate: Int,
            numberOfChannels: Int, numberOfFrames: Int, absoluteCaptureTimestampMs: Long) {
            if (bitsPerSample == 16) {
                observeSpeechLevel(pcmLevel(audioData, numberOfChannels * numberOfFrames), local = false)
            }
        }
    }
    private var peerConnection: PeerConnection? = null
    private var peerName = "Dost"
    private var peerCode = ""
    private var chatId = ""
    private var callId = ""
    private var outgoing = false
    private var startedAt = 0L
    private var inviteSent = false
    private var lastIceRestartAt = 0L
    @Volatile private var ending = false
    @Volatile private var muted = false
    @Volatile private var speaker = false

    override fun onCreate() {
        super.onCreate()
        running = true
        CallNotify.channels(this)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OUTGOING -> if (!CallState.active()) {
                load(intent, isOutgoing = true); beginOutgoing()
            }
            ACTION_ANSWER -> if (!CallState.active() || CallState.current().phase == CallPhase.INCOMING) {
                load(intent, isOutgoing = false); beginAnswer()
            }
            ACTION_END -> endLocal(if (outgoing && startedAt == 0L) "cancelled" else "ended",
                notifyPeer = true)
            ACTION_MUTE -> toggleMute()
            ACTION_SPEAKER -> toggleSpeaker()
        }
        return START_NOT_STICKY
    }

    private fun load(intent: Intent, isOutgoing: Boolean) {
        peerCode = WpUser.normalizeFriendCode(intent.getStringExtra(EXTRA_CODE))
        val suppliedName = intent.getStringExtra(EXTRA_PEER)?.take(40).orEmpty()
        peerName = Friends.currentName(this, peerCode, suppliedName)
        chatId = intent.getStringExtra(EXTRA_CHAT).orEmpty()
        callId = intent.getStringExtra(EXTRA_CALL).orEmpty()
        outgoing = isOutgoing
    }

    private fun beginOutgoing() {
        if (callId.isBlank() || peerCode.length != 8 || chatId.isBlank()) { endLocal("failed", false); return }
        VoiceRec.abort(); VoicePlay.stop()
        val state = CallSnapshot(callId, chatId, peerName, peerCode, true,
            CallPhase.OUTGOING, status = "Calling…")
        CallState.update(state); startCallForeground(microphone = false); listenSignals(); startRingback()
        inviteSent = true
        CallSignaling.send(this, chatId, peerCode, callId, "invite",
            JSONObject().put("name", WpUser.me(this))) { ok -> if (!ok) endLocal("failed", false) }
        armTimeout(30_000L) {
            CallSignaling.send(this, chatId, peerCode, callId, "cancel")
            endLocal("no_answer", false)
        }
    }

    private fun beginAnswer() {
        if (callId.isBlank() || peerCode.length != 8 || chatId.isBlank()) { endLocal("failed", false); return }
        IncomingCallController.accepted(this, callId)
        VoiceRec.abort(); VoicePlay.stop()
        CallState.update(CallSnapshot(callId, chatId, peerName, peerCode, false,
            CallPhase.CONNECTING, status = "Connecting…"))
        startCallForeground(microphone = true); listenSignals(); configureCallAudio()
        rtc.execute {
            if (!preparePeer()) { main.post { endLocal("failed", true) }; return@execute }
            main.post {
                CallSignaling.send(this, chatId, peerCode, callId, "accept") { ok ->
                    if (!ok) endLocal("failed", false)
                }
                armTimeout(45_000L) { endLocal("failed", true) }
            }
        }
    }

    private fun listenSignals() {
        signalReg?.remove()
        signalReg = CallSignaling.listen(this, chatId, peerCode) { signal ->
            if (signal.callId != callId) return@listen false
            if (handled.add(signal.nonce)) {
                while (handled.size > 300) handled.remove(handled.first())
                handleSignal(signal)
            }
            true
        }
    }

    private fun handleSignal(signal: CallWireSignal) {
        if (ending) return
        when (signal.action) {
            "accept" -> if (outgoing && CallState.current().phase == CallPhase.OUTGOING) {
                stopRingback(); cancelTimeout()
                CallState.mutate { it.copy(phase = CallPhase.CONNECTING, status = "Connecting…") }
                startCallForeground(microphone = true); configureCallAudio()
                rtc.execute {
                    if (!preparePeer()) { main.post { endLocal("failed", true) }; return@execute }
                    createOffer()
                }
                armTimeout(45_000L) { endLocal("failed", true) }
            }
            "offer" -> if (!outgoing) rtc.execute { acceptOffer(signal.body.optString("sdp")) }
            "answer" -> if (outgoing) rtc.execute { acceptAnswer(signal.body.optString("sdp")) }
            "candidate" -> rtc.execute { acceptCandidate(signal.body) }
            "decline" -> endLocal("declined", false)
            "busy" -> endLocal("busy", false)
            "unavailable" -> endLocal("unavailable", false)
            "cancel" -> endLocal("cancelled", false)
            "hangup" -> endLocal("ended", false)
        }
    }

    private fun preparePeer(): Boolean = try {
        synchronized(VoiceCallService::class.java) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions())
                initialized = true
            }
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val module = JavaAudioDeviceModule.builder(applicationContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(attributes)
            .setSamplesReadyCallback { samples ->
                observeSpeechLevel(pcmLevel(samples.data), local = true)
            }
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(false).setUseStereoOutput(false)
            .createAudioDeviceModule()
        val madeFactory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        audioModule = module; factory = madeFactory
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = madeFactory.createPeerConnection(config, observer) ?: return false
        peerConnection = pc
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        val madeSource = madeFactory.createAudioSource(constraints)
        source = madeSource
        val track = madeFactory.createAudioTrack("wp-audio-$callId", madeSource)
        track.setEnabled(!muted)
        // Caller offer ka audio m-line banata hai. Callee pe pehle se apna alag
        // transceiver banane se kuch Unified-Plan builds answer ko recvonly kar dete hain:
        // caller ki awaaz callee ko milti hai, magar callee ki caller ko nahi. Callee track
        // remote offer set hone ke BAAD usi offered m-line ke sender par attach hota hai.
        if (outgoing) {
            pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        }
        localTrack = track
        true
    } catch (_: Throwable) { false }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val audio = receiver.track() as? AudioTrack ?: return
            if (remoteTrack === audio) return
            runCatching { remoteTrack?.removeSink(remoteSpeechSink) }
            remoteTrack = audio
            runCatching { audio.addSink(remoteSpeechSink) }
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            main.post {
                CallSignaling.send(this@VoiceCallService, chatId, peerCode, callId, "candidate",
                    JSONObject().put("mid", candidate.sdpMid ?: "0")
                        .put("line", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> connected()
                PeerConnection.IceConnectionState.DISCONNECTED -> reconnecting()
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED -> main.post { endLocal("failed", true) }
                else -> Unit
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> connected()
                PeerConnection.PeerConnectionState.DISCONNECTED -> reconnecting()
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> main.post { endLocal("failed", true) }
                else -> Unit
            }
        }
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdp() {
                    override fun onSetSuccess() { sendSdp("offer") }
                    override fun onSetFailure(error: String) { main.post { endLocal("failed", true) } }
                }, desc)
            }
            override fun onCreateFailure(error: String) { main.post { endLocal("failed", true) } }
        }, MediaConstraints())
    }

    /**
     * Answerer ka microphone caller ke offered audio m-line par lagao. Is role-specific
     * binding se SDP answer sendrecv rehta hai; extra unassociated callee m-line nahi banti.
     */
    private fun attachAnswererAudio(): Boolean {
        val pc = peerConnection ?: return false
        val track = localTrack ?: return false
        return try {
            val offered = pc.transceivers.firstOrNull { transceiver ->
                transceiver.mid != null && transceiver.receiver.track() is AudioTrack
            }
            if (offered != null) {
                val attached = offered.sender.setTrack(track, false)
                offered.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                attached
            } else {
                // Defensive fallback for a non-standard Unified-Plan implementation;
                // addTrack reuses a compatible recvonly offered transceiver by spec.
                pc.addTrack(track, listOf("wp-audio-stream-$callId")) != null
            }
        } catch (_: Throwable) { false }
    }

    private fun acceptOffer(sdp: String) {
        if (sdp.isBlank()) return
        peerConnection?.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                flushCandidates()
                if (!attachAnswererAudio()) {
                    main.post { endLocal("failed", true) }
                    return
                }
                peerConnection?.createAnswer(object : SimpleSdp() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SimpleSdp() {
                            override fun onSetSuccess() { sendSdp("answer") }
                            override fun onSetFailure(error: String) { main.post { endLocal("failed", true) } }
                        }, desc)
                    }
                    override fun onCreateFailure(error: String) { main.post { endLocal("failed", true) } }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) { main.post { endLocal("failed", true) } }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun acceptAnswer(sdp: String) {
        if (sdp.isBlank()) return
        peerConnection?.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() { flushCandidates() }
            override fun onSetFailure(error: String) { main.post { endLocal("failed", true) } }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun sendSdp(action: String) {
        val sdp = peerConnection?.localDescription?.description.orEmpty()
        if (sdp.isBlank()) { main.post { endLocal("failed", true) }; return }
        main.post { CallSignaling.send(this, chatId, peerCode, callId, action, JSONObject().put("sdp", sdp)) }
    }

    private fun acceptCandidate(body: JSONObject) {
        val value = body.optString("candidate")
        if (value.isBlank()) return
        val candidate = IceCandidate(body.optString("mid", "0"), body.optInt("line", 0), value)
        if (peerConnection?.remoteDescription == null) queuedCandidates += candidate
        else peerConnection?.addIceCandidate(candidate)
    }

    private fun flushCandidates() {
        val pc = peerConnection ?: return
        queuedCandidates.forEach(pc::addIceCandidate); queuedCandidates.clear()
    }

    private fun connected() = main.post {
        if (ending) return@post
        if (startedAt <= 0L) startedAt = System.currentTimeMillis()
        cancelTimeout(); stopRingback(); applyAudioRoute()
        CallState.mutate { it.copy(phase = CallPhase.ACTIVE, startedAt = startedAt, status = "Connected") }
        updateNotification(); updateProximity()
    }

    private fun reconnecting() = main.post {
        if (ending || startedAt <= 0L) return@post
        CallState.mutate { it.copy(phase = CallPhase.RECONNECTING, status = "Reconnecting…") }
        updateNotification(); updateProximity()
        // The caller is the deterministic renegotiation owner, preventing offer glare while
        // still recovering from Wi-Fi/mobile handoffs that need a fresh ICE generation.
        if (outgoing && System.currentTimeMillis() - lastIceRestartAt > 4_000L) {
            lastIceRestartAt = System.currentTimeMillis()
            rtc.execute {
                runCatching { peerConnection?.restartIce() }
                if (!ending && peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
                    createOffer()
                }
            }
        }
        armTimeout(12_000L) { endLocal("failed", true) }
    }

    /** RMS of signed little-endian PCM16, normalized to 0..1. */
    private fun pcmLevel(data: ByteArray): Double {
        var energy = 0L
        var count = 0
        var i = 0
        // Every second sample is enough for speech VAD and halves audio-thread work.
        while (i + 1 < data.size) {
            val sample = ((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            energy += sample.toLong() * sample.toLong(); count++
            i += 4
        }
        return if (count == 0) 0.0 else sqrt(energy.toDouble() / count) / 32768.0
    }

    private fun pcmLevel(data: ByteBuffer, samples: Int): Double {
        val pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = minOf(samples.coerceAtLeast(0), pcm.remaining() / 2)
        if (count <= 0) return 0.0
        val start = pcm.position()
        var energy = 0L
        var measured = 0
        var i = 0
        while (i < count) {
            val sample = pcm.getShort(start + i * 2).toInt()
            energy += sample.toLong() * sample.toLong(); measured++
            i += 2
        }
        return if (measured == 0) 0.0 else sqrt(energy.toDouble() / measured) / 32768.0
    }

    /**
     * A small adaptive speech gate sits in front of movie ducking. A single PCM spike, keyboard
     * tap or other brief room noise must not lower the movie: energy has to remain above the
     * side-specific adaptive threshold for 260ms. The higher local threshold in speaker mode
     * also helps reject movie sound leaking back into the microphone.
     */
    private fun observeSpeechLevel(level: Double, local: Boolean) {
        if (ending || (local && muted) || CallState.current().phase != CallPhase.ACTIVE) return
        val now = SystemClock.elapsedRealtime()
        var confirmed = false
        synchronized(speechGateLock) {
            val gate = if (local) localSpeechGate else remoteSpeechGate
            val baseThreshold = when {
                !local -> 0.018
                speaker -> 0.045
                else -> 0.034
            }
            val adaptiveThreshold = (gate.noiseFloor * 2.2)
                .coerceAtMost(if (local) 0.075 else 0.055)
            val threshold = maxOf(baseThreshold, adaptiveThreshold)
            if (level >= threshold) {
                if (gate.candidateSince == 0L || now - gate.lastAboveAt > 130L) {
                    gate.candidateSince = now
                }
                gate.lastAboveAt = now
                confirmed = now - gate.candidateSince >= 260L
            } else {
                // Learn steady room noise only while it is below the speech threshold. Freezing
                // the floor during a candidate prevents soft speech from teaching itself away.
                val alpha = if (gate.candidateSince == 0L) 0.035 else 0.010
                val learned = level.coerceIn(0.001, baseThreshold)
                gate.noiseFloor = (gate.noiseFloor * (1.0 - alpha) + learned * alpha)
                    .coerceIn(0.002, if (local) 0.032 else 0.024)
                if (now - gate.lastAboveAt > 130L) gate.candidateSince = 0L
            }
        }
        if (confirmed) speechDetected(local)
    }

    /** Confirmed local or remote speech refreshes a 900ms release hold between spoken words. */
    private fun speechDetected(local: Boolean) {
        if (ending || (local && muted) || CallState.current().phase != CallPhase.ACTIVE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVadDispatchAt < 80L) return
        lastVadDispatchAt = now
        main.post {
            if (ending || CallState.current().phase != CallPhase.ACTIVE) return@post
            if (!movieDucked) {
                movieDucked = true
                PartyRoomRoute.setCallSpeechDucking(true)
            }
            unduckTask?.let(main::removeCallbacks)
            unduckTask = Runnable {
                movieDucked = false
                PartyRoomRoute.setCallSpeechDucking(false)
                unduckTask = null
            }.also { main.postDelayed(it, 900L) }
        }
    }

    private fun clearSpeechDucking() {
        unduckTask?.let(main::removeCallbacks); unduckTask = null
        synchronized(speechGateLock) {
            localSpeechGate.noiseFloor = 0.006
            localSpeechGate.candidateSince = 0L
            localSpeechGate.lastAboveAt = 0L
            remoteSpeechGate.noiseFloor = 0.004
            remoteSpeechGate.candidateSince = 0L
            remoteSpeechGate.lastAboveAt = 0L
        }
        lastVadDispatchAt = 0L
        movieDucked = false
        PartyRoomRoute.setCallSpeechDucking(false)
    }

    private fun toggleMute() {
        if (!CallState.active()) return
        muted = !muted; rtc.execute { localTrack?.setEnabled(!muted) }
        CallState.mutate { it.copy(muted = muted) }; updateNotification()
    }

    private fun toggleSpeaker() {
        if (!CallState.active()) return
        speaker = !speaker; applyAudioRoute()
        CallState.mutate { it.copy(speaker = speaker) }; updateNotification(); updateProximity()
    }

    private fun configureCallAudio() {
        // Movie and call are intentionally mixed. Do not take exclusive audio focus: decoded
        // local/remote speech drives precise MPV ducking instead of muting the whole movie.
        clearSpeechDucking()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!routeCallbackRegistered) {
            runCatching { audioManager.registerAudioDeviceCallback(routeCallback, main) }
                .onSuccess { routeCallbackRegistered = true }
        }
        applyAudioRoute()
    }

    private fun handsFree(type: Int): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)

    private fun applyAudioRoute() {
        selectedRoute = AudioDeviceInfo.TYPE_UNKNOWN
        if (Build.VERSION.SDK_INT >= 31) {
            // BLUETOOTH_CONNECT can be denied independently of microphone permission, so every
            // route query/change is guarded. Unknown is intentionally proximity-safe (screen on).
            runCatching {
                val devices = audioManager.availableCommunicationDevices
                val preferred = if (speaker) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull { handsFree(it.type) }
                        ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                if (preferred != null && audioManager.setCommunicationDevice(preferred)) {
                    selectedRoute = preferred.type
                } else {
                    selectedRoute = audioManager.communicationDevice?.type ?: AudioDeviceInfo.TYPE_UNKNOWN
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speaker
            selectedRoute = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else {
                runCatching {
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { handsFree(it.type) }?.type
                }.getOrNull() ?: AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
        }
    }

    /** Sensor only during a connected earpiece call—never on speaker/wired/Bluetooth/movie-only use. */
    private fun updateProximity() {
        val use = CallState.current().phase == CallPhase.ACTIVE &&
            !speaker && selectedRoute == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        if (use && proximity?.isHeld != true) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "$packageName:voice-call-proximity").also { runCatching { it.acquire() } }
            }
        } else if (!use) releaseProximity()
    }

    private fun releaseProximity() {
        proximity?.let { if (it.isHeld) runCatching { it.release() } }; proximity = null
    }

    private fun startRingback() {
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55) }.getOrNull()
        ringTask = object : Runnable {
            override fun run() {
                if (ending || CallState.current().phase != CallPhase.OUTGOING) return
                tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 900)
                main.postDelayed(this, 4_000L)
            }
        }.also { main.post(it) }
    }

    private fun stopRingback() {
        ringTask?.let(main::removeCallbacks); ringTask = null
        runCatching { tone?.stopTone() }; runCatching { tone?.release() }; tone = null
    }

    private fun armTimeout(delay: Long, task: () -> Unit) {
        cancelTimeout(); timeoutTask = Runnable { if (!ending) task() }.also { main.postDelayed(it, delay) }
    }

    private fun cancelTimeout() { timeoutTask?.let(main::removeCallbacks); timeoutTask = null }

    private fun startCallForeground(microphone: Boolean) {
        val notification = CallNotify.ongoing(this, CallState.current())
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (microphone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTE, notification, type)
        } else if (Build.VERSION.SDK_INT >= 29 && microphone) {
            startForeground(NOTE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTE, notification)
    }

    private fun updateNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTE, CallNotify.ongoing(this, CallState.current()))
    }

    private fun endLocal(reason: String, notifyPeer: Boolean) {
        if (ending) return
        ending = true; cancelTimeout(); stopRingback(); releaseProximity(); clearSpeechDucking()
        if (notifyPeer && callId.isNotBlank()) {
            val action = if (outgoing && startedAt == 0L) "cancel" else "hangup"
            CallSignaling.send(this, chatId, peerCode, callId, action)
        }
        val ended = System.currentTimeMillis()
        val duration = if (startedAt > 0L) ((ended - startedAt) / 1000L).toInt().coerceAtLeast(1) else 0
        val outcome = if (reason == "failed") "failed" else if (duration > 0) "ended" else reason
        if (callId.isNotBlank()) {
            // Never freeze the launch-time name into a newly completed record.
            val recordName = Friends.currentName(this, peerCode, peerName)
            CallStore.add(this, CallRecord(callId, recordName, peerCode, chatId, outgoing,
                outcome, startedAt, ended, duration))
            if (outgoing && inviteSent) sendSummary(duration, outcome)
        }
        CallState.mutate { it.copy(phase = CallPhase.ENDED, status = endLabel(outcome), startedAt = startedAt) }
        CallNotify.clearAll(this); PendingCallStore.clear(this)
        signalReg?.remove(); signalReg = null
        rtc.execute { releaseRtc() }
        if (routeCallbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(routeCallback) }
            routeCallbackRegistered = false
        }
        if (Build.VERSION.SDK_INT >= 31) runCatching { audioManager.clearCommunicationDevice() }
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = false }
        audioManager.mode = AudioManager.MODE_NORMAL
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        main.postDelayed({ if (CallState.current().callId == callId) CallState.clear() }, 1_800L)
    }

    private fun sendSummary(duration: Int, outcome: String) {
        val text = when {
            duration > 0 -> "Voice call ended"
            outcome == "declined" -> "Voice call declined"
            outcome == "busy" -> "Voice call · Busy"
            outcome == "cancelled" -> "Voice call cancelled"
            else -> "Voice call · No answer"
        }
        // Dedicated system type/from: UI is centered without either member's ID/DP and
        // ordinary composer text can never masquerade as this call-history card.
        FirebaseChat.send(this, chatId, ChatMsg(from = "Smart Party Plus", text = text,
            ts = System.currentTimeMillis(), type = "call", dur = duration))
    }

    private fun endLabel(outcome: String): String = when (outcome) {
        "declined" -> "Call declined"
        "busy" -> "Dost doosri call par hai"
        "no_answer", "unavailable" -> "No answer"
        "failed" -> "Connection failed"
        "cancelled" -> "Call cancelled"
        else -> "Call ended"
    }

    private fun releaseRtc() {
        queuedCandidates.clear()
        runCatching { remoteTrack?.removeSink(remoteSpeechSink) }; remoteTrack = null
        runCatching { peerConnection?.close() }; runCatching { peerConnection?.dispose() }; peerConnection = null
        runCatching { localTrack?.dispose() }; localTrack = null
        runCatching { source?.dispose() }; source = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { audioModule?.release() }; audioModule = null
    }

    override fun onDestroy() {
        running = false
        if (!ending && callId.isNotBlank()) endLocal("ended", true)
        signalReg?.remove(); releaseProximity(); stopRingback(); clearSpeechDucking(); rtc.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
