# Real local Web/Go Listen Together runtime

Status: **Complete for the exercised Web/server contract. Installed mixed Kotlin-client playback remains Partial. Local only.**

## Evidence gap and implementation

Prior controlled-transport tests execute the actual Web store, and Go httptest checks execute actual server handlers, but they ran independently. This phase connects them. No production membership, backend, playback engine or authentication implementation changes are needed to pass the real runtime scenario.

Exact added files: `web/app/src/party/backend.integration.test.ts`, `web/app/scripts/party-backend-probe.mjs`, `web/app/tests/party-backend/README.md`. `web/app/package.json` adds only the working `test:party:backend` script. Reports update the capability and convergence evidence. No dependency, lockfile, Go module, Gradle, AGP, Java or native build setting changes.

From `web`, run `pnpm --filter @bitchord/app test:party:backend` with the repository's Go toolchain/cached modules. `BITCHORD_GO` may name an existing executable. The command honors existing caches and uses local/offline Go resolution, so it cannot silently install a different toolchain or dependency. A fresh ignored build directory holds a temporary Go test overlay that invokes the existing setupTestServer and production handlers. The server binds only 127.0.0.1 on a random port, closes after the test, and has a bounded timeout. No backend source file, production endpoint or bind setting is changed.

The network test executes the public production Web membership/control APIs, store, reconnect and clock logic using real REST and WebSocket connections. Only provider identity lookup and browser storage use synthetic fixtures. A second real WebSocket protocol peer sends controls; it is not an Android or native Kotlin client. Test credentials are ephemeral local synthetic membership tokens, not provider or personal credentials. No provider media, installed extension or deployed server is contacted.

## Executed behavior and results

The real scenario passes creation, welcome/member state and ping/pong clock synchronization; negotiated bitchord.v1 authentication; queue and track publication; AutoPlay markers; duplicate-track queue needle; peer seek; server-enforced host-only refusal and unlock; reconnect grace preserving the same member/playback/queue; paused playhead; kick delivery; and local/session leave cleanup. These are actual network frames, not a hand-written server response list. The source command exits zero, as do the client test and Go helper independently. Source fingerprints and separate logs/result JSON are retained.

The initial harness attempt stopped before test execution because pnpm supplied a native Windows executable through npm_execpath. The runner now executes native .exe pnpm directly and JavaScript pnpm through Node. The failure log is retained. A separate missing-Go run fails as intended in approximately half a second, does not execute the client test and reports the missing executable; it is prerequisite/error-path verification, not a passing runtime test. The finalized positive command executes again to terminal exit zero.

Ordinary recursive verification passes **213 app and nine shared tests (222 total)**, with **one opt-in network test skipped** because that run does not start Go. The dedicated source command separately executes that network test successfully. Recursive type checks and app/Chrome/Firefox production builds exit zero. Existing bundle-size and mixed-import warnings remain. No tests or assertions are removed and no check is disabled to obtain these results.

## Remaining limits and preservation

This demonstrates the exercised production Web/store/server contract under Node's real network stack. It does not prove browser CORS/extension permission/session behavior, physical media timing, every network-failure permutation, native bearer-client playback, installed Android/native Kotlin lifecycle correctness or deployed-service behavior. Existing Go browser/native authentication boundary tests complement this result; they are not silently substituted for actual Kotlin clients. Physical mixed-device synchronization remains incomplete.

The original staged/unstaged distinction, HEADs and exact indexes remain preserved. Both source roots receive only the reviewed allowlisted files with candidate before-content guards. The original backend and existing Web membership source are byte-unchanged in this phase. Native ZIP/APK and accepted Surface touch/Home/FPS/diagnostics/Thai behavior are preserved. No new commit, push, upload, pull request or deployment.

- [x] Inspect actual client/server paths and preserve baseline before edits.
- [x] Execute actual local runtime scenario and verify client/helper terminal results.
- [x] Verify missing prerequisite behavior without downloads/cache deletion.
- [x] Pass ordinary tests/typechecks/builds while reporting the opt-in skip accurately.
- [x] Guard reviewed source reconciliation and verify both indexes unchanged.
- [ ] Extend actual runtime verification to Kotlin clients/media timing and installed browser/provider conditions.
