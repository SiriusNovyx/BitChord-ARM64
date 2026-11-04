import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../analysis/analyzer', () => ({ analyzer: {get: vi.fn(() => null), request: vi.fn()} }));
import { AutoDJ, type AutomixHost } from './autodj';
import { Timeline } from './timeline';
import type { TrackAnalysis } from '../analysis/types';
import type { LoopMixPlan, LoopMixTimes } from './loopMix';
import type { LoopDeck } from './loopDeck';
import { atAudioTime } from './audioClock';

class AudioClock extends EventTarget {
  state: AudioContextState = 'running';
  private anchor = 0;
  private wall = Date.now();
  private stalled = false;
  reads = 0;
  listeners = new Set<EventListenerOrEventListenerObject>();
  get currentTime() {
    this.reads++;
    return this.anchor + (this.state === 'running' && !this.stalled ? (Date.now() - this.wall) / 1000 : 0);
  }
  setState(state: AudioContextState) {
    this.anchor = this.currentTime; this.wall = Date.now(); this.state = state;
    this.dispatchEvent(new Event('statechange'));
  }
  jump(seconds: number) { this.anchor = seconds; this.wall = Date.now(); }
  freezeWhileRunning() { this.anchor = this.currentTime; this.wall = Date.now(); this.stalled = true; }
  override addEventListener(type: string, listener: EventListenerOrEventListenerObject | null, options?: AddEventListenerOptions | boolean) {
    if (type === 'statechange' && listener) this.listeners.add(listener);
    super.addEventListener(type, listener, options);
  }
  override removeEventListener(type: string, listener: EventListenerOrEventListenerObject | null, options?: EventListenerOptions | boolean) {
    if (type === 'statechange' && listener) this.listeners.delete(listener);
    super.removeEventListener(type, listener, options);
  }
}

function param() {
  return {cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), setValueCurveAtTime: vi.fn(),
    linearRampToValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn()};
}
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
async function advanceTo(seconds: number) {
  await vi.advanceTimersByTimeAsync(Math.max(0, Math.ceil(seconds * 1000 - Date.now())));
}

async function fixture(loop = false) {
  const clock = new AudioClock();
  let active = 0;
  const deck = (input: number, paused: boolean) => {
    const timeline = new Timeline();
    timeline.add({output: 0, input, rate: 1, active: !paused});
    const audio = {currentTime: input, paused, playbackRate: 1, play: vi.fn(async () => {audio.paused = false;})};
    return {
      ctx: clock, audio, timeline, gain: {gain: param()}, durationSec: loop ? 120 : 60, scheduleAhead: 0.17,
      get isPlaying() { return !audio.paused; },
      get expectedTrackTime() { return timeline.trackTimeAt(clock.currentTime) ?? input; },
      anchor: vi.fn(), anchorPrecise: vi.fn(async () => {}),
      lock: vi.fn(), setVolume: vi.fn(), pauseAt: vi.fn(), setRate: vi.fn(),
      playAt: vi.fn(), cancel: vi.fn(), unfollow: vi.fn(), release: vi.fn(),
      eq: {reset: vi.fn(), setGainAt: vi.fn(), rampGain: vi.fn()},
      sweep: {highpass: {frequency: param()}, lowpass: {frequency: param()}},
    };
  };
  const decks = [deck(loop ? 60 : 49, loop), deck(0, true)] as const;
  const handoffs: number[] = [], completions: number[] = [];
  const handoff = vi.fn(() => { handoffs.push(clock.currentTime); active = 1; });
  const finished = vi.fn(() => { completions.push(clock.currentTime); });
  const host = {ctx: clock, front: () => decks[active]!, back: () => decks[1-active]!,
    current: () => ({videoId: active ? 'in' : 'out', title: '', artist: ''}),
    next: () => active ? null : {videoId: 'in', title: '', artist: ''},
    loadBack: vi.fn(async () => true), handoff, finished, hold: vi.fn(), sungSpans: vi.fn(), loopDecks: vi.fn(),
  } as unknown as AutomixHost;
  const dj = new AutoDJ(host);
  dj.start();
  await flush();
  const internal = dj as unknown as {
    currentKey: string | null; nextKey: string | null; loopPlan: LoopMixPlan | null;
    loopDecks: [LoopDeck, LoopDeck]; loopTimes: LoopMixTimes | null;
    scheduleLoop(cur: TrackAnalysis): Promise<void>; tick(): Promise<void>;
  };
  if (loop) {
    internal.currentKey = 'out'; internal.nextKey = 'in';
    internal.loopPlan = {
      out: {key: 'out', loop: [80,88], bars: 4, beatSec: 0.5, bpm: 120, vocal: 0, checkedBy: 'lyrics'},
      in: {key: 'in', loop: [0.5,0.5+16*60/140], bars: 4, beatSec: 60/140, bpm: 140, vocal: 0, checkedBy: 'lyrics'},
      fold: 1, enterBars: 4, rampBars: 8, exitBars: 2, totalBars: 14,
    };
    const worklet = () => ({schedule: vi.fn(), stop: vi.fn(), gain: {gain: param()},
      eq: {setGainAt: vi.fn(), rampGain: vi.fn()}, sweep: {highpass: {frequency: param()}}});
    internal.loopDecks = [worklet(),worklet()] as unknown as [LoopDeck,LoopDeck];
    // A paused media element still has a known fixture timeline at rate 1.
    decks[0].timeline.clear(); decks[0].timeline.add({output: 0,input: 60,rate: 1,active: true});
    await internal.scheduleLoop({fileKey: 'out'} as TrackAnalysis);
  }
  expect(dj.snapshot.state).toBe('scheduled');
  const plan = dj.snapshot.plan!;
  const midpoint = internal.loopTimes?.handoffAt ?? plan.t0 + plan.durationSec / 2;
  return {dj, clock, internal, decks, handoff, finished, handoffs, completions, midpoint,
    completion: plan.t0 + plan.durationSec + 0.3};
}

beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(0); });
afterEach(() => { vi.clearAllTimers(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe('AutoDJ audio-clock ownership boundaries', () => {
  it('hands off the fallback at its midpoint without waiting for the next one-second tick', async () => {
    const f = await fixture();
    await advanceTo(f.midpoint - 0.01);
    expect(f.handoff).not.toHaveBeenCalled();
    await advanceTo(f.midpoint + 0.02);
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.handoffs[0]).toBeCloseTo(f.midpoint, 2);
    f.dj.stop();
  });

  it('completes at the existing end guard rather than the next planning tick', async () => {
    const f = await fixture();
    await advanceTo(f.completion - 0.01);
    expect(f.finished).not.toHaveBeenCalled();
    await advanceTo(f.completion + 0.02);
    expect(f.finished).toHaveBeenCalledOnce();
    expect(f.completions[0]).toBeCloseTo(f.completion, 2);
    f.dj.stop();
  });

  it('uses the real loop handoff time for unequal tempos rather than half its total duration', async () => {
    const f = await fixture(true);
    const guessed = f.dj.snapshot.plan!.t0 + f.dj.snapshot.plan!.durationSec / 2;
    expect(Math.abs(f.midpoint - guessed)).toBeGreaterThan(0.5);
    await advanceTo(f.midpoint + 0.02);
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.handoffs[0]).toBeCloseTo(f.midpoint, 2);
    f.dj.stop();
  });

  it('cancels the old same-pair deadline when a replacement transition is scheduled', async () => {
    const f = await fixture();
    await advanceTo(1);
    f.dj.cancel(); f.dj.start(); await flush();
    const next = f.dj.snapshot.plan!;
    await advanceTo(f.midpoint + 0.02);
    expect(f.handoff).not.toHaveBeenCalled();
    await advanceTo(next.t0 + next.durationSec / 2 + 0.02);
    expect(f.handoff).toHaveBeenCalledOnce();
    f.dj.stop();
  });

  it('does not move ownership while the context is suspended and resumes on its audio clock', async () => {
    const f = await fixture();
    await advanceTo(1);
    f.clock.setState('suspended');
    await vi.advanceTimersByTimeAsync(20_000);
    expect(f.handoff).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(1); // Only the existing planning interval.
    f.clock.setState('running');
    await vi.advanceTimersByTimeAsync(Math.ceil((f.midpoint - f.clock.currentTime + 0.02) * 1000));
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.handoffs[0]).toBeCloseTo(f.midpoint, 2);
    f.dj.stop();
    expect(f.clock.listeners.size).toBe(0);
  });

  it('retires timers and clock listeners when the context is closed', async () => {
    const f = await fixture();
    f.clock.setState('closed');
    expect(f.dj.snapshot.state).toBe('idle');
    expect(vi.getTimerCount()).toBe(0);
    expect(f.clock.listeners.size).toBe(0);
    await vi.advanceTimersByTimeAsync(20_000);
    expect(f.handoff).not.toHaveBeenCalled();
  });

  it('does not spin if a running context stops advancing near the deadline', async () => {
    const f = await fixture();
    await advanceTo(f.midpoint - 0.005);
    f.clock.freezeWhileRunning();
    const reads = f.clock.reads;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(f.clock.reads - reads).toBeLessThan(300);
    expect(f.handoff).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBeLessThanOrEqual(2);
    f.dj.stop();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('keeps forced handoff single and still completes at the real end', async () => {
    const f = await fixture();
    await advanceTo(1);
    expect(f.dj.forceHandoff()).toBe(true);
    expect(f.dj.forceHandoff()).toBe(false);
    await advanceTo(f.completion + 0.02);
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.finished).toHaveBeenCalledOnce();
    f.dj.stop();
  });

  it('does not dereference the old plan after the host rejects handoff and cancels it', async () => {
    const f = await fixture();
    f.handoff.mockImplementationOnce(() => { f.dj.cancel(); });
    f.clock.jump(f.midpoint + 0.1);
    await expect(f.internal.tick()).resolves.toBeUndefined();
    expect(f.dj.snapshot.state).toBe('playing');
    expect(f.dj.snapshot.plan).toBeNull();
    expect(f.finished).not.toHaveBeenCalled();
    f.dj.stop();
  });

  it('does not overwrite a host stop issued during completion', async () => {
    const f = await fixture();
    f.finished.mockImplementationOnce(() => { f.dj.stop(); });
    f.clock.jump(f.completion + 0.1);
    await f.internal.tick();
    expect(f.dj.snapshot.state).toBe('idle');
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.finished).toHaveBeenCalledOnce();
  });

  it('retires a stopped transition without later promotion or a retained listener', async () => {
    const f = await fixture();
    f.dj.stop(); f.dj.stop();
    await vi.advanceTimersByTimeAsync(20_000);
    expect(f.handoff).not.toHaveBeenCalled();
    expect(f.finished).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
    expect(f.clock.listeners.size).toBe(0);
  });

  it('does not run an obsolete EQ or gain cleanup after the transition has stopped', async () => {
    const f = await fixture();
    f.dj.stop();
    await vi.advanceTimersByTimeAsync(20_000);
    expect(f.decks[0].eq.reset).not.toHaveBeenCalled();
    expect(f.decks[0].setVolume).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('settles an already-closed clock after the caller receives its cancellation handle', async () => {
    const clock = new AudioClock();
    clock.setState('closed');
    const action = vi.fn(), closed = vi.fn();
    const cancel = atAudioTime(clock as unknown as AudioContext, 0, action, closed);
    expect(closed).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(0);
    expect(closed).toHaveBeenCalledOnce();
    expect(action).not.toHaveBeenCalled();
    expect(clock.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    cancel();
  });

  it('catches up a late clock observation once without replaying a callback backlog', async () => {
    const f = await fixture();
    f.clock.jump(f.completion + 5);
    await vi.advanceTimersByTimeAsync(1000);
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.finished).toHaveBeenCalledOnce();
    f.dj.stop();
    await vi.advanceTimersByTimeAsync(1100);
    expect(f.clock.listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('respects cancellation issued by the host inside the precise deadline callback', async () => {
    const f = await fixture();
    f.handoff.mockImplementationOnce(() => { f.dj.cancel(); });
    await advanceTo(f.midpoint + 0.02);
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.dj.snapshot.plan).toBeNull();
    expect(f.dj.snapshot.state).toBe('playing');
    expect(f.clock.listeners.size).toBe(0);
    f.dj.stop();
  });

  it('respects a host stop inside the precise completion callback', async () => {
    const f = await fixture();
    f.finished.mockImplementationOnce(() => { f.dj.stop(); });
    await advanceTo(f.completion + 0.02);
    expect(f.handoff).toHaveBeenCalledOnce();
    expect(f.finished).toHaveBeenCalledOnce();
    expect(f.dj.snapshot.state).toBe('idle');
    expect(f.clock.listeners.size).toBe(0);
    await vi.advanceTimersByTimeAsync(1100);
    expect(vi.getTimerCount()).toBe(0);
  });
});
