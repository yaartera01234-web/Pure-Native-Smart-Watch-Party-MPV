# Smart Party Plus v1.0.5

A focused live-identity and typing-avatar update, signed with the same permanent Smart Party Plus release key.

## Fixed

- A Friend Code contact’s changed display name now replaces the old name in Inbox and an already-open DM without changing the conversation or losing history.
- Existing peer message rows use the latest code-backed profile name instead of retaining a stale sender label.
- Reusing an active Party Room session now republishes the newly selected name instead of keeping the previous in-memory name.
- Existing Room message rows update to a member’s latest name and color through the stable Room member ID.
- Typing indicators now show the member’s actual selected DP in both DM and Party Room; multiple Room typers use the first active member’s DP while listing all active names.
- A renamed account’s old name-keyed presence is marked offline after the new profile publishes.

## Preserved

- Single launcher icon, stable Party bubble colors, compact Room header, first-page background fix and all previously accepted behavior remain unchanged.
- Exact ACT7/Synkplay Android v0.23.0 native MPV/FFmpeg engine remains pinned and verified.
- Package ID remains `app.party.wpnative`.

## Signing and updates

This APK uses the same permanent release certificate as all previous v1.0.x releases, so it installs directly over the existing version without clearing app data.

- Certificate SHA-256: `76:C2:D3:D3:A3:54:47:B1:5D:CF:83:50:F0:9F:3A:CF:D4:7D:61:8A:42:7D:25:DA:A6:4D:4F:CF:ED:CE:8C:2F`

Private signing material is intentionally excluded from the repository and release.
