# Spotify-exclusive playback assessment

Initial assessment: 22 October 2026. Design clarification and provider-reference recheck: 23 October 2026. Local implementation approval does not authorize publication.

## Decision

**Technically feasible; activation held pending application permission, not a technical impossibility.** A process-lifetime engine choice could keep Spotify PCM separate from the standard resolver, preserve settings, and require a restart. This would be a separate playback mode, not Spotify priority #2. It would not, by itself, establish permission for a multi-service application using an unofficial playback client. No exclusive-mode switch or fake resolver adapter has been added.

Spotify's [Developer Policy, section III](https://developer.spotify.com/policy) restricts products integrated with another service's streams/content, segueing/mixing/overlap, and analysis of Spotify content. Section IV requires Premium for streaming. The restriction on an integrated product is broader than avoiding simultaneous playback. I found no published exception saying that a restart-separated mode makes this application permissible. This is an engineering assessment of published restrictions, not a determination that Spotify has approved the fork.

The [Developer Terms](https://developer.spotify.com/terms) also restrict reverse engineering, subject to applicable-law exceptions. [librespot's own disclaimer](https://github.com/librespot-org/librespot#disclaimer) cautions about permission to connect. A successful OAuth exchange, a Premium subscription, and another application's existence do not establish developer permission. A supported, approved integration or applicable written Spotify permission is the unresolved prerequisite.

The [implementation-ready conditional design](spotify-only-mode-design.md) specifies mode/bootstrap ownership, queue and session isolation, migration, permission gates, refusal attribution and verification. It compares a standalone application with restart-separated BitChord and considers an explicit official-client handoff. A standalone binary reduces technical coupling; it does not establish permission to use the playback mechanism, application identity or a replacement player experience. Neither product shape is represented as compliant.

This follow-up changes documentation only. The existing helper, authentication, queue, refusal behavior and unavailable priority row are retained. All new mode, migration, durable-restriction and handoff behavior in the design remains proposed.

## Spotifast architecture inspected

The reference was read remotely; none of its implementation was copied into BitChord.

| Boundary | Spotifast source observation | BitChord's existing boundary |
| --- | --- | --- |
| Authentication | [auth.rs](https://github.com/crmne/spotifast/blob/main/src/auth.rs) separates Web API grants from a playback grant, uses browser Authorization Code + PKCE and a loopback callback, and checks state/cancellation/timeouts. | Separate library and streaming grants already exist in `DesktopSpotifyOAuth.kt`, `DesktopSpotifySession.kt`, and `DesktopSpotifyPlayback.kt`. The playback grant uses the same desktop client identity as the reference. This is not proof that BitChord is authorized to use that identity. |
| Entitlement | [backend.rs](https://github.com/crmne/spotifast/blob/main/src/backend.rs) blocks known Free accounts. Unknown plan state is not equivalent to confirmed entitlement. | The helper authenticates a real librespot session and preserves its Premium-required rejection. Web and playback identities must match. No entitlement bypass was added. |
| Credentials | [credentials.rs](https://github.com/crmne/spotifast/blob/main/src/credentials.rs) keeps independent protected slots, serializes native store work, and rejects stale writes using generations/revocation markers. | Separate DPAPI-protected grant keys, refresh serialization and connection revisions already prevent stale install after disconnect. No user credentials were used in this assessment. |
| Playback lifetime | [player.rs](https://github.com/crmne/spotifast/blob/main/src/player.rs) owns a librespot `Session`, `Player`, mixer and Spotify Connect `Spirc`; reconnection can retain a player without starting overlapping players. | `DesktopSpotifyRuntime.kt` launches a bounded helper process per track/seek. The Rust helper creates a session/player and sends framed stereo float PCM to `SpotifyPcmSource`. This is genuine native playback, not a playlist or Canvas adapter. Replacing it with Spotifast's lifetime model is unnecessary for the requested assessment. |
| Account changes | Spotifast generations reject late network/store completions. | Existing session revisions, same-account checks and explicit native disconnect remain intact. |

Remote files inspected on the assessment date had blob IDs: `auth.rs` `817d7ea6705c8d65729cf4e983589dbe5d0b0623`, `player.rs` `fe0440099e2db320cbb64fa31f73112c0265b617`, `credentials.rs` `e0634158f1dbe593ebf8c66965c0c4a96c8633c8`, and `backend.rs` `dc7b41e9d4c145d0fc4fa357b2a8ed451c95e1d1`. Links to `main` may change.

## Isolation required if permission is obtained

1. Persist the selected mode separately from each provider's credentials, enabled states and ordering. Capture the active mode at startup; show active engine and a pending-restart change distinctly.
2. Standard mode must never instantiate or resolve native Spotify playback. Spotify-only mode must never resolve JioSaavn, YouTube, addons or local audio as substitutions.
3. Reject mixed-provider queues and restored incompatible queue entries before preparing audio. Cancel existing decks and analysis before changing engines. Preserve incompatible queue/settings data rather than silently rewriting it.
4. Disable AutoMix, segueing, crossfade, Spotify audio analysis and other prohibited processing for Spotify playback. Existing native-track analysis/AutoMix exclusions must remain. Check lyrics/visual synchronization against the applicable permission too.
5. Require authenticated matching account identities and provider-confirmed entitlement. Authentication, entitlement, application permission and an available binary are distinct conditions.
6. Test cold/restored authentication, expiry/rotation, disconnect during authentication, stale completions, seek/restart, incompatible restored queues, rapid skip, refusal and no fallback/overlap using injected engines and synthetic grants. Real authorized account and Windows ARM64/x64 playback acceptance would still be required.

These are prerequisites, not implemented exclusive-mode behavior.

## Enforcement visibility and remaining limitation

The pinned [librespot 0.8.0 authentication mapping](https://github.com/librespot-org/librespot/blob/v0.8.0/core/src/connection/mod.rs) exposes application-banned, invalid-key, verification, credential and Premium-required reasons. It does **not** expose a universal `UserViolation` reason in that mapping. BitChord's helper preserves application-banned as public protocol code **313**. That code does not prove that combining sources caused the refusal.

The PCM decoder propagates bounded refusal codes. Regression tests exposed an initial native failure entering the generic retry path and a midstream refusal losing its provider message. Both are corrected: native opening failure stops after one resolution/open, midstream refusal retains the provider diagnostic, and neither advances the queue. Code 313 is described as an application restriction instead of encouraging reconnection. Two engine regressions failed before the fix and passed afterward, alongside six PCM protocol tests. No error details containing credentials are forwarded, and no enforcement is bypassed or suppressed.

The validated helper boundary is 44.1 kHz stereo float PCM. Diagnostics identify that boundary and the librespot PCM helper; its original compressed codec is unknown. Decoded float samples are not treated as evidence that the original Spotify stream was lossless or hi-res.

**Incomplete:** comprehensive detection of provider responses specifically attributing a violation to mixed-service use, a durable enforcement latch, and authorized Spotify-exclusive playback. The helper currently collapses some playback failures to generic unavailable/end-of-channel codes; arbitrary upstream log text is deliberately not enabled because it can contain authentication data. No undocumented numeric violation code or causal interpretation has been invented. An approved protocol/API exposing a structured reason would be needed for reliable attribution and recovery rules.

The existing native authentication, playback and account settings are preserved. Spotify remains visibly unavailable as an automatic resolver source; its persisted row is not evidence that it can serve priority-based audio. Official [Web Playback SDK](https://developer.spotify.com/documentation/web-playback-sdk) playback in a supported browser or playback controlled in Spotify's own application are possible alternatives, subject to the same applicable application restrictions and authorization. Neither is a drop-in native PCM replacement and neither has been implemented here.
