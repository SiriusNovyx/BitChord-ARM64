# Repeatable Surface Pro 11 acceptance scenarios

Target: Snapdragon X Plus, Windows 11 ARM64. Automated regression results are recorded separately. This procedure has **not** been run on the physical Surface, and no GPU, battery, audible-quality or nine-hour improvement is claimed.

## Record the environment

Record build/source identity, JVM/native architecture, Windows and GPU driver version, screen resolution/scaling and 60/120 Hz setting, power mode/battery saver, AC/battery, output endpoint, window size and visible page. Use the same authorized tracks and artwork for comparisons. Record whether network/caches/model initialization are cold or reused; do not clear the user's settings or credentials. Use stereo, plain DD+ and an authorized known JOC fixture separately.

The user's earlier approximate nine-hour observations were 28–31% GPU with full lyrics and 1.5–2.8% without lyrics, with smoother UI after one to three hours. These are user observations, not a measured baseline for this implementation or proof of shader, GC or AutoMix causality.

## Rendering matrix

Keep Visual Performance set to Performance. Repeat each row at Dynamic, 15, 30 and 60 background FPS. Keep lyrics settings and the display refresh rate fixed within a comparison. Repeat on AC and battery, then minimize/restore and cover the background with another page/player state.

| Scenario | Initial sample | Later sample | Evidence to capture |
| --- | --- | --- | --- |
| Same song without lyrics | First 120 seconds after enabling/resetting metrics | 5–10 minutes and after 1–3 hours | Background frame gaps/CPU draw submission, player composition count, GPU engine usage/frame timing, heap/GC |
| Same full synchronized lyrics | First 120 seconds | Same later intervals | Above plus lyric composition/frame counts, visible lyric timing, dropped/delayed frames |
| AutoMix initialization and skip | First analyzed pair | Cached/repeated pair | Analysis duration, controls/skip responsiveness, active worker count, input/output position and lyric alignment |
| Hidden/minimized background | Before hiding | At least 60 seconds hidden; restore | Background frame/draw sample counts stop, GPU idle behavior, no jump in lyric/audio position |

Enable optional Visual Performance measurements before the sample and use Reset to define its start. Copy results and record elapsed time. “Startup” means the first 120 seconds after reset; the “warmed” bucket only means later samples, not proof that shaders or models are warm. Starting collection can be done before the comparison, but startup before the toggle is enabled requires an external trace. Disable/reset between independent scenarios to avoid pooled results.

Use Task Manager's GPU engine view for coarse usage with its sampling interval recorded. For frame time, missed frames, shader compilation and texture allocation, capture a repeatable Windows graphics trace with WPR/GPUView/WPA or an equivalent profiler available on the device. Correlate trace timestamps with actions. CPU draw-submission time is not GPU execution time. Background frame gaps contain intentional FPS pacing and cannot be reported as application dropped frames.

The diagnostics dialog records JVM heap and lifetime GC counters only when requested. Record start/end used and committed heap, GC count/time deltas, collection duration and workload. For allocation/GC attribution, take a bounded JVM recording with allocation samples and GC pauses, using the same recording settings for each build. Do not infer a leak from committed heap alone. Do not run invasive profiling during the subjective smoothness comparison without noting its overhead.

## Input and refresh

1. On Home, Library, Search, Settings, playlists, queue, lyrics and dialogs: one-finger vertical dragging; horizontal shelves where present; diagonal movement; fling then retouch. Confirm finger movement scrolls without activating a row.
2. Check mouse wheel, hover, row activation, mouse text selection, scrollbar dragging, keyboard focus/activation and Escape. Check the 48dp source and 44dp queue/playlist handles independently from surrounding scrolling. Reordering must neither click a row nor scroll its parent.
3. Cancel a touch by focus/capture loss; open a dialog and touch outside it; change scaling/rotation, minimize/restore and move between displays. Confirm no stuck contacts or background clicks.
4. Home at top: wheel upward past the reveal threshold, then stop. Confirm one refresh after idle, visible indicator/result, and old content until new data arrives. A subthreshold wheel movement must not refresh.
5. Pull Home down with a finger and release above/below threshold; cancel before release. Verify no refresh on cancellation and no concurrent duplicate requests. Return before/after five minutes, resume, and test offline/reconnect/backoff. Music must continue throughout.

## Playback and Atmos evidence

Record preference, resolved source/rendition, observed codec/profile, actual decoder input/name and current endpoint. Repeat with preference on/off for a newly resolved track; the existing playing/cached stream may keep its format. Plain E-AC-3 must remain DD+, never confirmed object-based Atmos. A JOC profile is input evidence only. Surface speaker, Bluetooth, USB and HDMI routing must be checked against the actual endpoint. Confirmed object rendering requires backend/output evidence beyond decoder existence or an audible difference.

For AutoMix, compare a cold pair and cached pair, rapid repeated skips, slow/disconnected network and CPU contention. Confirm obsolete transitions never apply, no overlapping analysis workers accumulate, control actions remain responsive, and lyrics follow the published playback position. The 15-second skip setting is fade duration; measure preparation time from actual upcoming selection rather than assuming a 15-second preparation deadline.

## Results template

For every run record: scenario/build, start timestamp, duration, cold/reused state, power/display/route, background cap, lyrics mode, GPU engine/usage and profiler, frame-time distribution/missed frames, JVM used/committed heap and GC deltas, instrumentation export, action timestamps, observed defects and trace paths. Mark unavailable measurements as unavailable. Keep logs free of credentials and private track/account identifiers before sharing.
