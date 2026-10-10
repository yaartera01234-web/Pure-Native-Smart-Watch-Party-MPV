# Smart Party Plus v1.0.10

## Exact approved Smart Party + wordmark

- The approved focused `Smart Party +` sample is now the runtime asset itself, not a recreation.
- Sora 850 letters are fixed vector outlines with the approved white → pink → violet → cyan gradient.
- The cyan/violet/pink `+`, dark symbol, cyan edge, dashed halo, highlight and glow are baked into the same 240×48 lock-up.
- Android displays the byte-identical 960×192 lossless render through a fixed-ratio `ImageView`; device fonts, text metrics and selected themes cannot alter it.
- The existing 44dp animated orbit logo, compact page footprint and all seven digital feature cells remain preserved.

## Original-derived Room Sleep Timer

- Chat's upper `☰` menu now includes **⌛ Sleep Timer** directly under **Pinned chats**.
- Requested choices are **20 minutes**, **40 minutes** and **60 minutes**.
- The menu shows live minutes remaining; an active timer can be reset or cancelled.
- At expiry, local MPV pauses immediately and the normal retained user-pause command is published so the Room song stops for every active member at the same position.
- The timer uses a wall-clock deadline plus a one-shot callback, catches up after Activity recreation, and retries the Room pause when the selected Party Tower reconnects—matching the Original Smart Music behavior.
- Explicitly leaving the Party cancels the timer so a future Room cannot be stopped accidentally.

## Preserved identity and behavior

- App/package/launcher identity remains **Smart Party Plus** (`app.party.wpnative`).
- Permanent update certificate and exact ACT7/Synkplay Android v0.23.0 MPV engine remain unchanged.
- Lobby Neon remains the new-user default while existing users retain their saved theme.
- All accepted Room, sync, media, chat, call, DP, typing, rename, notification and digital-matrix behavior remains preserved.

## Release identity

- Version: **1.0.10** (`versionCode 14`)
