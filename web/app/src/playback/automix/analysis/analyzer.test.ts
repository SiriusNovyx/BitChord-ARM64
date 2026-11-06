import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TrackAnalysis } from './types';
import { getAnalysis, putAnalysis } from '../db';
import { fetchTrackAudio } from '../fetchAudio';

vi.mock('../db', () => ({ getAnalysis: vi.fn(), putAnalysis: vi.fn() }));
vi.mock('../fetchAudio', () => ({ fetchTrackAudio: vi.fn() }));
vi.mock('@/playback/trackLog', () => ({ trackLog: { i: vi.fn(), w: vi.fn() } }));
vi.mock('./models', () => ({ BEAT_SR: 22050, VOCAL_WINDOW_SEC: 8 }));

function result(version: number): TrackAnalysis {
  return { fileKey: 'revision-test', path: 'youtube:revision-test', tags: { title: 'Test', artist: 'Fixture' },
    durationSec: 60, bpm: 120, beatIntervalSec: .5, firstDownbeatSec: 0, gridConfidence: .8,
    camelot: null, keyName: null, keyConfidence: 0, energy: .5, energyCurve: [],
    introEndSec: null, outroStartSec: null, analysisVersion: version };
}

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  vi.useFakeTimers();
});
afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe('analysis peak-algorithm migration', () => {
  it('does not publish a grid from the previous algorithm as current evidence', async () => {
    vi.mocked(getAnalysis).mockResolvedValue(result(8));
    const { analyzer } = await import('./analyzer');
    analyzer.request('revision-test', result(8).tags);
    await Promise.resolve();
    expect(analyzer.get('revision-test')).toBeNull();
    expect(analyzer.statusOf('revision-test')).toBe('pending');
  });

  it('restores a corrected cached grid before requesting audio', async () => {
    const cached = result(9);
    vi.mocked(getAnalysis).mockResolvedValue(cached);
    const { analyzer } = await import('./analyzer');
    analyzer.request('revision-test', cached.tags);
    await Promise.resolve();
    expect(analyzer.get('revision-test')).toEqual(cached);
    expect(analyzer.statusOf('revision-test')).toBe('done');
    await vi.advanceTimersByTimeAsync(50);
    expect(fetchTrackAudio).not.toHaveBeenCalled();
  });

  it('preserves user grid overrides when replacing an old derived analysis', async () => {
    const cached = { ...result(8), manualOverrides: { bpm: 123, gridNudgeBeats: .25 } };
    vi.mocked(getAnalysis).mockResolvedValue(cached);
    const { analyzer } = await import('./analyzer');
    const compute = analyzer as unknown as { analyzeOne: () => Promise<TrackAnalysis> };
    vi.spyOn(compute, 'analyzeOne').mockResolvedValue(result(9));
    analyzer.request('revision-test', cached.tags);
    await vi.advanceTimersByTimeAsync(50);
    expect(analyzer.get('revision-test')?.analysisVersion).toBe(9);
    expect(analyzer.get('revision-test')?.manualOverrides).toEqual(cached.manualOverrides);
    expect(putAnalysis).toHaveBeenCalledWith(expect.objectContaining({
      analysisVersion: 9, manualOverrides: cached.manualOverrides,
    }));
  });
});
