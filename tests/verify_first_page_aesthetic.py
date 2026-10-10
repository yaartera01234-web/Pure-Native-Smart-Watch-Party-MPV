#!/usr/bin/env python3
"""Release guard for the approved compact Smart Party + first-page design."""
from pathlib import Path
import hashlib
import struct

ROOT = Path(__file__).resolve().parents[1]
main = (ROOT / "app/src/main/java/app/party/wpnative/MainActivity.kt").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
themes = (ROOT / "app/src/main/java/app/party/wpnative/WpTheme.kt").read_text(encoding="utf-8")
room = (ROOT / "app/src/main/java/app/party/wpnative/PartyRoomActivity.kt").read_text(encoding="utf-8")

# First-page visual brand changes only; launcher identity remains Smart Party Plus.
assert 'contentDescription = "Smart Party Plus"' in main
assert 'text = "WATCH  •  LISTEN  •  TOGETHER"' in main
assert '<string name="app_name">Smart Party Plus</string>' in strings
assert 'android:label="@string/app_name"' in manifest

# The approved focused sample is the runtime asset itself—not a recreated font/TextView.
wordmark = ROOT / "app/src/main/res/drawable-nodpi/smart_party_wordmark.png"
wordmark_bytes = wordmark.read_bytes()
assert hashlib.sha256(wordmark_bytes).hexdigest() == \
    "f11906c57448b40d7ea2f5e24cd76cda1110429467502a7aee5f8c8e9c124eb1"
assert wordmark_bytes[:8] == b"\x89PNG\r\n\x1a\n"
assert struct.unpack(">II", wordmark_bytes[16:24]) == (960, 192)
assert 'setImageResource(R.drawable.smart_party_wordmark)' in main
assert 'scaleType = ImageView.ScaleType.FIT_CENTER' in main
assert 'titleView' not in main and 'plusView' not in main and 'GradientText' not in main
assert 'brandMetaView.setTextColor(hex("#6c6b96"))' in main

# Approved native dimensions mirror the compact proposal and the 240×48 vector viewBox.
assert 'joinCard.addView(logo, lp(dp(44), dp(44))' in main
assert 'joinCard.addView(brandImage, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))' in main
assert 'col.addView(avatarFrame, lp(dp(84), dp(84)))' in main
assert 'joinCard.addView(nameInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))' in main
assert 'joinCard.addView(roomInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))' in main
assert 'joinCard.addView(joinBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))' in main

# Digital footer is fixed to two short rows (4 + 3), never a wrapping/tall chip flow.
assert 'PARTY SYSTEMS  //  DIGITAL MATRIX' in main
assert 'listOf("▶ YOUTUBE", "♫ MP4/MP3", "☷ PLAYLIST", "◎ DP")' in main
assert 'listOf("↩ SWIPE REPLY", "⌁ LIVE SYNC", "⌂ ROOMS")' in main
assert main.count('lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(27))') == 2
assert 'chipFlow(' not in main
assert 'class FlowLayout' not in main
# Night Purple must use the sample's dark translucent cells, never forced opaque-white t.chip.
assert 'intArrayOf(hex("#eb131231"), hex("#eb050c1d"))' in main
assert 'withAlpha(t.chip, 235)' not in main
assert 'chip.setTextColor(hex("#e8e5fa"))' in main

# Lobby Neon is only the missing-preference default; saved theme values still win.
assert 'const val DEFAULT_INDEX = 0' in themes
assert '"neon", "Lobby Neon (default)"' in themes
assert '"purple", "Night Purple"' in themes
assert 'prefs.getInt("theme", WpThemes.DEFAULT_INDEX)' in main
assert 'prefs.getInt("theme", WpThemes.DEFAULT_INDEX)' in room
assert 'prefs.getInt("theme", 1)' not in main
assert 'prefs.getInt("theme", 1)' not in room

# Animated logo uses independent paint objects, preventing redraw-state leakage.
assert 'private val barPaint' in main
assert 'private val ringPaint' in main
assert 'barPaint.alpha = 255' in main
assert 'barPaint.style = Paint.Style.FILL' in main
assert 'barPaint.shader = null' in main

print("Compact aesthetic Smart Party + first-page policy OK")
