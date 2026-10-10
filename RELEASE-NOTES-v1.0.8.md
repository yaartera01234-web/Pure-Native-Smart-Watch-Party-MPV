# Smart Party Plus v1.0.8

## Approved compact first-page redesign

- The first/join page now presents the approved aesthetic **Smart Party +** visual lock-up while the Android launcher/app identity remains **Smart Party Plus**.
- The existing 44dp animated equalizer logo now sits inside a subtle dual-color orbit with an independent glow dot.
- **Smart Party** uses the selected theme's native gradient and the `+` is a separate 34dp digital badge.
- A compact `WATCH • LISTEN • TOGETHER` detail line completes the brand without increasing the previous title footprint.

## Party Lobby-style digital feature matrix

- The old wrapping YouTube/Reply/etc. pills are replaced by the approved digital panel.
- Seven features remain visible: YouTube, MP4/MP3, Playlist, DP, Swipe Reply, Live Sync and Rooms.
- The matrix is fixed to exactly two 27dp rows (`4 + 3`), preventing a long first page on normal phone widths.
- All six first-page themes recolor the title, plus badge, panel border, chips and matrix line natively.
- Independent logo paint objects explicitly reset fill, alpha and shader state, preventing redraw-state leakage.

## Preserved behavior

- Name, Room, avatar, Photo, Cartoon, Tower, Theme/Bubble picker and Enter actions are unchanged.
- Keyboard-safe scrolling remains available on small screens.
- v1.0.7 live name propagation remains unchanged across DM, background popup, call screens and old/new Call History.
- v1.0.6 Room membership rows and exact Smart Music message/join/leave tunes remain unchanged.
- One launcher, permanent update certificate, exact ACT7/Synkplay Android v0.23.0 MPV engine and all approved chat/call/media/sync behavior remain preserved.

## Release identity

- App: **Smart Party Plus** (`app.party.wpnative`)
- First-page visual title: **Smart Party +**
- Version: **1.0.8** (`versionCode 12`)
