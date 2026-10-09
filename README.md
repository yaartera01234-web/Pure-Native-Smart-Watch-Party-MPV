# Watch Party — Pure Native Android

WebView ke baghair Kotlin/Android Views implementation.

Ab shamil hai:

- Native first/join page, Messages/Calls aur bottom navigation
- Pixel-matched native Party Lobby v48
- Lobby ke **Enter Party** par first-page ke saved name, room aur selected tower se actual Room join
- Native Party Room UI aur Rave-size full-width `16:9` player placeholder
- EMQX, HiveMQ aur tyckr ke exact WSS endpoints par encrypted native MQTT Room transport
- Live Room members, reconnect-safe presence aur explicit confirmed Leave
- Room text/reply/reactions/photo/voice; media tower par AES-GCM encrypted retained blobs
- Har member ke explicit Leave par us phone ki Room chat/media clear; aakhri member ke Leave par server chat/media bhi clear
- Playlist chat se independent retained rehti hai, YouTube thumbnail/title aur custom rename/remove ke sath
- Party chat quick emoji, swipe reply, reaction chips aur flying emoji
- Keyboard-safe Room typing mode: keyboard khulte hi composer aur message thread visible rehte hain

Room chat maximum 120 messages rakhti hai. Disconnect/glitch ko Leave nahi maana jata. MPV playback, player controls aur synchronized playback final player batch mein reserved Party Room player ke andar add honge.

## Build

GitHub Actions workflow `.github/workflows/build-apk.yml` bundled ZIP ko extract karke debug APK build karta hai.
