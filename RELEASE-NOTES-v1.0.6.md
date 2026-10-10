# Smart Party Plus v1.0.6

## Room membership events

- Party Room now adds centered, non-user system rows such as **Ahmed Joined** and **Ahmed Left**.
- Join and explicit Leave also appear in the immersive fullscreen activity feed.
- Stable member IDs, wire event IDs and the Original implementation's 45-second semantic window suppress duplicate QoS echoes/repeated events.
- MQTT reconnects are no longer announced as a new Room entry.

## Exact Smart Music Room tunes

The authoritative Smart Music Watch Party page does not contain audio files. Its `party-final1.html` synthesizes all three sounds with Web Audio sine oscillators. This update reproduces those definitions natively:

- **Message:** 880 Hz for 130 ms, then 1318 Hz for 200 ms at +160 ms.
- **Join:** 523, 659 and 784 Hz for 120 ms each, then 1047 Hz for 300 ms.
- **Leave:** the same four-note sequence in reverse.
- Every note uses the source's exact 20 ms exponential attack from 0.0001 to 0.4, exponential release to 0.0001, and 50 ms oscillator tail.

As in the authoritative implementation, the message tune plays for each incoming friend's live Room message, not the sender's own local message. Restored retained history and duplicate transient/retained broker delivery remain silent. Tones do not request audio focus, pause playback, or publish Party commands.

## Preserved release identity

- App: **Smart Party Plus** (`app.party.wpnative`)
- Version: **1.0.6** (`versionCode 10`)
- One launcher only
- Exact ACT7/Synkplay Android v0.23.0 MPV engine remains pinned
- Final APK remains signed by the permanent Smart Party Plus update certificate
