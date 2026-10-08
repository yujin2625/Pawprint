/** 2D shapes on a slice, as lists of [u, v] cells. Shared by the 2D view and (later) 3D tools on a face plane. */

export type Cell2 = [number, number];
export type BrushShape = 'square' | 'circle' | 'diamond';

/** Cells covered by a brush of the given size centered on (u, v). */
export function brush(u: number, v: number, shape: BrushShape, size: number): Cell2[] {
  const out: Cell2[] = [];
  const r = (size - 1) / 2;
  const lo = -Math.floor(r), hi = Math.ceil(r);
  for (let dv = lo; dv <= hi; dv++) {
    for (let du = lo; du <= hi; du++) {
      // Offsets are measured from the brush center, which sits between cells for even sizes.
      const cu = du - (size % 2 === 0 ? 0.5 : 0), cv = dv - (size % 2 === 0 ? 0.5 : 0);
      const inside =
        shape === 'square' ? true : shape === 'circle' ? cu * cu + cv * cv <= (r + 0.35) * (r + 0.35) : Math.abs(cu) + Math.abs(cv) <= r + 0.01;
      if (inside) out.push([u + du, v + dv]);
    }
  }
  return out;
}

/** Bresenham line from a to b, both ends included. */
export function line(a: Cell2, b: Cell2): Cell2[] {
  const out: Cell2[] = [];
  let [x, y] = a;
  const [x1, y1] = b;
  const dx = Math.abs(x1 - x), dy = -Math.abs(y1 - y);
  const sx = x < x1 ? 1 : -1, sy = y < y1 ? 1 : -1;
  let err = dx + dy;
  for (;;) {
    out.push([x, y]);
    if (x === x1 && y === y1) break;
    const e2 = 2 * err;
    if (e2 >= dy) (err += dy), (x += sx);
    if (e2 <= dx) (err += dx), (y += sy);
  }
  return out;
}

/** A line drawn with a brush: every point stamped, duplicates removed. */
export function strokeLine(a: Cell2, b: Cell2, shape: BrushShape, size: number): Cell2[] {
  return unique(line(a, b).flatMap(([u, v]) => (size <= 1 ? [[u, v] as Cell2] : brush(u, v, shape, size))));
}

export function rect(a: Cell2, b: Cell2, filled: boolean, thickness = 1): Cell2[] {
  const u0 = Math.min(a[0], b[0]), u1 = Math.max(a[0], b[0]);
  const v0 = Math.min(a[1], b[1]), v1 = Math.max(a[1], b[1]);
  const out: Cell2[] = [];
  for (let v = v0; v <= v1; v++) {
    for (let u = u0; u <= u1; u++) {
      const edge = u - u0 < thickness || u1 - u < thickness || v - v0 < thickness || v1 - v < thickness;
      if (filled || edge) out.push([u, v]);
    }
  }
  return out;
}

/** Ellipse inside the box from a to b. The outline is the filled shape minus its inside. */
export function ellipse(a: Cell2, b: Cell2, filled: boolean, thickness = 1): Cell2[] {
  const u0 = Math.min(a[0], b[0]), u1 = Math.max(a[0], b[0]);
  const v0 = Math.min(a[1], b[1]), v1 = Math.max(a[1], b[1]);
  const cu = (u0 + u1) / 2, cv = (v0 + v1) / 2;
  const ru = (u1 - u0) / 2 + 0.5, rv = (v1 - v0) / 2 + 0.5;
  const inside = (u: number, v: number, shrink: number) => {
    const a2 = Math.max(0.01, ru - shrink), b2 = Math.max(0.01, rv - shrink);
    return ((u - cu) / a2) ** 2 + ((v - cv) / b2) ** 2 <= 1;
  };
  const out: Cell2[] = [];
  for (let v = v0; v <= v1; v++) {
    for (let u = u0; u <= u1; u++) {
      if (!inside(u, v, 0)) continue;
      if (filled || !inside(u, v, thickness)) out.push([u, v]);
    }
  }
  return out;
}

/**
 * Cells connected to the start (4 directions) that hold the same value, within the given box and up to `limit`
 * cells. Returns null when the area runs past the limit (e.g. filling open space).
 */
export function flood(
  start: Cell2,
  valueAt: (u: number, v: number) => number,
  box: { u0: number; v0: number; u1: number; v1: number },
  limit = 65536,
): Cell2[] | null {
  const target = valueAt(start[0], start[1]);
  const seen = new Set<string>();
  const out: Cell2[] = [];
  const stack: Cell2[] = [start];
  while (stack.length) {
    const [u, v] = stack.pop()!;
    if (u < box.u0 || u > box.u1 || v < box.v0 || v > box.v1) continue;
    const k = u + ',' + v;
    if (seen.has(k) || valueAt(u, v) !== target) continue;
    seen.add(k);
    out.push([u, v]);
    if (out.length > limit) return null;
    stack.push([u + 1, v], [u - 1, v], [u, v + 1], [u, v - 1]);
  }
  return out;
}

export function unique(cells: Cell2[]): Cell2[] {
  const seen = new Set<string>();
  return cells.filter(([u, v]) => {
    const k = u + ',' + v;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

/** How 2D slice coordinates map to the world. Screen u goes right, v goes down. */
export type Plane = 'y' | 'z' | 'x';

/**
 * Plane 'y': floor plan seen from above (u = x, v = z, north up).
 * Plane 'z': front elevation looking north (u = x, v = -y).
 * Plane 'x': side elevation looking west (u = -z, v = -y).
 */
export function toWorld(plane: Plane, slice: number, u: number, v: number): [number, number, number] {
  switch (plane) {
    case 'y': return [u, slice, v];
    case 'z': return [u, -v, slice];
    case 'x': return [slice, -v, -u];
  }
}

export function toSlice(plane: Plane, x: number, y: number, z: number): { slice: number; u: number; v: number } {
  switch (plane) {
    case 'y': return { slice: y, u: x, v: z };
    case 'z': return { slice: z, u: x, v: -y };
    case 'x': return { slice: x, u: -z, v: -y };
  }
}
