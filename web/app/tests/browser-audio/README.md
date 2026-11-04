# Real Web audio regression checks

From `web`, run:

```text
pnpm --filter @bitchord/app test:audio:browser --gain-only
pnpm --filter @bitchord/app test:audio:browser
```

Open the printed localhost URL in the browser to test and press Run. The first command renders three gain-scheduling regressions through actual OfflineAudioContext/AudioParam implementations. It models the media element's 120 ms start lead and verifies that incoming PCM stays silent until the intended fade. The ordinary Vitest scheduler regressions additionally cover 40/120/300 ms start leads.

The second command builds and runs the actual createAutomixGraph, HTMLAudioElement decks and Signalsmith WASM worklets against two deterministic generated stereo WAV inputs. Known fixture analyses and local audio bytes replace provider/model requests in this probe only. It checks normal PCM, no-grid fallback, pause/cancel/restart, real loop preparation/rendering and promotion. It also checks context suspension/resume, single handoff/completion, and observed metadata delay against the fallback midpoint and actual loop ramp boundary (under 200 ms in the foreground probe). These are browser observations, not sample-accurate or background-throttling guarantees. The fixture advances the outgoing element to the prepared loop's approach to keep the test bounded; this is not a production UI seek test.

An AudioWorklet measures PCM before a final zero-gain output, so these checks produce no audible output and do not change system volume. They do not sign in, read provider accounts, fetch provider music or use the user's library. Sample peaks and finite PCM do not prove subjective quality, true-peak safety or physical output routing. This does not replace listening, installed-extension/provider, lyric UI or long-session tests.

Each run uses a fresh directory under the already-ignored `web/app/build/audio-browser-probe/`, retaining source fingerprints and result JSON. The server exposes only that generated site's files on `127.0.0.1`, exits after saving a result, and times out after ten minutes. Existing outputs are retained. No new dependency, browser extension or test runner is installed. The Vite build uses a CI fixture party URL; no backend deployment is involved.
