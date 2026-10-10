#!/usr/bin/env python3
"""Release guard for the Original-derived native Room sleep timer."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sleep = (ROOT / "app/src/main/java/app/party/wpnative/PartySleepTimer.kt").read_text(encoding="utf-8")
inbox = (ROOT / "app/src/main/java/app/party/wpnative/InboxActivity.kt").read_text(encoding="utf-8")
room = (ROOT / "app/src/main/java/app/party/wpnative/PartyRoomActivity.kt").read_text(encoding="utf-8")
workflow = (ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

# Requested menu placement and exact choices.
assert inbox.index('"📌  Pinned chats"') < inbox.index("PartySleepTimer.menuLabel(this)")
assert inbox.index("PartySleepTimer.menuLabel(this)") < inbox.index('"🗑️  Chat clear"')
assert "val choicesMinutes = listOf(20, 40, 60)" in sleep
assert 'timerButton("$minutes min"' in inbox
assert "PartySleepTimer.set(this, minutes)" in inbox
assert '"Pehle Party Room join karo"' in inbox

# Wall-clock deadline, one-shot backstop, Activity catch-up and explicit cancellation.
assert "System.currentTimeMillis() + minutes * 60_000L" in sleep
assert "main.postDelayed(fire, delay)" in sleep
assert "PartySleepTimer.arm(this)" in inbox
assert "PartySleepTimer.arm(this)" in room
assert "PartySleepTimer.cancel(applicationContext)" in room
assert "putBoolean(KEY_PENDING, true)" in sleep
assert "PartyRoomRoute.fireSleepTimer()" in sleep
assert "PartySleepTimer.retryPending(this)" in room

# Expiry reuses the approved Room-wide user pause path: local MPV pause + retained tower command.
start = room.index("internal fun onSleepTimerExpired()")
end = room.index("/** Lock-screen previous/next", start)
expiry = room[start:end]
assert "userPausePlayback()" in expiry
assert "mpvVideo?.stop()" not in expiry
pause_start = room.index("private fun userPausePlayback()")
pause_end = room.index("internal fun onSleepTimerExpired()", pause_start)
pause = room[pause_start:pause_end]
assert 'mpvVideo?.pause()' in pause
assert 'PartyTower.publishPlaybackCommand("pause", time = time, user = true' in pause
assert "publishPlaybackSnapshot(media, time, false" in pause
assert '"⌛ Sleep Timer pura — Room ka song ruk gaya"' in expiry

assert "python3 tests/verify_sleep_timer.py" in workflow
print("Original-derived 20/40/60 Room sleep timer policy OK")
