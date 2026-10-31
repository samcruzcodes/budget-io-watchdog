import { describe, expect, it } from 'vitest';
import { compact, niceMax, ticks } from './scale';

describe('chart axes', () => {
  it('rounds the top of the axis up to a nice number', () => {
    expect(niceMax(7)).toBe(10);
    expect(niceMax(17)).toBe(20);
    expect(niceMax(23)).toBe(25);
    expect(niceMax(480)).toBe(500);
    expect(niceMax(1_000)).toBe(1_000);
    expect(niceMax(1_001)).toBe(2_000);
  });

  it('gives an empty chart an axis of 0 to 1', () => {
    expect(niceMax(0)).toBe(1);
  });

  it('spaces ticks evenly from zero', () => {
    expect(ticks(20)).toEqual([0, 5, 10, 15, 20]);
  });

  it('keeps ticks on whole numbers for counts', () => {
    expect(ticks(5, true)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(ticks(2, true)).toEqual([0, 1, 2]);
    expect(ticks(1, true)).toEqual([0, 1]);
    expect(ticks(20, true)).toEqual([0, 5, 10, 15, 20]);
    expect(ticks(25, true)).toEqual([0, 5, 10, 15, 20, 25]);
  });

  it('labels in full under 10,000 and compactly above', () => {
    expect(compact(2_500)).toBe('2,500');
    expect(compact(12_500)).toBe('12.5K');
  });
});
