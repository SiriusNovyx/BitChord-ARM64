import { describe, expect, it, vi } from 'vitest';

// Peak picking needs no model download or WASM session.
vi.mock('onnxruntime-web/wasm', () => ({ env: { wasm: {} } }));
import { pickPeaks } from './models';

describe('beat peak picking', () => {
  it('keeps a positive plateau as one beat at its centre', () => {
    for (let length = 2; length <= 9; length++) {
      const logits = new Float32Array(length + 8);
      logits.fill(3, 4, 4 + length);
      expect(pickPeaks(logits), `${length}-frame flat maximum`).toEqual([4 + (length - 1) / 2]);
    }
  });

  it('keeps equal peaks separated by lower values distinct', () => {
    const logits = new Float32Array(10);
    logits[2] = 3;
    logits[6] = 3;
    expect(pickPeaks(logits)).toEqual([2, 6]);
  });

  it('retains subframe interpolation for an isolated peak', () => {
    expect(pickPeaks(new Float32Array([0, 1, 4, 3, 0]))).toEqual([2.25]);
  });

  it('keeps boundary plateaus as one event', () => {
    expect(pickPeaks(new Float32Array([3, 3, 3, 0, 0]))).toEqual([1]);
    expect(pickPeaks(new Float32Array([0, 0, 3, 3, 3]))).toEqual([3]);
  });
});
