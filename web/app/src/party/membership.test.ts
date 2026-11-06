import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => ({
  signedIn: true, active: { authUser: 0, id: 'synthetic-test-account', name: 'Fixture' },
}) }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

class Store {
  private values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}

class Socket {
  static OPEN = 1;
  static instances: Socket[] = [];
  readyState = 0;
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  sent: Record<string, unknown>[] = [];
  constructor(readonly url: string, readonly protocols: string[]) { Socket.instances.push(this); }
  open() { this.readyState = 1; this.onopen?.(); }
  close() { this.readyState = 3; this.onclose?.(); }
  frame(value: unknown) { this.onmessage?.({ data: JSON.stringify(value) }); }
  send(value: string) { this.sent.push(JSON.parse(value)); }
}

const member = { memberId: 'fixture-member', userId: 'fixture-user', displayName: 'Fixture', isHost: true, connected: true };
const membership = (code: string) => ({ code, token: 'synthetic-' + code, you: member,
  party: { code, members: [member], playback: {}, queue: { seq: 0, index: -1, items: [] } } });
const response = (status: number, body: unknown) => ({ ok: status >= 200 && status < 300, status,
  json: async () => body }) as Response;
let client: typeof import('./listenTogether');
let fetcher: ReturnType<typeof vi.fn>;
let session: Store;

beforeEach(async () => {
  vi.resetModules(); vi.useFakeTimers();
  Socket.instances = [];
  session = new Store();
  vi.stubGlobal('sessionStorage', session);
  vi.stubGlobal('localStorage', new Store());
  vi.stubGlobal('WebSocket', Socket);
  vi.spyOn(Math, 'random').mockReturnValue(0);
  fetcher = vi.fn(async (input: string) => {
    const code = String(input).match(/\/parties\/([^/]+)\/join$/)?.[1];
    return code ? response(200, membership(code)) : response(200, {});
  });
  vi.stubGlobal('fetch', fetcher);
  client = await import('./listenTogether');
});

afterEach(async () => {
  fetcher.mockImplementation(async () => response(200, {}));
  await client.leaveParty();
  expect(vi.getTimerCount()).toBe(0);
  vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals();
});

function resume(code = 'ALPHA1', token = 'synthetic-original') {
  session.setItem('bitchord:party-session', JSON.stringify({ code, token, server: 'http://localhost:43123' }));
  client.resumeSession();
  return Socket.instances.at(-1)!;
}

async function checking() {
  const pending = deferred<Response>();
  fetcher.mockImplementationOnce(() => pending.promise);
  Socket.instances.at(-1)!.close();
  await vi.advanceTimersByTimeAsync(500);
  Socket.instances.at(-1)!.close();
  await vi.advanceTimersByTimeAsync(1000);
  Socket.instances.at(-1)!.close();
  expect(fetcher).toHaveBeenCalledWith('http://localhost:43123/api/parties/ALPHA1', expect.objectContaining({ headers: { Authorization: 'Bearer synthetic-original' } }));
  return pending;
}

