# Smart Party Plus v1.0.0

The first production-branded pure-native Android release.

## Highlights

- Pure-native synchronized MP4, MKV, MP3 and YouTube watch parties.
- Exact ACT7/Synkplay Android v0.23.0 native MPV engine.
- Selected encrypted Party Tower transport, retained media/playlist state and slowest-valid-member sync.
- Friend Code DMs with genuine read state, numeric unread counts, photos, voice, keyboard GIFs, persistent reactions and exact quoted-message navigation.
- Private 1-to-1 two-way WebRTC voice calls with earpiece/speaker/Bluetooth routing, proximity behavior and draggable minimized call bar.
- Adaptive call speech gate: brief noise is ignored; confirmed conversation smoothly ducks movie audio to 45% and restores it after silence without broadcasting playback commands.
- Original-source visual polish with a documented **92.5% static source-token/geometry parity**. See `POLISH-AUDIT.md` for the calculation and intentional native differences.
- New **Smart Party Plus** adaptive launcher icon, round icon, Android themed icon, splash branding and notification mark.

## Signing and updates

This APK is signed with the permanent Smart Party Plus release certificate:

- SHA-256: `76:C2:D3:D3:A3:54:47:B1:5D:CF:83:50:F0:9F:3A:CF:D4:7D:61:8A:42:7D:25:DA:A6:4D:4F:CF:ED:CE:8C:2F`
- SHA-1: `DF:85:28:A0:C6:6D:84:8A:C2:3D:4A:B6:35:46:5C:B0:53:8B:B5:D2`

Future versions signed with the same private key, the same application ID and a higher versionCode can install directly over v1.0.0 without clearing app data.

The private signing backup is intentionally **not** part of this repository or release.
