# Web Listen Together membership ownership

Status: **Complete for reproduced stale membership effects; mixed-client acceptance remains Partial. Local only.**

## Root cause and actual behavior

The actual Web membership state machine (`web/app/src/party/listenTogether.ts`) checks membership after three failed socket opens. It previously awaited a REST response/error body and then left whichever party was current. A delayed 401/404 for an old party could therefore eject a newly joined party, erase its stored session and show an obsolete error. Separately, the kick handler waited for the old slot-release request before setting its error, which could overwrite the state of a later successful join. These are reproduced in the public join/resume/leave paths, not inferred from model parsing alone.

The client now increments membership identity on adoption/resume/leave and captures generation, server, code and token for each membership check. It rechecks ownership after both network and body awaits. Valid current 401/404 responses still retire the membership and report refusal. Local retirement/error reporting happens synchronously; the old slot-release request continues using its captured identity, without any later write to a newer party. Kick blocking remains enforced. Socket reconnect does not change membership generation, so a genuine current refusal is still honored across reconnects.

Exact implementation/test changes: `web/app/src/party/listenTogether.ts` and new `web/app/src/party/membership.test.ts`. No authentication protocol, provider credentials, account behavior, queue format, playback engine, dependency pin, build setting or native package is changed.

## Verification actually executed

The initial 15-case run reproduces **six failures**, including switch, leave, same-code rejoin, delayed error-body parsing and delayed kick notification. All **18 final cases pass**, adding identical-token generation reuse, delayed refused-slot release and current refusal across reconnect. Other cases verify valid 401/404 retirement, network interruption/retry, terminal server refusal, old-socket frame rejection, queue/state sequence ordering, missed-queue resync and retention of host-only control when an older members frame omits it. All cases assert timer retirement after leaving.

These tests execute the actual exported Zustand membership store and socket/frame handlers, with controlled fetch/WebSocket boundaries and synthetic test identities. They do not sign in to a real provider, connect to a deployed service, or execute Android/native Kotlin state machines. The production test suite passes **213 app plus nine shared tests (222 total)**. Recursive type checks and app/Chrome/Firefox production builds exit zero. No assertions or existing checks are removed; the lockfile is unchanged.

Fresh local backend `go test ./... -count=1 -json` and `go vet ./...` both exit zero. This executes 48 top-level tests (57 passed events including subtests), including actual httptest REST/WebSocket, browser subprotocol versus native bearer authentication, queue ordering, host-only restrictions and socket replacement boundaries. These server runs complement the Web state-machine tests; they are not a single end-to-end run of three installed clients.

## Cross-platform audit and limits

Android and native Desktop were inspected separately. They use coroutine socket jobs and bearer-header transport; Android also serializes switching/leaving with a mutex. Neither uses this Web-specific three-failed-open REST membership-check function. Their queue/state schemas, stale-sequence guards and missing-queue resync behavior correspond to the inspected Web/server contract. This is source-level correspondence, not proof of all asynchronous lifecycle paths. Native session-finally retirement and delayed terminal-leave ownership require dedicated coroutine fault injection before any correction; no speculative Kotlin changes are made here.

Physical Web/Android/native mixed-party playback, resume lead, drift/seek alignment, deployed-service failures and installed-extension/provider authorization remain unverified. Optional real-client/local-server scenarios remain the next verification gate. This phase does not claim mixed-client completeness or general synchronization quality.

## Preservation and publication

Both original HEADs/indexes, independent staged/unstaged patches and affected source are preserved in outer `.work/party-membership-20261026`. Exact-file candidate projection uses before-content guards and verifies both indexes unchanged. Source-only environment exclusions remain local. No commit, push, upload, pull request or deployment is made. Latest verified native ZIP/APK and the accepted Surface Thai result are unchanged.

- [x] Read actual Web, Android/native lifecycle and server protocol implementations.
- [x] Reproduce six failures before correction; pass 18 focused cases.
- [x] Run 222 Web tests, type checks/builds and fresh backend tests/vet.
- [x] Guard source reconciliation and preserve Git staging distinction.
- [ ] Execute actual mixed installed-client/server/playback acceptance.
