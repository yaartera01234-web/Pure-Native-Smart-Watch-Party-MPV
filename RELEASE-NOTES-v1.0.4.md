# Smart Party Plus v1.0.4

A focused Android launcher correction, signed with the same permanent Smart Party Plus release key.

## Fixed

- Android now exposes exactly one launcher app: **Smart Party Plus**.
- The internal Inbox is no longer exported as a second launcher named **Smart Party Plus Chat**.
- Inbox, messages, calls and notification navigation remain available normally inside the main app.
- CI now enforces a one-launcher manifest policy so a second app-drawer icon cannot be reintroduced accidentally.

## Preserved

- Stable Party bubble colors, compact Room header, first-page background fix and all previously accepted behavior remain unchanged.
- Exact ACT7/Synkplay Android v0.23.0 native MPV/FFmpeg engine remains pinned and verified.
- Package ID remains `app.party.wpnative`; this remains one application, not two installed packages.

## Signing and updates

This APK uses the same permanent release certificate as all previous v1.0.x releases, so it installs directly over the existing version without clearing app data.

- Certificate SHA-256: `76:C2:D3:D3:A3:54:47:B1:5D:CF:83:50:F0:9F:3A:CF:D4:7D:61:8A:42:7D:25:DA:A6:4D:4F:CF:ED:CE:8C:2F`

Private signing material is intentionally excluded from the repository and release.
