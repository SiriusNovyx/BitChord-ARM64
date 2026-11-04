import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../analysis/analyzer', () => ({ analyzer: {
  get: vi.fn(() => null), request: vi.fn(), audioBytes: vi.fn(async () => new ArrayBuffer(8)),
} }));
import { analyzer } from '../analysis/analyzer';
import { AutoDJ, type AutomixHost, type QueuedTrack } from './autodj';
import type { TrackAnalysis } from '../analysis/types';
import type { LoopDeck } from './loopDeck';
import type { LoopMixPlan } from './loopMix';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

async function flush() {
  for (let i = 0; i < 16; i++) await Promise.resolve();
}

function analysis(fileKey: string): TrackAnalysis {
  return {
    fileKey, path: '', tags: { title: fileKey, artist: 'Fixture' }, durationSec: 120,
    bpm: 120, beatIntervalSec: 0.5, firstDownbeatSec: 0.5, gridConfidence: 0.8,
    camelot: null, keyName: null, keyConfidence: 0, energy: 0.5, energyCurve: [],
    introEndSec: 0, outroStartSec: 120, analysisVersion: 9,
  };
}

function fixture() {
  const samples = new Float32Array(120_000).fill(0.3);
  const buffer = { duration: 120, sampleRate: 1000, length: samples.length,
    numberOfChannels: 1, getChannelData: () => samples } as unknown as AudioBuffer;
  let delayDecode = false;
  const decodes: ReturnType<typeof deferred<AudioBuffer>>[] = [];
  const decode = vi.fn(async () => {
    if (!delayDecode) return buffer;
    const task = deferred<AudioBuffer>();
    decodes.push(task);
    return task.promise;
  });
  const loops = [
    { load: vi.fn(async () => {}), stop: vi.fn() },
    { load: vi.fn(async () => {}), stop: vi.fn() },
  ] as const;
  const makeDeck = () => ({ isPlaying: false, expectedTrackTime: 0 });
  const track = (videoId: string): QueuedTrack => ({ videoId, title: videoId, artist: 'Fixture' });
  let front = makeDeck();
  let back = makeDeck();
  let current = track('out');
  let next = track('in');
  const createDecks = vi.fn(async () => loops as unknown as [LoopDeck, LoopDeck]);
  const host = {
    ctx: { currentTime: 0, decodeAudioData: decode }, front: () => front, back: () => back,
    current: () => current, next: () => next, loopDecks: createDecks,
    sungSpans: vi.fn(async () => []), hold: vi.fn(), loadBack: vi.fn(), handoff: vi.fn(), finished: vi.fn(),
  } as unknown as AutomixHost;
  const dj = new AutoDJ(host);
  dj.start();
  // Delay actual external boundaries; planning and ownership remain production
  // implementations. The paused front keeps unrelated interval ticks idle.
  const preparation = dj as unknown as {
    currentKey: string | null; nextKey: string | null; loopPlan: LoopMixPlan | null;
    prepareLoopMix(current: TrackAnalysis, next: TrackAnalysis): Promise<void>;
  };
  const reselect = () => { preparation.currentKey = 'out'; preparation.nextKey = 'in'; };
  reselect();
  return {
    dj, decode, decodes, loops, createDecks, preparation,
    run: () => preparation.prepareLoopMix(analysis('out'), analysis('in')),
    reselect,
    pauseDecode: () => { delayDecode = true; },
    releaseDecode: () => { delayDecode = false; for (const task of decodes) task.resolve(buffer); },
    replaceCurrent: () => { current = track('other'); },
    replaceNext: () => { next = track('other'); },
    replaceFront: () => { front = makeDeck(); },
    replaceBack: () => { back = makeDeck(); },
  };
}

