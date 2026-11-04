# Real local party-server check

From `web`, with the repository's Go toolchain available:

```text
pnpm --filter @bitchord/app test:party:backend
```

The command requires the Go version in `backend/go.mod` and cached Go dependencies. It uses `GOTOOLCHAIN=local` and `GOPROXY=off`, so missing prerequisites fail without changing toolchains or downloading dependencies. Set `BITCHORD_GO` to an existing Go executable if it is not on PATH; existing `GOMODCACHE` and `GOCACHE` settings are honored.

The runner starts the actual backend handlers in a fresh loopback-only Go httptest server through a temporary test overlay. It runs the production Web membership store with real REST and WebSocket traffic. Only provider identity lookup and browser storage are replaced with synthetic local fixtures. A second WebSocket protocol peer exercises remote controls and refusals; it is not an installed Android or native Kotlin client.

The test covers membership/welcome, clock synchronization, queue/track publication, AutoPlay flags, duplicate queue identity, remote seek, server-enforced host-only control and unlock, reconnect grace, kick and leave. It does not play provider audio, install an extension, use personal accounts or contact a deployed party server. Ordinary unit runs skip this environment-dependent case; this command supplies its real server and executes it.

Logs, source fingerprints, overlay, readiness and terminal results are retained under the existing ignored `web/app/build/party-backend-probe/run-*/`. The helper closes on completion and has a bounded timeout. No source checkout, caches or existing outputs are deleted. The fixture exposes only existing backend test routes on `127.0.0.1`; it adds no production endpoint or bind configuration.

Passing this check establishes the exercised Web/server contract. Installed browser behavior, actual Kotlin clients, media playback timing and physical mixed-device acceptance require their separate tests.
