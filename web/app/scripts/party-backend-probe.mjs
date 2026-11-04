import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { access, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const app = fileURLToPath(new URL('..', import.meta.url));
const backend = path.resolve(app, '../../backend');
const output = path.join(app, 'build/party-backend-probe');
await mkdir(output, { recursive: true });
const run = await mkdtemp(path.join(output, 'run-'));
const ready = path.join(run, 'server.url');
const stop = path.join(run, 'stop');
const virtual = path.join(backend, 'local_client_probe_test.go');
try { await access(virtual); throw new Error('Preserve existing backend/local_client_probe_test.go'); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const fixture = path.join(run, 'local_client_probe_test.go');
await writeFile(fixture, `package main
import ("os"; "testing"; "time")
func TestLocalClientProbe(t *testing.T) {
  server := setupTestServer()
  defer server.Close()
  if err := os.WriteFile(os.Getenv("BITCHORD_PROBE_READY"), []byte(server.URL), 0600); err != nil { t.Fatal(err) }
  deadline := time.Now().Add(90 * time.Second)
  for time.Now().Before(deadline) {
    if _, err := os.Stat(os.Getenv("BITCHORD_PROBE_STOP")); err == nil { return }
    time.Sleep(50 * time.Millisecond)
  }
  t.Fatal("Local Web client did not stop its test server within 90 seconds")
}
`);
const overlay = path.join(run, 'overlay.json');
await writeFile(overlay, JSON.stringify({ Replace: { [virtual]: fixture } }, null, 2));
const source = {};
for (const relative of ['package.json', 'scripts/party-backend-probe.mjs', 'tests/party-backend/README.md', 'src/party/backend.integration.test.ts',
  'src/party/listenTogether.ts', 'src/party/models.ts', 'src/party/clock.ts', '../../backend/main.go',
  '../../backend/main_test.go', '../../backend/party/party.go', '../../backend/protocol/protocol.go',
  '../../backend/hub/hub.go', '../../backend/config/config.go']) {
  source[relative] = createHash('sha256').update(await readFile(path.resolve(app, relative))).digest('hex');
}
await writeFile(path.join(run, 'source.json'), JSON.stringify({ source }, null, 2));
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function execute(command, args, options, logName) {
  const log = createWriteStream(path.join(run, logName), { flags: 'wx' });
  const child = spawn(command, args, { ...options, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let result;
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
  const done = new Promise(resolve => {
    child.once('error', error => { result = { code: null, error: String(error) }; log.write(String(error)); log.end(); resolve(result); });
    child.once('exit', (code, signal) => { result = { code, signal }; log.end(); resolve(result); });
  });
  return { child, done, get result() { return result; } };
}

let serverResult;
let testsResult;
let failure;
const server = execute(process.env.BITCHORD_GO || 'go', ['test', `-overlay=${overlay}`, '-run', '^TestLocalClientProbe$', '-count=1', '-timeout=100s', '.'], {
  cwd: backend, env: { ...process.env, GOTOOLCHAIN: 'local', GOPROXY: 'off',
    BITCHORD_PROBE_READY: ready, BITCHORD_PROBE_STOP: stop },
}, 'server.log');
try {
  const deadline = Date.now() + 45000;
  let url;
  while (!url) {
    if (server.result) throw new Error('Go test server exited before readiness; inspect server.log');
    try { url = (await readFile(ready, 'utf8')).trim(); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (!url && Date.now() >= deadline) throw new Error('Go test server readiness timed out; inspect server.log');
    if (!url) await pause(50);
  }
  const address = new URL(url);
  if (address.protocol !== 'http:' || address.hostname !== '127.0.0.1') throw new Error('Probe server must be loopback HTTP');
  console.log('Running production Web membership against actual local Go REST/WebSocket handlers. No provider audio or accounts.');
  const pnpm = process.env.npm_execpath;
  if (!pnpm || !path.isAbsolute(pnpm)) throw new Error('Run through pnpm --filter @bitchord/app test:party:backend');
  const nativePnpm = process.platform === 'win32' && pnpm.toLowerCase().endsWith('.exe');
  const testArgs = ['--filter', '@bitchord/app', 'test', 'src/party/backend.integration.test.ts'];
  const tests = execute(nativePnpm ? pnpm : process.execPath, nativePnpm ? testArgs : [pnpm, ...testArgs], {
    cwd: path.resolve(app, '..'), env: { ...process.env, BITCHORD_PARTY_PROBE_URL: url },
  }, 'client.log');
  testsResult = await tests.done;
  if (testsResult.code !== 0) throw new Error('Real client/server checks failed; inspect client.log');
} catch (error) { failure = String(error); }
finally {
  await writeFile(stop, 'stop\n');
  serverResult = await server.done;
}
const passed = !failure && testsResult?.code === 0 && serverResult.code === 0;
await writeFile(path.join(run, 'result.json'), JSON.stringify({ passed, failure, testsResult, serverResult,
  scope: 'Production Web membership store and actual Go handlers with one protocol peer. No Kotlin clients, provider audio, installed extension or physical mixed-client acceptance.' }, null, 2));
console.log(`${passed ? 'Passed' : 'Failed'}; evidence: ${run}`);
if (!passed) {
  console.error(failure ?? 'Go helper failed');
  process.exitCode = 1;
}
