# Smart Party Plus v1.0.7

## Current names in background message popups

- Background DM notifications now use the sender name carried by the new live message instead of the display name captured when the long-lived service first attached.
- The background service also keeps a live profile-directory listener for every stable Friend Code, so later name changes update its local identity map even while Inbox and Chat are closed.
- Stable chat identity suppresses notifications for the DM already open during a simultaneous rename.

## Current names throughout Call History

- Existing and newly created Call History rows resolve their display name through the stable Friend Code rather than treating the saved call-time name as permanent.
- The Calls screen listens to live profile changes. If a friend changes their name now or in the future, prior history rows refresh to the new name without deleting call records.
- Every missed, declined, cancelled, failed, outgoing and completed call resolves the latest name again when its record is written.
- Authenticated incoming-call invites carry the caller's current send-time name, avoiding a stale-listener race for new records and incoming-call UI.
- Calling back from History uses the refreshed current name.

## Preserved release identity and behavior

- App: **Smart Party Plus** (`app.party.wpnative`)
- Version: **1.0.7** (`versionCode 11`)
- One launcher only
- v1.0.6 Room membership rows and exact Smart Music message/join/leave tunes remain unchanged
- Stable DPs, typing identity, bubble colors, private 1-to-1 voice calls and all approved media/sync behavior remain preserved
- Exact ACT7/Synkplay Android v0.23.0 MPV engine remains pinned
- Final APK remains signed by the permanent Smart Party Plus update certificate
