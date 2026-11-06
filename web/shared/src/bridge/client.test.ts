import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { externalBridge, relayBridge } from './client';
import { errorResponse, okResponse, type BridgeRequest } from './protocol';

const ping = { version: 'test', protocol: 1, browser: 'chromium' as const, capabilities: [] };

function relayTarget() {
  const listeners = new Set<(event: MessageEvent) => void>();
  const target = {
    location: { origin: 'https://bitchord.test' },
    addEventListener: vi.fn((_type: string, listener: (event: MessageEvent) => void) => listeners.add(listener)),
    removeEventListener: vi.fn((_type: string, listener: (event: MessageEvent) => void) => listeners.delete(listener)),
    postMessage: vi.fn((_request: BridgeRequest, _origin: string) => {}),
  };
  const window = target as unknown as Window;
  const dispatch = (data: unknown, source: unknown = window, origin = target.location.origin) => {
    for (const listener of [...listeners]) listener({ source, origin, data } as MessageEvent);
  };
  return { target, window, listeners, dispatch };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('extension call lifecycle', () => {
  it('releases the deadline and abort listener when Chromium rejects dispatch synchronously', async () => {
    const failure = new Error('Extension context invalidated');
    vi.stubGlobal('chrome', { runtime: { sendMessage: () => { throw failure; } } });
    const controller = new AbortController();
    const remove = vi.spyOn(controller.signal, 'removeEventListener');

    await expect(externalBridge('extension')!.call('ping', {}, { signal: controller.signal })).rejects.toBe(failure);

    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('releases the message listener when relay dispatch throws', async () => {
    const relay = relayTarget();
    const failure = new DOMException('Cannot clone request', 'DataCloneError');
    relay.target.postMessage.mockImplementation(() => { throw failure; });
    const controller = new AbortController();
    const remove = vi.spyOn(controller.signal, 'removeEventListener');

    await expect(relayBridge(relay.window).call('ping', {}, { signal: controller.signal })).rejects.toBe(failure);

    expect(relay.listeners.size).toBe(0);
    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('cleans up after a cancellation delivered during dispatch', async () => {
    const relay = relayTarget();
    const controller = new AbortController();
    const reason = new Error('Track changed');
    relay.target.postMessage.mockImplementation(() => controller.abort(reason));

    await expect(relayBridge(relay.window).call('ping', {}, { signal: controller.signal })).rejects.toBe(reason);

    expect(relay.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not dispatch a request that was already cancelled', async () => {
    const relay = relayTarget();
    const controller = new AbortController();
    const reason = new Error('Obsolete');
    controller.abort(reason);

    await expect(relayBridge(relay.window).call('ping', {}, { signal: controller.signal })).rejects.toBe(reason);

    expect(relay.target.postMessage).not.toHaveBeenCalled();
    expect(relay.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('times out, removes its relay listener and ignores late replies', async () => {
    const relay = relayTarget();
    const pending = relayBridge(relay.window).call('ping', {}, { timeoutMs: 50 });
    const request = relay.target.postMessage.mock.calls[0]![0];
    const rejected = expect(pending).rejects.toMatchObject({ code: 'timeout' });
    await vi.advanceTimersByTimeAsync(50);
    await rejected;

    relay.dispatch(okResponse<'ping'>(request.id, ping));
    expect(relay.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('accepts only the matching reply from the same window and origin', async () => {
    const relay = relayTarget();
    const pending = relayBridge(relay.window).call('ping', {});
    const request = relay.target.postMessage.mock.calls[0]![0];
    relay.dispatch(okResponse<'ping'>(request.id, ping), {});
    relay.dispatch(okResponse<'ping'>(request.id, ping), relay.window, 'https://other.test');
    relay.dispatch(okResponse<'ping'>('unrelated', ping));
    expect(relay.listeners.size).toBe(1);
    relay.dispatch(okResponse<'ping'>(request.id, ping));

    await expect(pending).resolves.toEqual(ping);
    expect(relay.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('preserves provider refusal rather than treating it as a successful response', async () => {
    const relay = relayTarget();
    const pending = relayBridge(relay.window).call('yt.authState', {});
    const request = relay.target.postMessage.mock.calls[0]![0];
    relay.dispatch(errorResponse(request.id, 'unauthenticated', 'Sign in required'));

    await expect(pending).rejects.toMatchObject({ code: 'unauthenticated', message: 'Sign in required' });
    expect(relay.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('settles asynchronous Chromium replies and removes the abort listener', async () => {
    let reply: ((response: unknown) => void) | undefined;
    let request: BridgeRequest | undefined;
    vi.stubGlobal('chrome', { runtime: { sendMessage: (_id: string, sent: BridgeRequest, callback: typeof reply) => {
      request = sent;
      reply = callback;
    } } });
    const controller = new AbortController();
    const remove = vi.spyOn(controller.signal, 'removeEventListener');
    const pending = externalBridge('extension')!.call('ping', {}, { signal: controller.signal });
    reply!(okResponse<'ping'>(request!.id, ping));

    await expect(pending).resolves.toEqual(ping);
    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('cleans up a Chromium runtime failure reported through lastError', async () => {
    vi.stubGlobal('chrome', { runtime: {
      lastError: { message: 'Receiving end does not exist' },
      sendMessage: (_id: string, _request: unknown, callback: (response: unknown) => void) => callback(undefined),
    } });
    await expect(externalBridge('extension')!.call('ping', {})).rejects.toMatchObject({ code: 'unavailable' });
    expect(vi.getTimerCount()).toBe(0);
  });
});