beforeEach(() => { vi.useFakeTimers(); vi.mocked(analyzer.audioBytes).mockClear(); });
afterEach(() => { vi.clearAllTimers(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe('AutoDJ asynchronous loop preparation', () => {
  it('does not fetch audio after cancellation during deck initialization', async () => {
    const f = fixture();
    const decks = deferred<[LoopDeck, LoopDeck]>();
    f.createDecks.mockReturnValueOnce(decks.promise);
    const pending = f.run();
    f.dj.cancel();
    decks.resolve(f.loops as unknown as [LoopDeck, LoopDeck]);
    await pending;

    expect(vi.mocked(analyzer.audioBytes).mock.calls.length).toBe(0);
    expect(f.decode.mock.calls.length).toBe(0);
    f.dj.stop();
  });

  it('does not start decoding when cancelled while audio bytes are pending', async () => {
    const f = fixture();
    const bytes = deferred<ArrayBuffer>();
    vi.mocked(analyzer.audioBytes).mockReturnValueOnce(bytes.promise);
    const pending = f.run();
    await flush();
    f.dj.cancel();
    bytes.resolve(new ArrayBuffer(8));
    await pending;

    expect(f.decode.mock.calls.length).toBe(0);
    expect(f.loops[0].load.mock.calls.length).toBe(0);
    f.dj.stop();
  });

  it('retires a cancelled same-pair decode before admitting its replacement', async () => {
    const f = fixture();
    f.pauseDecode();
    const obsolete = f.run();
    await flush();
    expect(f.decodes.length).toBe(2);
    f.dj.cancel();
    f.reselect();
    const premature = f.run();
    await flush();
    const decodesWhileStalled = f.decodes.length;
    f.releaseDecode();
    await Promise.all([obsolete, premature]);
    const stalePlan = f.preparation.loopPlan;
    const staleLoads = f.loops[0].load.mock.calls.length;
    await f.run();

    expect(decodesWhileStalled).toBe(2);
    expect(stalePlan).toBeNull();
    expect(staleLoads).toBe(0);
    expect(f.preparation.loopPlan).not.toBeNull();
    expect(f.loops[0].load).toHaveBeenCalledOnce();
    expect(f.loops[1].load).toHaveBeenCalledOnce();
    f.dj.stop();
  });

  it.each(['replaceCurrent', 'replaceNext', 'replaceFront', 'replaceBack'] as const)(
    'does not load loop buffers after %s during decoding', async change => {
      const f = fixture();
      f.pauseDecode();
      const pending = f.run();
      await flush();
      f[change]();
      f.releaseDecode();
      await pending;

      expect(f.loops[0].load.mock.calls.length).toBe(0);
      expect(f.loops[1].load.mock.calls.length).toBe(0);
      expect(f.preparation.loopPlan).toBeNull();
      f.dj.stop();
    },
  );

  it('does not load the incoming deck after cancellation during outgoing loading', async () => {
    const f = fixture();
    const loading = deferred<void>();
    f.loops[0].load.mockReturnValueOnce(loading.promise);
    const pending = f.run();
    await flush();
    expect(f.loops[0].load).toHaveBeenCalledOnce();
    f.dj.cancel();
    loading.resolve();
    await pending;

    expect(f.loops[1].load.mock.calls.length).toBe(0);
    expect(f.preparation.loopPlan).toBeNull();
    f.dj.stop();
  });

  it('does not accumulate work during twenty rapid same-pair cancellations', async () => {
    const f = fixture();
    f.pauseDecode();
    const pending = [f.run()];
    await flush();
    for (let i = 0; i < 20; i++) {
      f.dj.cancel();
      f.reselect();
      pending.push(f.run());
      await flush();
    }
    const decoderCalls = f.decode.mock.calls.length;
    f.releaseDecode();
    await Promise.all(pending);

    expect(decoderCalls).toBe(2);
    expect(f.loops[0].load.mock.calls.length).toBe(0);
    expect(f.loops[1].load.mock.calls.length).toBe(0);
    expect(f.dj.snapshot.state).toBe('playing');
    f.dj.stop();
  });

  it('prepares a healthy pair using the real loop-selection algorithm', async () => {
    const f = fixture();
    await f.run();

    expect(f.preparation.loopPlan?.out.key).toBe('out');
    expect(f.preparation.loopPlan?.in.key).toBe('in');
    expect(f.loops[0].load).toHaveBeenCalledOnce();
    expect(f.loops[1].load).toHaveBeenCalledOnce();
    f.dj.stop();
  });

  it('can retry after a deck loading failure without retaining the preparation slot', async () => {
    const f = fixture();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    f.loops[0].load.mockRejectedValueOnce(new Error('Worklet rejected replacement'));
    await f.run();
    expect(f.preparation.loopPlan).toBeNull();
    await f.run();

    expect(f.preparation.loopPlan?.in.key).toBe('in');
    expect(f.loops[0].load).toHaveBeenCalledTimes(2);
    expect(f.loops[1].load).toHaveBeenCalledOnce();
    f.dj.stop();
  });
});
