# Watch Party — Pure Native Android

WebView ke baghair Kotlin/Android Views implementation.

Ab shamil hai:

- Native first/join page, Messages/Calls aur bottom navigation
- Pixel-matched native Party Lobby v48
- Lobby ke **Enter Party** par first-page ke saved name, room aur selected tower se actual Room join
- Native Party Room UI aur Rave-size full-width `16:9` MPV player
- EMQX, HiveMQ aur tyckr ke exact WSS endpoints par encrypted native MQTT Room transport
- Live Room members, reconnect-safe presence aur explicit confirmed Leave
- Room text/reply/reactions/photo/voice; media tower par AES-GCM encrypted retained blobs
- Explicit Leave par us phone ki Room chat/media cache saaf; Room khaali hone ke baad bhi shared playback/media state retained rehti hai
- `WP1-XXXX-XXXX` Friend Codes: own-code Copy/Share, lookup, requests, Accept/Reject aur stable code-backed DM chats
- Playlist chat se independent retained rehti hai, YouTube thumbnail/title aur custom rename/remove ke sath
- Party chat quick emoji, swipe reply, reaction chips aur flying emoji
- Keyboard-safe Room typing mode: keyboard khulte hi composer aur message thread visible rehte hain

Room chat maximum 120 messages rakhti hai. Disconnect/glitch ko Leave nahi maana jata. Native MPV player Room playback ko MQTT/WP4 sync ke sath chalata hai.

Friend Code directory aur Message Requests existing Firestore DM `chats/.../msgs` permission ke andar compatible envelopes use karte hain, is liye nayi collection permission ki zarurat nahi. Updated `firestore.rules` presence/token shape bhi cover karti hai. Firebase Storage istemal nahi hoti.

## MPV023 engine

Final APK Smart Music Watch Party ACT7 ki verified pipeline se exact Synkplay Android v0.23.0 arm64 native bundle use karta hai: MPV `v0.41.0-252-gc401ef9c3`, FFmpeg `N-123143-g0540b42657`, aur matching source-rebuilt JNI adapter. Provenance aur pinned hashes `tests/mpv023/` mein hain. Minimum Android 8 (API 26).

## Build

GitHub Actions workflow `.github/workflows/build-apk.yml` bundled ZIP ko extract karta hai, base debug APK banata hai, verified MPV023 engine substitute karta hai, phir APK ko align/sign/verify karta hai.
