# Smart Party Plus v1.0.1

A focused first-page rendering fix built from the same production source and signed with the same permanent Smart Party Plus release key.

## Fixed

- The selected first-page theme background now remains fully opaque while typing in the Name or Room field.
- Cursor blinking, input focus changes, keyboard opening/closing and IME-driven resizing no longer expose the grey Android window background.
- The same reusable page renderer is now stable across repeated redraws in the Room as well.

## Preserved

- All v1.0.0 behavior, Smart Party Plus branding, approved launcher icon and documented 92.5% Original-source polish are unchanged.
- Exact ACT7/Synkplay Android v0.23.0 native MPV/FFmpeg engine remains pinned and verified.
- Package ID remains `app.party.wpnative`.

## Signing and updates

This APK is signed with the same permanent Smart Party Plus release certificate as v1.0.0, so it installs directly over v1.0.0 without clearing app data:

- SHA-256: `76:C2:D3:D3:A3:54:47:B1:5D:CF:83:50:F0:9F:3A:CF:D4:7D:61:8A:42:7D:25:DA:A6:4D:4F:CF:ED:CE:8C:2F`
- SHA-1: `DF:85:28:A0:C6:6D:84:8A:C2:3D:4A:B6:35:46:5C:B0:53:8B:B5:D2`

The private signing backup is intentionally not part of this repository or release.
