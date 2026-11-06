import { afterAll, expect, test, vi } from 'vitest';

vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => ({
  signedIn: true, active: { authUser: 0, id: 'mixed-local-web-peer', name: 'Local Web peer' },
}) }));

const base = process.env.BITCHORD_PARTY_PROBE_URL;
const code = process.env.BITCHORD_NATIVE_PROBE_CODE;
class Store {
  private values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}
let client: typeof import('./listenTogether') | undefined;
afterAll(async () => { if (client) await client.leaveParty(); vi.unstubAllGlobals(); });

test.runIf(Boolean(base && code))('actual Web and native Kotlin stores exchange sequenced controls in one local party', async () => {
  const address = new URL(base!);
  expect(address.protocol).toBe('http:');
  expect(address.hostname).toBe('127.0.0.1');
  expect(address.username + address.password + address.search + address.hash).toBe('');
  expect(address.pathname).toBe('/');
  vi.stubGlobal('localStorage', new Store());
  vi.stubGlobal('sessionStorage', new Store());
  client = await import('./listenTogether');
  const until = async (check: () => boolean) => {
    const deadline = Date.now() + 8000;
    while (!check()) {
      if (Date.now() >= deadline) throw new Error('Native/Web control exchange did not settle');
      await new Promise(resolve => setTimeout(resolve, 20));
    }
  };
  await client.joinParty(code!, base);
  await until(() => client!.useParty.getState().connection === 'live' && client!.useParty.getState().clockSynced);
  expect(client.useParty.getState()).toMatchObject({ code, you: { isHost: false },
    playback: { track: { videoId: 'a', fromAutoplay: true }, positionMs: 1111, queueIndex: 2, isPlaying: false },
    queue: { items: [{ videoId: 'a' }, { videoId: 'b' }, { videoId: 'a', fromAutoplay: true }] } });
  expect(client.useParty.getState().members).toHaveLength(2);
  expect(client.useParty.getState().members).toEqual(expect.arrayContaining([
    expect.objectContaining({ isHost: true }), expect.objectContaining({ isHost: false }),
  ]));
  const initial = client.useParty.getState().playback.seq;
  client.party.seek(8123);
  await until(() => client!.useParty.getState().playback.positionMs === 9123 && client!.controlsLocked());
  expect(client.useParty.getState().playback.seq).toBeGreaterThan(initial);
  client.party.seek(9999);
  await until(() => client!.useParty.getState().error === 'Only the host can control the music in this party.');
  expect(client.useParty.getState().playback.positionMs).toBe(9123);
  expect(client.partyPositionMs()).toBe(9123);
  await client.leaveParty();
  expect(client.useParty.getState().code).toBeNull();
}, 20000);