describe('actual Listen Together membership ownership', () => {
  it.each([401, 404])('ignores a stale %i membership check after switching parties', async status => {
    resume(); const pending = await checking();
    await client.joinParty('BRAVO2', 'http://localhost:43123');
    pending.resolve(response(status, { error: 'no_such_party', message: 'Old membership ended' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: 'BRAVO2', error: null });
    expect(session.getItem('bitchord:party-session')).toContain('BRAVO2');
    expect(Socket.instances.at(-1)!.readyState).toBe(0);
  });

  it('does not publish an obsolete error after leaving', async () => {
    resume(); const pending = await checking();
    await client.leaveParty();
    pending.resolve(response(404, { error: 'no_such_party' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: null, error: null, connection: 'offline' });
    expect(session.getItem('bitchord:party-session')).toBeNull();
  });

  it('distinguishes a new membership in the same party from the old token', async () => {
    resume(); const pending = await checking();
    await client.leaveParty(); resume('ALPHA1', 'synthetic-rejoined');
    pending.resolve(response(401, { error: 'bad_token' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: 'ALPHA1', error: null });
    expect(session.getItem('bitchord:party-session')).toContain('synthetic-rejoined');
  });

  it('rechecks ownership after a delayed error body', async () => {
    resume(); const pending = await checking();
    const body = deferred<unknown>();
    pending.resolve({ ok: false, status: 401, json: () => body.promise } as Response);
    await vi.advanceTimersByTimeAsync(0);
    await client.joinParty('BRAVO2', 'http://localhost:43123');
    body.resolve({ error: 'bad_token', message: 'Old token expired' });
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: 'BRAVO2', error: null });
  });

  it('distinguishes a new generation even if the server restores the same token', async () => {
    resume(); const pending = await checking();
    await client.leaveParty(); resume();
    pending.resolve(response(401, { error: 'bad_token' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: 'ALPHA1', error: null });
  });

  it('does not let a slow refused-membership release overwrite a later join', async () => {
    resume(); const pending = await checking();
    const release = deferred<Response>(); fetcher.mockImplementationOnce(() => release.promise);
    pending.resolve(response(401, { error: 'bad_token', message: 'Old membership denied' }));
    await vi.advanceTimersByTimeAsync(0);
    await client.joinParty('BRAVO2', 'http://localhost:43123');
    release.resolve(response(200, {}));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: 'BRAVO2', error: null });
  });

  it('still honors a current refusal if the socket reconnects while checking', async () => {
    resume(); const pending = await checking();
    await vi.advanceTimersByTimeAsync(2000);
    Socket.instances.at(-1)!.open();
    pending.resolve(response(401, { error: 'bad_token', message: 'Current membership denied' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: null, error: 'Current membership denied' });
  });

  it.each([401, 404])('still retires the current membership on %i', async status => {
    resume(); const pending = await checking();
    pending.resolve(response(status, { error: status === 404 ? 'no_such_party' : 'bad_token', message: 'Membership denied' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: null, connection: 'offline', error: status === 404 ? 'That party has ended.' : 'Membership denied' });
    expect(session.getItem('bitchord:party-session')).toBeNull();
  });

  it('keeps retrying on an unreachable server without inventing a refusal', async () => {
    resume(); const pending = await checking();
    pending.reject(new TypeError('Synthetic network interruption'));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: 'ALPHA1', connection: 'connecting', error: null });
    await vi.advanceTimersByTimeAsync(2000);
    expect(Socket.instances).toHaveLength(4);
  });

  it('does not apply an old kick notification after a later successful join', async () => {
    const socket = resume(); socket.open();
    const release = deferred<Response>(); fetcher.mockImplementationOnce(() => release.promise);
    socket.frame({ type: 'bye', reason: 'kicked' });
    await client.joinParty('BRAVO2', 'http://localhost:43123');
    release.resolve(response(200, {}));
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: 'BRAVO2', error: null });
  });

  it('still reports a current kick and blocks rejoining that party', async () => {
    const socket = resume(); socket.open(); socket.frame({ type: 'bye', reason: 'kicked' });
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: null, error: 'The host removed you from the party.' });
    await expect(client.joinParty('ALPHA1')).rejects.toMatchObject({ code: 'kicked' });
  });

  it('drops frames from the replaced connection', async () => {
    const old = resume(); old.open();
    await client.joinParty('BRAVO2', 'http://localhost:43123');
    old.frame({ type: 'bye', reason: 'kicked' });
    old.frame({ type: 'state', playback: { seq: 999, positionMs: 999 } });
    expect(client.useParty.getState()).toMatchObject({ code: 'BRAVO2', playback: { seq: 0 }, error: null });
  });

  it('applies server playback/queue sequences and requests a missed queue', () => {
    const socket = resume(); socket.open();
    socket.frame({ type: 'queue', queue: { seq: 3, index: 0, items: [{ videoId: 'a', fromAutoplay: true }] } });
    socket.frame({ type: 'queue', queue: { seq: 2, index: -1, items: [] } });
    socket.frame({ type: 'state', playback: { seq: 5, queueSeq: 4, queueIndex: 0, track: { videoId: 'a' }, positionMs: 2500 } });
    socket.frame({ type: 'state', playback: { seq: 4, queueSeq: 3, track: { videoId: 'wrong' } } });
    expect(client.useParty.getState()).toMatchObject({ queue: { seq: 3, items: [{ videoId: 'a', fromAutoplay: true }] }, playback: { seq: 5, track: { videoId: 'a' }, positionMs: 2500 } });
    expect(socket.sent).toContainEqual({ type: 'syncQueue' });
  });

  it('preserves host-only control when an older members frame omits it', () => {
    const socket = resume(); socket.open();
    socket.frame({ type: 'welcome', party: { code: 'ALPHA1', hostOnlyControl: true, members: [member] }, you: { ...member, isHost: false } });
    socket.frame({ type: 'members', members: [{ ...member, isHost: false }] });
    expect(client.controlsLocked()).toBe(true);
    socket.frame({ type: 'members', members: [member], hostOnlyControl: false });
    expect(client.controlsLocked()).toBe(false);
  });

  it.each(['bad_token', 'no_such_party'])('honors terminal server refusal %s', async error => {
    const socket = resume(); socket.open(); socket.frame({ type: 'error', error, message: 'Refused' });
    await vi.advanceTimersByTimeAsync(0);
    expect(client.useParty.getState()).toMatchObject({ code: null, connection: 'offline' });
    expect(session.getItem('bitchord:party-session')).toBeNull();
  });
});
