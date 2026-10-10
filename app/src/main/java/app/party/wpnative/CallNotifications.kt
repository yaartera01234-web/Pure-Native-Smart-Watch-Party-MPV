package app.party.wpnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

object CallNotify {
    const val INCOMING_ID = 4810
    const val ONGOING_ID = 4811
    private const val CH_INCOMING = "wp_calls_incoming_v1"
    private const val CH_ONGOING = "wp_calls_ongoing_v1"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_INCOMING) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming voice calls",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming private voice calls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 650, 350, 650, 900)
                val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            })
        }
        if (nm.getNotificationChannel(CH_ONGOING) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing voice call",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for an active private voice call"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            })
        }
    }

    fun showIncoming(ctx: Context, pending: PendingCall) {
        channels(ctx)
        val answerIntent = VoiceCallActivity.incomingIntent(ctx, pending, answer = true)
        val openIntent = VoiceCallActivity.incomingIntent(ctx, pending, answer = false)
        val declineIntent = Intent(ctx, CallActionReceiver::class.java)
            .setAction(CallActionReceiver.DECLINE).putExtra("callId", pending.callId)
        val answer = PendingIntent.getActivity(ctx, 4812, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutable())
        val open = PendingIntent.getActivity(ctx, 4813, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutable())
        val decline = PendingIntent.getBroadcast(ctx, 4814, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutable())
        val person = Person.Builder().setName(pending.peerName).setImportant(true).build()
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_INCOMING)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)
        builder.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(pending.peerName)
            .setContentText("Incoming private voice call")
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
        } else {
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "Decline", decline).build())
            builder.addAction(Notification.Action.Builder(android.R.drawable.sym_action_call,
                "Answer", answer).build())
        }
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(INCOMING_ID, builder.build())
    }

    fun ongoing(ctx: Context, state: CallSnapshot): Notification {
        channels(ctx)
        fun action(name: String, request: Int) = PendingIntent.getBroadcast(ctx, request,
            Intent(ctx, CallActionReceiver::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable())
        val open = PendingIntent.getActivity(ctx, 4820,
            VoiceCallActivity.openIntent(ctx), PendingIntent.FLAG_UPDATE_CURRENT or immutable())
        val hangup = action(CallActionReceiver.END, 4821)
        val mute = action(CallActionReceiver.MUTE, 4822)
        val speaker = action(CallActionReceiver.SPEAKER, 4823)
        val person = Person.Builder().setName(state.peerName.ifBlank { "Dost" }).build()
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_ONGOING)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)
        builder.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(state.peerName.ifBlank { "Private voice call" })
            .setContentText(when (state.phase) {
                CallPhase.OUTGOING -> "Calling…"
                CallPhase.CONNECTING -> "Connecting…"
                CallPhase.RECONNECTING -> "Reconnecting…"
                CallPhase.ACTIVE -> elapsed(state.startedAt)
                else -> "Private voice call"
            })
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_btn_speak_now,
                if (state.muted) "Unmute" else "Mute", mute).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_lock_silent_mode_off,
                if (state.speaker) "Earpiece" else "Speaker", speaker).build())
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(Notification.CallStyle.forOngoingCall(person, hangup))
        } else {
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "End", hangup).build())
        }
        return builder.build()
    }

    fun clearIncoming(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(INCOMING_ID)
    }

    fun clearAll(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(INCOMING_ID); nm.cancel(ONGOING_ID)
    }

    private fun elapsed(start: Long): String {
        val sec = if (start > 0) ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L) else 0L
        return "%02d:%02d · Private voice call".format(sec / 60L, sec % 60L)
    }

    private fun immutable() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
}

/** Owns an unanswered invite before the microphone/WebRTC service starts. */
object IncomingCallController {
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    fun receive(ctx: Context, signal: CallWireSignal, peerName: String) {
        if (signal.action != "invite") return
        val app = ctx.applicationContext
        val currentPending = PendingCallStore.get(app)
        // The same encrypted invite can be observed again while its delete is in flight.
        // It is idempotent—not a second call, and must never receive a false busy reply.
        if (currentPending?.callId == signal.callId) {
            present(app, currentPending)
            return
        }
        if (CallState.active() || currentPending != null) {
            CallSignaling.send(app, signal.chatId, signal.fromCode, signal.callId, "busy")
            return
        }
        val currentName = Friends.currentName(app, signal.fromCode, peerName)
        val pending = PendingCall(signal.callId, signal.chatId, currentName, signal.fromCode,
            System.currentTimeMillis())
        PendingCallStore.save(app, pending)
        present(app, pending)
    }

