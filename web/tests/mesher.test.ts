import { describe, expect, it } from 'vitest';
import { buildSection, VoxelMap, sectionKey } from '../src/core/mesh/mesher';
import { toMeshPalette } from '../src/core/mesh/prepare';
import { ModelBaker } from '../src/core/model/bake';

const unitRect = (): [number, number, number, number] => [0, 0, 1, 1];

function palette(states: string[]) {
  // Without a pack every block is a full colored cube, which is all these tests need.
  const baker = new ModelBaker(null);
  return toMeshPalette(states.map((s) => baker.bake(s)), unitRect);
}

describe('VoxelMap', () => {
  it('stores cells across sections, including negative coordinates', () => {
    const map = new VoxelMap();
    map.set(0, 0, 0, 1);
    map.set(15, 15, 15, 2);
    map.set(16, 0, 0, 3);
    map.set(-1, 0, 0, 4);
    expect([map.get(0, 0, 0), map.get(15, 15, 15), map.get(16, 0, 0), map.get(-1, 0, 0), map.get(1, 1, 1)]).toEqual([1, 2, 3, 4, 0]);
    expect([...map.sections.keys()].sort()).toEqual(['-1,0,0', '0,0,0', '1,0,0']);
  });
});

describe('buildSection', () => {
  it('hides faces between solid blocks, also across section borders', () => {
    const pal = palette(['minecraft:stone']);
    // Two blocks side by side across the border between sections 0 and 1.
    const map = VoxelMap.fromArrays(new Int32Array([15, 0, 0, 16, 0, 0]), new Int32Array([0, 0]));
    const a = buildSection(map, sectionKey(0, 0, 0), pal)!;
    const b = buildSection(map, sectionKey(1, 0, 0), pal)!;
    expect(a.quadCount).toBe(5);
    expect(b.quadCount).toBe(5);
    expect(a.layers[0]!.indices.length).toBe(5 * 6);
  });

  it('builds a 3×3×3 solid cube with only its outer faces', () => {
    const pal = palette(['minecraft:stone']);
    const positions: number[] = [];
    for (let x = 0; x < 3; x++) for (let y = 0; y < 3; y++) for (let z = 0; z < 3; z++) positions.push(x, y, z);
    const map = VoxelMap.fromArrays(new Int32Array(positions), new Int32Array(27));
    expect(buildSection(map, '0,0,0', pal)!.quadCount).toBe(6 * 9);
  });

  it('offsets positions inside the section and keeps colors', () => {
    const pal = palette(['minecraft:stone']);
    const map = VoxelMap.fromArrays(new Int32Array([20, 3, 4]), new Int32Array([0]));
    const mesh = buildSection(map, '1,0,0', pal)!;
    expect(mesh.origin).toEqual([16, 0, 0]);
    const xs = [...mesh.layers[0]!.positions].filter((_, i) => i % 3 === 0);
    expect(Math.min(...xs)).toBe(4);
    expect(Math.max(...xs)).toBe(5);
    expect(mesh.layers[0]!.colors.some((c) => c > 0)).toBe(true);
  });

  it('skips hidden blocks', () => {
    const map = VoxelMap.fromArrays(new Int32Array([0, 0, 0, 1, 0, 0]), new Int32Array([0, 0]), (i) => i === 1);
    expect(buildSection(map, '0,0,0', palette(['minecraft:stone']))!.quadCount).toBe(6);
  });
});
