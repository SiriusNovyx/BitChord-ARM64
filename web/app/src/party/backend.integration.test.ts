import { afterAll, expect, test, vi } from 'vitest';

vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => ({
  signedIn: true, active: { authUser: 0, id: 'local-probe-host', name: 'Local probe host' },
}) }));

// The source command supplies a fresh loopback server; ordinary unit runs do not start Go.
const base = process.env.BITCHORD_PARTY_PROBE_URL;
const pause = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
async function until(check: () => boolean, message: string) {
  const deadline = Date.now() + 8000;
  while (!check()) {
    if (Date.now() >= deadline) throw new Error(message);
    await pause(20);
  }
}

class Store {
  private values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}

let client: typeof import('./listenTogether') | undefined;
let peer: WebSocket | undefined;
const sockets: WebSocket[] = [];
afterAll(async () => {
  peer?.close();
  if (client) await client.leaveParty();
  sockets.forEach(socket => socket.close());
  vi.unstubAllGlobals();
});

test.runIf(Boolean(base))('production Web store talks to actual Go REST/WebSocket handlers', async () => {
  const address = new URL(base!);
  expect(address.protocol).toBe('http:');
  expect(address.hostname).toBe('127.0.0.1');
  expect(address.username + address.password + address.search + address.hash).toBe('');
  expect(address.pathname).toBe('/');
  vi.stubGlobal('localStorage', new Store());
  const session = new Store();
  vi.stubGlobal('sessionStorage', session);
  const RealSocket = WebSocket;
  class TrackedSocket extends RealSocket {
    constructor(url: string | URL, protocols?: string | string[]) {
      super(url, protocols); sockets.push(this);
    }
  }
  vi.stubGlobal('WebSocket', TrackedSocket);
  client = await import('./listenTogether');
  client.savePartySettings({ customServer: base! });
  const code = await client.createParty({ maxMembers: 3, autoplayEnabled: true });
  await until(() => client!.useParty.getState().connection === 'live' && client!.useParty.getState().clockSynced,
    'Real welcome/pong did not reach the production store');
  expect(client.useParty.getState()).toMatchObject({ code, you: { isHost: true }, maxMembers: 3, playback: { autoplayEnabled: true } });
  expect(sockets[0]!.protocol).toBe('bitchord.v1');
  expect(session.getItem('bitchord:party-session')).toContain(code);

  const joined = await fetch(`${base}/api/parties/${code}/join`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId: 'local-probe-peer', deviceId: 'local-probe-peer', displayName: 'Protocol peer' }) });
  expect(joined.status).toBe(200);
  const membership = await joined.json() as { token: string; you: { memberId: string } };
  const peerFrames: Record<string, any>[] = [];
  peer = new RealSocket(`${base!.replace('http:', 'ws:')}/ws/parties/${code}`, ['bitchord.v1', `bitchord.token.${membership.token}`]);
  peer.addEventListener('message', event => peerFrames.push(JSON.parse(String(event.data))));
  await until(() => peerFrames.some(f => f.type === 'welcome') && client!.useParty.getState().members.length === 2,
    'Real peer membership did not reach both sockets');
  const sendPeer = (frame: Record<string, unknown>) => peer!.send(JSON.stringify({ type: 'control', ...frame }));
  const tracks = ['a', 'b', 'a'].map((videoId, index) => ({ videoId, title: `Fixture ${videoId}`, artist: 'Local PCM fixture',
    thumbnailUrl: null, durationMs: 180000, fromAutoplay: index === 2 }));
  client.party.setQueue(tracks, 1);
  client.party.setTrack(tracks[1]!, 2500, false);
  await until(() => client!.useParty.getState().playback.track?.videoId === 'b' && client!.useParty.getState().queue.items.length === 3,
    'Actual queue/track controls were not reflected');
  expect(client.useParty.getState()).toMatchObject({ queue: { index: 1, items: [{ videoId: 'a' }, { videoId: 'b' }, { videoId: 'a', fromAutoplay: true }] },
    playback: { queueIndex: 1, positionMs: 2500, isPlaying: false } });

  sendPeer({ action: 'seek', positionMs: 5456 });
  await until(() => client!.useParty.getState().playback.positionMs === 5456, 'Peer seek did not arrive over real socket');
  client.party.setHostOnlyControl(true);
  await until(() => client!.useParty.getState().hostOnlyControl, 'Host control restriction was not broadcast');
  sendPeer({ action: 'seek', positionMs: 9999 });
  await until(() => peerFrames.some(frame => frame.type === 'error' && frame.error === 'host_only'), 'Server did not refuse locked peer control');
  expect(client.useParty.getState().playback.positionMs).toBe(5456);
  client.party.setHostOnlyControl(false);
  await until(() => !client!.useParty.getState().hostOnlyControl, 'Host unlock was not broadcast');
  sendPeer({ action: 'seek', positionMs: 6789 });
  await until(() => client!.useParty.getState().playback.positionMs === 6789, 'Unlocked peer control was not accepted');

  client.party.next();
  await until(() => client!.useParty.getState().playback.queueIndex === 2, 'Server did not advance to duplicate at queue needle');
  expect(client.useParty.getState().playback.track).toMatchObject({ videoId: 'a', fromAutoplay: true });
  client.party.pause(1111);
  await until(() => !client!.useParty.getState().playback.isPlaying && client!.useParty.getState().playback.positionMs === 1111,
    'Host pause did not settle before reconnect');
  const before = client.useParty.getState();
  sockets[0]!.close(1000, 'local reconnect probe');
  await until(() => sockets.length > 1 && client!.useParty.getState().connection === 'live' && client!.useParty.getState().clockSynced,
    'Production reconnect/clock reset did not recover');
  expect(client.useParty.getState()).toMatchObject({ code, playback: { seq: before.playback.seq, queueIndex: 2, positionMs: 1111, isPlaying: false },
    queue: { seq: before.queue.seq, items: tracks } });
  expect(client.useParty.getState().you?.memberId).toBe(before.you?.memberId);
  expect(client.partyPositionMs()).toBe(1111);

  client.party.kick(membership.you.memberId);
  await until(() => peerFrames.some(frame => frame.type === 'bye' && frame.reason === 'kicked'), 'Kick did not reach real peer');
  await until(() => client!.useParty.getState().members.length === 1, 'Kicked member remains in host store');
  await client.leaveParty();
  expect(client.useParty.getState()).toMatchObject({ code: null, connection: 'offline' });
  expect(session.getItem('bitchord:party-session')).toBeNull();
}, 30000);
