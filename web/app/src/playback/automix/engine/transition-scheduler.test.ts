import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { scheduleTransition } from './transition-scheduler';
import { Timeline } from './timeline';
import type { Deck } from './deck';
import type { TrackAnalysis } from '../analysis/types';

function parameter() {
  const events: { time: number; value: number | Float32Array; duration?: number }[] = [];
  return {
    cancelScheduledValues(time: number) {
      for (let i = events.length - 1; i >= 0; i--) if (events[i]!.time >= time) events.splice(i, 1);
    },
    setValueAtTime(value: number, time: number) { events.push({ time, value }); },
    setValueCurveAtTime(value: Float32Array, time: number, duration: number) { events.push({ time, value, duration }); },
    exponentialRampToValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn(),
    at(time: number) {
      const event = events.filter(event => event.time <= time).sort((a, b) => a.time - b.time).at(-1);
      if (!event) return 1;
      if (typeof event.value === 'number') return event.value;
      const position = Math.min(event.value.length - 1, (time - event.time) / event.duration! * (event.value.length - 1));
      const left = Math.floor(position), right = Math.ceil(position);
      return event.value[left]! + (event.value[right]! - event.value[left]!) * (position - left);
    },
  };
}

function analysis(fileKey: string, hasGrid: boolean): TrackAnalysis {
  return { fileKey, path: '', tags: {title: fileKey, artist: 'Fixture'}, durationSec: 60,
    bpm: hasGrid ? 120 : null, beatIntervalSec: hasGrid ? 0.5 : null, firstDownbeatSec: hasGrid ? 0.5 : null,
    gridConfidence: hasGrid ? 0.8 : 0, camelot: null, keyName: null, keyConfidence: 0,
    energy: 0.5, energyCurve: [], introEndSec: 0, outroStartSec: 60, analysisVersion: 9 };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); });

describe('incoming deck gain before its scheduled transition', () => {
  it.each([
    ['crossfade', 0.04], ['crossfade', 0.12], ['crossfade', 0.3],
    ['blend', 0.04], ['blend', 0.12], ['blend', 0.3],
    ['filter', 0.04], ['filter', 0.12], ['filter', 0.3],
  ] as const)('%s holds the incoming deck silent during a %s second start lead', (style, latency) => {
    const now = 100;
    const ctx = Object.assign(new EventTarget(), {currentTime: now, state: 'running'});
    const makeDeck = () => {
      const gain = parameter();
      const timeline = new Timeline();
      timeline.add({output: now, input: 10, rate: 1, active: true});
      let startsAt = Infinity;
      const deck = {ctx, gain: {gain}, timeline, expectedTrackTime: 10,
        scheduleAhead: latency + 0.05,
        playAt({ctxTime}: {ctxTime: number}) { startsAt = ctxTime - latency; },
        pauseAt: vi.fn(), setRate: vi.fn(), setVolume: vi.fn(),
        eq: {reset: vi.fn(), setGainAt: vi.fn(), rampGain: vi.fn()},
        sweep: {highpass: {frequency: parameter()}, lowpass: {frequency: parameter()}},
      } as unknown as Deck;
      return {deck, gain, startsAt: () => startsAt};
    };
    const from = makeDeck(), to = makeDeck();
    const plan = scheduleTransition({from: from.deck, to: to.deck,
      fromAnalysis: analysis('out', style !== 'crossfade'), toAnalysis: analysis('in', style === 'blend'),
      bars: 4, forceNow: true});

    expect(plan.style).toBe(style);
    expect(to.startsAt()).toBeLessThan(plan.t0);
    expect(to.gain.at(to.startsAt())).toBe(0);
    expect(to.gain.at(plan.t0 - 0.03)).toBe(0);
    expect(to.gain.at(plan.t0)).toBe(0);
    expect(from.gain.at(plan.t0)).toBe(1);
    expect(to.gain.at(plan.t0 + plan.durationSec)).toBeCloseTo(1, 6);
    expect(from.gain.at(plan.t0 + plan.durationSec)).toBeCloseTo(0, 6);
  });
});
