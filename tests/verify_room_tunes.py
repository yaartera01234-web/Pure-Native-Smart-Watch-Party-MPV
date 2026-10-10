#!/usr/bin/env python3
"""Static release guard for the source-derived Party Room tones and live-only alerts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
tunes = (ROOT / "app/src/main/java/app/party/wpnative/RoomEventTunes.kt").read_text()
tower = (ROOT / "app/src/main/java/app/party/wpnative/PartyTower.kt").read_text()
room = (ROOT / "app/src/main/java/app/party/wpnative/PartyRoomActivity.kt").read_text()

# party-final1.html tonePlay/tuneMsg/tuneJoin/tuneLeave, transcribed literally.
required_tones = [
    "const val FLOOR = 0.0001",
    "const val PEAK = 0.4",
    "const val ATTACK = 0.02",
    "const val TAIL = 0.05",
    "Note(880.0, 880.0, 0.13, 0.0)",
    "Note(1318.0, 1318.0, 0.20, 0.16)",
    "Note(523.0, 523.0, 0.12, 0.0)",
    "Note(659.0, 659.0, 0.12, 0.12)",
    "Note(784.0, 784.0, 0.12, 0.24)",
    "Note(1047.0, 1047.0, 0.30, 0.36)",
    "Note(1047.0, 1047.0, 0.12, 0.0)",
    "Note(523.0, 523.0, 0.30, 0.36)",
]
for literal in required_tones:
    assert literal in tunes, f"Room tune drifted: missing {literal}"

# Restoration must paint history but never ring; either live MQTT topic can win once.
assert "handleMessageEnvelope(payload, live = !retained)" in tower
assert "liveMessageNotified.add(m.mid)" in tower
assert "if (notifyLive) it.onPartyLiveMessage(m)" in tower
assert "if (!own) roomTunes.message()" in room

# Join/leave must remain transient, deduplicated and rendered as system rows.
assert 'put("t", type)' in tower and 'put("mid", randomId())' in tower
assert "now - previous < 45_000L" in tower
assert "Row.SYSTEM" in room
assert 'if (joined) "Joined" else "Left"' in room

print("Party Room source-derived tunes/live-event policy OK")
