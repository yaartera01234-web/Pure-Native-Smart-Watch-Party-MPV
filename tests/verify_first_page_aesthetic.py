#!/usr/bin/env python3
"""Release guard for the approved compact Smart Party + first-page design."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
main = (ROOT / "app/src/main/java/app/party/wpnative/MainActivity.kt").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")

# First-page visual brand changes only; launcher identity remains Smart Party Plus.
assert 'text = "Smart Party"' in main
assert 'plusView = TextView(this).apply' in main
assert 'text = "+"' in main
assert 'contentDescription = "Smart Party Plus"' in main
assert 'text = "WATCH  •  LISTEN  •  TOGETHER"' in main
assert '<string name="app_name">Smart Party Plus</string>' in strings
assert 'android:label="@string/app_name"' in manifest

# Approved native dimensions mirror the compact 360x800 HTML proposal.
assert 'joinCard.addView(logo, lp(dp(44), dp(44))' in main
assert 'brandRow.addView(plusView, lp(dp(34), dp(34))' in main
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

# Animated logo uses independent paint objects, preventing redraw-state leakage.
assert 'private val barPaint' in main
assert 'private val ringPaint' in main
assert 'barPaint.alpha = 255' in main
assert 'barPaint.style = Paint.Style.FILL' in main
assert 'barPaint.shader = null' in main

print("Compact aesthetic Smart Party + first-page policy OK")
