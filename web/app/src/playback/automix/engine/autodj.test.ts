import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../analysis/analyzer', () => ({ analyzer: { get: vi.fn(() => null), request: vi.fn() } }));
import { AutoDJ, type AutomixHost, type QueuedTrack } from './autodj';
import type { TrackAnalysis } from '../analysis/types';
import type { LoopDeck } from './loopDeck';
import type { LoopMixPlan } from './loopMix';

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
}

function parameter() {
  return { cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn(),
    exponentialRampToValueAtTime: vi.fn() };
}

function loopDeck() {
  return {
    schedule: vi.fn(), stop: vi.fn(), gain: { gain: parameter() },
    eq: { setGainAt: vi.fn(), rampGain: vi.fn() },
    sweep: { highpass: { frequency: parameter() }, lowpass: { frequency: parameter() } },
  };
}

function fixture() {
  const anchoring = deferred();
  const deck = () => ({
    // start() enters the normal playing state but leaves this focused schedule
    // test in control of the delayed native element-clock observation.
    isPlaying: false, audio: { currentTime: 80, play: vi.fn(async () => {}) },
    gain: { gain: parameter() }, eq: { reset: vi.fn() },
    timeline: { ctxTimeAt: vi.fn(() => 20) }, anchorPrecise: vi.fn(() => anchoring.promise),
    pauseAt: vi.fn(), playAt: vi.fn(), lock: vi.fn(), cancel: vi.fn(),
  });
  const outgoing = deck();
  const incoming = deck();
  const track = (videoId: string): QueuedTrack => ({ videoId, title: videoId, artist: 'Fixture' });
  let current = track('out');
  let next = track('in');
  let front = outgoing;
  let back = incoming;
  const loops = [loopDeck(), loopDeck()] as const;
  const hold = vi.fn();
  const ctx = Object.assign(new EventTarget(), {currentTime: 0, state: 'running'});
  const host = {
    ctx, front: () => front, back: () => back,
    current: () => current, next: () => next, hold,
    loadBack: vi.fn(), handoff: vi.fn(), finished: vi.fn(), loopDecks: vi.fn(), sungSpans: vi.fn(),
  } as unknown as AutomixHost;
  const dj = new AutoDJ(host);
  dj.start();
  const plan: LoopMixPlan = {
    out: { key: 'out', loop: [80, 88], bars: 4, beatSec: 0.5, bpm: 120, vocal: 0, checkedBy: 'lyrics' },
    in: { key: 'in', loop: [0, 8], bars: 4, beatSec: 0.5, bpm: 120, vocal: 0, checkedBy: 'lyrics' },
    fold: 1, enterBars: 4, rampBars: 2, exitBars: 2, totalBars: 8,
  };
  // Isolate the actual scheduling branch after preparation: no model/network
  // mock can replace the ownership checks inside AutoDJ.scheduleLoop().
  const scheduling = dj as unknown as {
    loopPlan: LoopMixPlan | null; loopDecks: [LoopDeck, LoopDeck];
    scheduleLoop(analysis: TrackAnalysis): Promise<void>;
  };
  scheduling.loopPlan = plan;
  scheduling.loopDecks = loops as unknown as [LoopDeck, LoopDeck];
  const cur = { fileKey: 'out' } as TrackAnalysis;
  return {
    dj, loops, hold, outgoing, incoming, anchoring,
    run: () => scheduling.scheduleLoop(cur),
    replaceCurrent: () => { current = track('replacement'); },
    replaceNext: () => { next = track('replacement'); },
    replaceFront: () => { front = deck(); },
    replaceBack: () => { back = deck(); },
    clearPlan: () => { scheduling.loopPlan = null; },
  };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); });

describe('AutoDJ delayed loop scheduling ownership', () => {
  it('does not resurrect a stopped AutoDJ after the deck anchor finishes', async () => {
    const f = fixture();
    const pending = f.run();
    f.dj.stop();
    f.anchoring.resolve();
    await pending;

    expect(f.dj.snapshot.state).toBe('idle');
    expect(f.loops[0].schedule.mock.calls.length).toBe(0);
    expect(f.hold).not.toHaveBeenCalledWith(true);
  });

  it('does not revive a cancelled same-pair transition after stop and restart', async () => {
    const f = fixture();
    const pending = f.run();
    f.dj.stop();
    f.dj.start();
    f.anchoring.resolve();
    await pending;

    expect(f.dj.snapshot.state).toBe('playing');
    expect(f.loops[0].schedule.mock.calls.length).toBe(0);
    expect(f.hold).not.toHaveBeenCalledWith(true);
    f.dj.stop();
  });

  it.each(['replaceCurrent', 'replaceNext', 'replaceFront', 'replaceBack', 'clearPlan'] as const)(
    'ignores the stale anchor after %s', async change => {
      const f = fixture();
      const pending = f.run();
      f[change]();
      f.anchoring.resolve();
      await pending;

      expect(f.loops[0].schedule.mock.calls.length).toBe(0);
      expect(f.loops[1].schedule.mock.calls.length).toBe(0);
      expect(f.outgoing.pauseAt).not.toHaveBeenCalled();
      expect(f.incoming.playAt).not.toHaveBeenCalled();
      expect(f.hold).not.toHaveBeenCalledWith(true);
      expect(f.dj.snapshot.state).toBe('playing');
      f.dj.stop();
    },
  );

  it('still schedules both decks when the anchored pair remains current', async () => {
    const f = fixture();
    const pending = f.run();
    f.anchoring.resolve();
    await pending;

    expect(f.dj.snapshot.state).toBe('scheduled');
    expect(f.loops[0].schedule).toHaveBeenCalledOnce();
    expect(f.loops[1].schedule).toHaveBeenCalledOnce();
    expect(f.outgoing.pauseAt).toHaveBeenCalledOnce();
    expect(f.incoming.playAt).toHaveBeenCalledOnce();
    expect(f.hold).toHaveBeenCalledWith(true);
    f.dj.stop();
  });
});
