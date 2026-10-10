# Smart Party Plus — Pure Native Android

WebView ke baghair Kotlin/Android Views implementation.

Ab shamil hai:

- Native first/join page, Messages/Calls aur bottom navigation
- Compact aesthetic **Smart Party +** first-page lock-up with the approved fixed sample gradient, Lobby Neon new-user default, orbit equalizer and a dark two-row Party Lobby-style digital feature matrix
- Pixel-matched native Party Lobby v48
- Lobby ke **Enter Party** par first-page ke saved name, room aur selected tower se actual Room join
- Native Party Room UI aur Rave-size full-width `16:9` MPV player
- EMQX, HiveMQ aur tyckr ke exact WSS endpoints par encrypted native MQTT Room transport
- Live Room members, reconnect-safe presence aur explicit confirmed Leave
- Aesthetic `Ahmed Joined` / `Ahmed Left` Room system rows plus fullscreen membership activity cards
- Smart Music source-derived Room tones: exact sine/envelope sequence for remote join, leave and each incoming live message; retained history and broker echoes stay silent
- Room text/reply/reactions/photo/voice; media tower par AES-GCM encrypted retained blobs
- Explicit Leave par us phone ki Room chat/media cache saaf; Room khaali hone ke baad bhi shared playback/media state retained rehti hai
- `WP1-XXXX-XXXX` Friend Codes: own-code Copy/Share, lookup, requests, Accept/Reject aur stable code-backed DM chats
- Stable Friend Code identity propagation: background message popups and old/new Call History rows always resolve the current display name, including future renames without clearing history
- Accepted Friend Code contacts ke darmiyan pure-native private **1-to-1 voice calls**: WebRTC/Opus, STUN+TURN, encrypted idempotent signaling, incoming/ongoing call notifications, mute, earpiece/speaker/wired/Bluetooth routing, reconnect, timer aur minimize/restore
- Conversation-aware movie audio: adaptive native speech gate 260ms sustained voice confirm karta hai; local ya remote member bolay to movie smoothly 45% tak duck hoti aur 900ms silence par full level restore karti hai—short background noise ignore hota aur playback/Party sync kabhi pause nahi hoti
- Earpiece par sirf connected state mein conditional proximity screen-off; ringing, connecting, reconnecting, speaker, wired aur Bluetooth par sensor hamesha off
- Latest-100 durable Calls history, result/direction/duration, history callback aur DM call-summary rows; video aur group calling jaan-boojh kar shamil nahi
- Native YouTube Search: Piped multi-instance primary, YouTube Data API fallback, recent/suggestions, thumbnails/duration
- Search result tap par item queue ke end mein add aur poore Room ke liye synchronized play; recent chips re-entrant click ke baghair safe search chalate hain
- Playlist chat se independent retained rehti hai, YouTube thumbnail/title aur custom rename/remove ke sath
- Native MediaSession lock-screen controls: previous/next queue song, play/pause aur ±10-second seek; har action Room mein sync hota hai
- Party chat quick emoji, swipe reply, reaction chips aur flying emoji
- Keyboard-safe Room typing mode: keyboard khulte hi composer aur message thread visible rehte hain

Room chat maximum 120 messages rakhti hai. Disconnect/glitch ko Leave nahi maana jata. Native MPV player Room playback ko MQTT/WP4 sync ke sath chalata hai.

Friend Code directory, Message Requests aur short-lived encrypted call signaling existing Firestore `chats/.../msgs` rule ke andar compatible envelopes use karte hain. Call packets reserved `_wp_call_*` chat documents mein rehte, visible DM history se alag hote aur consume/expiry par delete hote hain; is liye nayi collection/rule publication ki zarurat nahi. Updated `firestore.rules` presence/token shape bhi cover karti hai. Firebase Storage istemal nahi hoti.

Incoming call discovery normal app launches se chalu foreground-backed listener par depend karti hai. Android force-stop ya aggressive OEM process killing ke baad, trusted push-call backend ke baghair incoming-call delivery ki guarantee nahi ho sakti; app dobara kholne par listener restore hota hai.

## MPV023 engine

Final APK Smart Music Watch Party ACT7 ki verified pipeline se exact Synkplay Android v0.23.0 arm64 native bundle use karta hai: MPV `v0.41.0-252-gc401ef9c3`, FFmpeg `N-123143-g0540b42657`, aur matching source-rebuilt JNI adapter. Provenance aur pinned hashes `tests/mpv023/` mein hain. Minimum Android 8 (API 26).

## Build

GitHub Actions workflow `.github/workflows/build-apk.yml` bundled ZIP ko extract karta hai, base APK banata hai, verified MPV023 engine substitute karta hai aur aligned signing input + CI-check APK verify karta hai. Public release APK us aligned input se alag permanent private Smart Party Plus key ke saath sign/verify hoti hai; private key repository ya public Release mein kabhi nahi jati.