    /** Rebuild ringing/timeout after a normal process recreation. */
    fun restore(ctx: Context) {
        PendingCallStore.get(ctx)?.let { present(ctx.applicationContext, it) }
    }

    private fun present(app: Context, pending: PendingCall) {
        val latestName = Friends.currentName(app, pending.peerCode, pending.peerName)
        val shown = if (latestName == pending.peerName) pending else pending.copy(peerName = latestName)
        if (shown !== pending) PendingCallStore.save(app, shown)
        CallState.update(CallSnapshot(shown.callId, shown.chatId, shown.peerName, shown.peerCode,
            outgoing = false, phase = CallPhase.INCOMING, status = "Incoming voice call"))
        val remaining = (30_000L - (System.currentTimeMillis() - shown.at)).coerceAtLeast(0L)
        if (remaining > 0L) CallNotify.showIncoming(app, shown)
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable {
            val live = PendingCallStore.get(app)
            if (live?.callId != shown.callId) return@Runnable
            CallSignaling.send(app, live.chatId, live.peerCode, live.callId, "unavailable")
            val recordName = Friends.currentName(app, live.peerCode, live.peerName)
            CallStore.add(app, CallRecord(live.callId, recordName, live.peerCode,
                live.chatId, false, "missed", 0L, System.currentTimeMillis(), 0))
            PendingCallStore.clear(app); CallNotify.clearIncoming(app)
            finishPendingState(live.callId, "Missed call")
            timeout = null
        }.also { handler.postDelayed(it, remaining) }
    }

    fun accepted(ctx: Context, callId: String) {
        timeout?.let(handler::removeCallbacks); timeout = null
        if (PendingCallStore.get(ctx)?.callId == callId) PendingCallStore.clear(ctx)
        CallNotify.clearIncoming(ctx)
    }

    fun decline(ctx: Context, callId: String) {
        val pending = PendingCallStore.get(ctx) ?: return
        if (callId.isNotBlank() && pending.callId != callId) return
        timeout?.let(handler::removeCallbacks); timeout = null
        CallSignaling.send(ctx, pending.chatId, pending.peerCode, pending.callId, "decline")
        val recordName = Friends.currentName(ctx, pending.peerCode, pending.peerName)
        CallStore.add(ctx, CallRecord(pending.callId, recordName, pending.peerCode,
            pending.chatId, false, "declined", 0L, System.currentTimeMillis(), 0))
        PendingCallStore.clear(ctx); CallNotify.clearIncoming(ctx)
        finishPendingState(pending.callId, "Call declined")
    }

    /** Caller hung up while this phone was still ringing. */
    fun cancelled(ctx: Context, callId: String): Boolean {
        val pending = PendingCallStore.get(ctx) ?: return false
        if (pending.callId != callId) return false
        timeout?.let(handler::removeCallbacks); timeout = null
        val recordName = Friends.currentName(ctx, pending.peerCode, pending.peerName)
        CallStore.add(ctx, CallRecord(pending.callId, recordName, pending.peerCode,
            pending.chatId, false, "missed", 0L, System.currentTimeMillis(), 0))
        PendingCallStore.clear(ctx); CallNotify.clearIncoming(ctx)
        finishPendingState(pending.callId, "Call cancelled")
        return true
    }

    private fun finishPendingState(callId: String, label: String) {
        val current = CallState.current()
        if (current.callId == callId) {
            CallState.update(current.copy(phase = CallPhase.ENDED, status = label))
            handler.postDelayed({ if (CallState.current().callId == callId) CallState.clear() }, 1_800L)
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val DECLINE = "app.party.wpnative.call.DECLINE"
        const val END = "app.party.wpnative.call.END"
        const val MUTE = "app.party.wpnative.call.MUTE"
        const val SPEAKER = "app.party.wpnative.call.SPEAKER"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        when (intent?.action) {
            DECLINE -> IncomingCallController.decline(ctx, intent.getStringExtra("callId").orEmpty())
            END -> VoiceCallService.command(ctx, VoiceCallService.ACTION_END)
            MUTE -> VoiceCallService.command(ctx, VoiceCallService.ACTION_MUTE)
            SPEAKER -> VoiceCallService.command(ctx, VoiceCallService.ACTION_SPEAKER)
        }
    }
}
