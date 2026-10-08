/** Grid ray traversal (Amanatides & Woo): the first solid cell a ray enters, and which face it came through. */

export interface RayHit {
  cell: [number, number, number];
  /** Outward normal of the face that was hit, e.g. [0, 1, 0] for the top. */
  normal: [number, number, number];
  distance: number;
}

export function raycast(
  origin: [number, number, number],
  direction: [number, number, number],
  maxDistance: number,
  solid: (x: number, y: number, z: number) => boolean,
): RayHit | null {
  const len = Math.hypot(direction[0], direction[1], direction[2]) || 1;
  const d = direction.map((v) => v / len) as [number, number, number];
  const cell = origin.map(Math.floor) as [number, number, number];
  const step = d.map((v) => (v > 0 ? 1 : v < 0 ? -1 : 0));
  const tDelta = d.map((v) => (v !== 0 ? Math.abs(1 / v) : Infinity));
  const tMax = d.map((v, i) => {
    if (v === 0) return Infinity;
    const boundary = v > 0 ? cell[i]! + 1 : cell[i]!;
    return (boundary - origin[i]!) / v;
  });
  let normal: [number, number, number] = [0, 0, 0];
  let t = 0;
  // Starting inside a solid cell counts as no hit, so the camera can sit inside a build.
  let started = !solid(cell[0], cell[1], cell[2]);
  for (let i = 0; i < 4096 && t <= maxDistance; i++) {
    if (started && solid(cell[0], cell[1], cell[2])) return { cell: [...cell], normal, distance: t };
    if (!started && !solid(cell[0], cell[1], cell[2])) started = true;
    const axis = tMax[0]! < tMax[1]! ? (tMax[0]! < tMax[2]! ? 0 : 2) : tMax[1]! < tMax[2]! ? 1 : 2;
    t = tMax[axis]!;
    tMax[axis]! += tDelta[axis]!;
    cell[axis]! += step[axis]!;
    normal = [0, 0, 0];
    normal[axis] = -step[axis]!;
  }
  return null;
}

/** Where a ray meets the horizontal plane y = level, if in front of the origin. */
export function rayPlaneY(origin: [number, number, number], direction: [number, number, number], level: number): [number, number, number] | null {
  if (Math.abs(direction[1]) < 1e-6) return null;
  const t = (level - origin[1]) / direction[1];
  if (t <= 0) return null;
  return [origin[0] + direction[0] * t, level, origin[2] + direction[2] * t];
}

/** Where a ray meets the plane through `point` with normal along `axis` (0 x, 1 y, 2 z). */
export function rayAxisPlane(origin: [number, number, number], direction: [number, number, number], axis: number, level: number): [number, number, number] | null {
  if (Math.abs(direction[axis]!) < 1e-6) return null;
  const t = (level - origin[axis]!) / direction[axis]!;
  if (t <= 0) return null;
  return [origin[0] + direction[0] * t, origin[1] + direction[1] * t, origin[2] + direction[2] * t];
}
