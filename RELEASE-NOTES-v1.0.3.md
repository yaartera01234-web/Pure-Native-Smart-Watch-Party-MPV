# Smart Party Plus v1.0.3

A focused rendering-state correction, signed with the same permanent Smart Party Plus release key.

## Fixed

- Party message bubbles retain their selected original colors after sending instead of becoming fully dim on a later redraw.
- Bubble fills now reset opacity independently from their intentionally translucent border and top gloss.
- Party Room aurora colors no longer inherit the low opacity of the dot texture on later frames.
- The MP3 player artwork and equalizer gradients now remain stable across animated redraws.

## Preserved

- The compact **Smart Party +** Room header, first-page typing/background fix and all previously accepted behavior remain unchanged.
- Exact ACT7/Synkplay Android v0.23.0 native MPV/FFmpeg engine remains pinned and verified.
- Package ID remains `app.party.wpnative`.

## Signing and updates

This APK uses the same permanent release certificate as all v1.0.x releases, so it installs directly over the previous version without clearing app data.

- Certificate SHA-256: `76:C2:D3:D3:A3:54:47:B1:5D:CF:83:50:F0:9F:3A:CF:D4:7D:61:8A:42:7D:25:DA:A6:4D:4F:CF:ED:CE:8C:2F`

Private signing material is intentionally excluded from the repository and release.
