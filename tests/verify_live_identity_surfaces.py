#!/usr/bin/env python3
"""Static release guard for stable-code display-name propagation outside Chat."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


friends = text("app/src/main/java/app/party/wpnative/Friends.kt")
bg = text("app/src/main/java/app/party/wpnative/BgMsgService.kt")
calls = text("app/src/main/java/app/party/wpnative/CallsActivity.kt")
call_notify = text("app/src/main/java/app/party/wpnative/CallNotifications.kt")
call_service = text("app/src/main/java/app/party/wpnative/VoiceCallService.kt")
chat = text("app/src/main/java/app/party/wpnative/ChatActivity.kt")
wp_notify = text("app/src/main/java/app/party/wpnative/WpNotify.kt")
wp_messaging = text("app/src/main/java/app/party/wpnative/WpMessagingService.kt")

# One stable-code resolver is shared by call creation, persistence and rendering.
assert "fun currentName(ctx: Context, code: String" in friends
assert "nameForCode(ctx, code)" in friends

# Long-lived background closures receive live profile changes and do not freeze p.name.
assert "private val profileRegs" in bg
assert "FirebaseChat.listenFriendProfile(this, code)" in bg
assert "Friends.updateNameByCode(this, profile.code, profile.name)" in bg
assert "val popupName = m.from.trim().take(40)" in bg
assert "WpNotify.post(this, popupName, body, chatId, code)" in bg
assert 'signal.body.optString("name")' in bg
assert "IncomingCallController.receive(this, signal, callName)" in bg
assert "WpNotify.post(this, p.name" not in bg
assert "IncomingCallController.receive(this, signal, p.name)" not in bg

# Old and new history rows render through current stable-code identity and stay live.
assert "val displayName = Friends.currentName(this, record.peerCode, record.peerName)" in calls
assert "FirebaseChat.listenFriendProfile(this, code)" in calls
assert "Friends.updateNameByCode(this, profile.code, profile.name)" in calls
assert "reloadCalls()" in calls
assert "VoiceCallActivity.startOutgoing(this@CallsActivity, c.name" in calls
assert "CallItem(record.peerName" not in calls

# Every newly persisted call outcome resolves the latest name at write time.
assert "val recordName = Friends.currentName(this, peerCode, peerName)" in call_service
assert "CallRecord(callId, recordName, peerCode" in call_service
assert call_notify.count("Friends.currentName(") >= 5
assert "CallRecord(live.callId, recordName, live.peerCode" in call_notify
assert "CallRecord(pending.callId, recordName, pending.peerCode" in call_notify

# Rename races cannot notify a DM that is already open.
assert "@Volatile var chatId: String?" in wp_messaging
assert "WpActive.chatId = chatId" in chat
assert "WpActive.chatId == chatId" in wp_notify

print("Background popup + call-history live identity policy OK")
