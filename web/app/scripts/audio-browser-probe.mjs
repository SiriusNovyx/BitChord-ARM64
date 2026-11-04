import {createHash} from 'node:crypto';
import {createServer} from 'node:http';
import {mkdir, mkdtemp, readFile, writeFile} from 'node:fs/promises';
import {dirname, extname, join, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {build, loadConfigFromFile, mergeConfig} from 'vite';

const app = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const fixture = join(app, 'tests/browser-audio');
const args = process.argv.slice(2);
if (args.some(arg => arg !== '--gain-only')) throw new Error('Only --gain-only is supported');
const page = args.includes('--gain-only') ? 'gain.html' : 'index.html';
const base = join(app, 'build/audio-browser-probe');
await mkdir(base, {recursive: true});
const directory = await mkdtemp(join(base, 'run-'));
const dist = join(directory, 'site');
// No deployed service participates in these generated-audio checks.
process.env.VITE_PARTY_SERVER_URL = 'https://ci-fixture.invalid';
const config = await loadConfigFromFile({command: 'build', mode: 'production'}, join(app, 'vite.config.ts'), app);
if (!config) throw new Error('The app Vite configuration was not found');
const paths = ['autodj.ts', 'audioClock.ts', 'deck.ts', 'loopDeck.ts', 'loopMix.ts', 'transition-scheduler.ts', 'timeline.ts', 'eq.ts']
  .map(name => 'src/playback/automix/engine/' + name);
const sha = data => createHash('sha256').update(data).digest('hex');
const source = Object.fromEntries(await Promise.all(paths.map(async path => [path, sha(await readFile(join(app, path)))])));
await build(mergeConfig(config.config, {configFile: false, root: fixture, envDir: app,
  build: {outDir: dist, emptyOutDir: false, rolldownOptions: {input: {
    graph: join(fixture, 'index.html'), gain: join(fixture, 'gain.html'),
  }}}}));
await writeFile(join(directory, 'source.json'), JSON.stringify({source, page}, null, 2));

let origin;
let timer;
let received = false;
const stop = () => { clearTimeout(timer); server.close(); server.closeIdleConnections(); };
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? '/', origin);
    if (request.method === 'POST' && url.pathname === '/_result') {
      if (request.headers.origin !== origin || !request.headers['content-type']?.startsWith('application/json')) {
        response.writeHead(403).end(); return;
      }
      if (received) { response.writeHead(409).end(); return; }
      const chunks = [];
      let length = 0;
      for await (const chunk of request) {
        length += chunk.length;
        if (length > 100_000) { response.writeHead(413).end(); return; }
        chunks.push(chunk);
      }
      const result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      for (const [path, expected] of Object.entries(source)) {
        if (sha(await readFile(join(app, path))) !== expected) throw new Error('Source changed while the probe was running');
      }
      await writeFile(join(directory, 'result.json'), JSON.stringify(result, null, 2));
      received = true;
      process.exitCode = result.passed === true ? 0 : 1;
      response.setHeader('Connection', 'close');
      response.writeHead(204).end();
      console.log(`Audio probe ${result.passed === true ? 'passed' : 'failed'}; evidence: ${directory}`);
      stop();
      return;
    }
    if (request.method !== 'GET') { response.writeHead(405).end(); return; }
    const path = resolve(dist, '.' + decodeURIComponent(url.pathname === '/' ? '/' + page : url.pathname));
    if (!path.startsWith(dist + sep)) { response.writeHead(404).end(); return; }
    const body = await readFile(path);
    const mime = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.wasm': 'application/wasm'};
    response.setHeader('Content-Type', mime[extname(path)] ?? 'application/octet-stream');
    response.setHeader('Cache-Control', 'no-store');
    response.writeHead(200).end(body);
  } catch (error) {
    if (request.method === 'GET' && error?.code === 'ENOENT') {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(500).end('Audio probe request failed');
    console.error(error);
    process.exitCode = 1;
    stop();
  }
});
server.on('error', error => { console.error(error); process.exitCode = 1; stop(); });
server.listen(0, '127.0.0.1', () => {
  origin = `http://127.0.0.1:${server.address().port}`;
  console.log(`Open ${origin}/${page} and press Run. Output stays silent; no account access is needed.`);
});
timer = setTimeout(() => { console.error('Audio probe timed out without a result'); process.exitCode = 1; stop(); }, 10 * 60_000);
process.once('SIGINT', () => { process.exitCode = 1; stop(); });
