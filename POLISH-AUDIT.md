# Smart Party Plus — final source-based polish audit

Audit date: 2026-10-10  
Original reference: `yaartera01234-web/watch-party` commit `e63f75a23b189a7db5171bce4575ce1ca98fa09b` (`party-final1.html`)  
Native implementation: this repository, `app/src/main/java/app/party/wpnative/`

## Honest similarity result

**Static source-token and geometry parity: 37 / 40 checkpoints = 92.5%.**

This is not presented as screenshot-level “100%”. It is a reproducible source audit: each checkpoint is a visible color, dimension, spacing, hierarchy or behavior token that can be checked in the Original CSS and native source. Native Android font rasterization, OEM emoji, elevation and the absence of browser `backdrop-filter` prevent an honest pixel-perfect claim across every handset.

| Area | Full matches | Audited | Evidence / remaining native difference |
|---|---:|---:|---|
| Join page and six themes | 8 | 8 | Original theme order, ordered gradients, glow positions, cards, inputs, picker coupling and 12 bubble palettes are centralized in `WpTheme.kt`; join geometry is in `MainActivity.kt`. |
| DM header, messages and composer | 12 | 13 | Header 12dp padding; 28dp back; 34dp DP plus ring; 36dp actions; 34dp message DPs; 13.5sp/1.45 text; 9×13dp bubbles; quote/reaction/time/read tokens match. Native composer retains a 46dp accessibility touch target instead of the web's 40dp visual target. |
| Inbox | 6 | 7 | 17dp gradient rows, unread edge, 49/45dp DP ring, presence dot, 13.5sp title, selected mini-bubble palette and numeric unread badge match. Browser blur/inset-shadow can only be approximated by native gradients/elevation. |
| Calls and bottom navigation | 5 | 5 | Calls now use Original 42dp DP, 13/11/10sp type and 36dp callback; nav uses 22dp glass shell, 17dp active pill, 24dp vectors, unread badge and 5.2s sheen. |
| Party chat, mini-player and theme coupling | 6 | 7 | Selected tower, Original message palette/geometry, theme coupling, retained timeline and mini-player-only theming match. Platform emoji/font rendering remains OEM-dependent; expanded/fullscreen player intentionally retains its approved native appearance. |
| **Total** | **37** | **40** | **92.5% source-token/geometry parity.** |

## Final polish changes in v1.0.0

- Production brand changed to **Smart Party Plus** across launcher, join, Room, MediaSession, friend-code sharing and call system events.
- Approved orbit/play/plus artwork added as a full-size adaptive icon, round icon, Android 13 themed icon, splash mark and monochrome notification mark.
- Essential icon artwork occupies roughly the central 63% of the master so it remains prominent without adaptive-mask clipping.
- DM typography/padding corrected to Original 13.5sp, 1.45 line height and 9×13dp bubble padding.
- Inbox rows corrected to Original gradient card, unread accent, avatar ring and selected own/other mini-bubble palette.
- Calls list corrected to Original avatar, text and call-button geometry.
- Room brand title auto-sizes on narrow phones so the longer production name does not collide with Tower and action controls.
- Application version set to `1.0.0` (`versionCode 4`).
- CI now emits a verified aligned unsigned input; the public APK is signed separately with the permanent private release key. No temporary CI key can accidentally become a future release identity.

## Deliberately unchanged

- Application ID remains `app.party.wpnative` for update/data continuity.
- Exact ACT7/Synkplay Android v0.23.0 engine remains pinned.
- Playback timeline remains visible.
- Only the mini-player follows themes; expanded inline and immersive fullscreen keep the approved appearance.
- No behavioral changes were made to DM/Party GIF, reactions, cleanup, calls, selected tower, sync, retained media or YouTube fallback logic.
