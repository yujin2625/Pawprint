/** Resource locations in resource-pack layout, shared by the pack generator and the model resolver. */

export function splitLocation(ref: string): [namespace: string, path: string] {
  const colon = ref.indexOf(':');
  return colon < 0 ? ['minecraft', ref] : [ref.slice(0, colon), ref.slice(colon + 1)];
}

export function blockstatePath(blockId: string): string {
  const [ns, path] = splitLocation(blockId);
  return `assets/${ns}/blockstates/${path}.json`;
}

export function modelPath(ref: string): string {
  const [ns, path] = splitLocation(ref);
  return `assets/${ns}/models/${path}.json`;
}

export function texturePath(ref: string): string {
  const [ns, path] = splitLocation(ref);
  return `assets/${ns}/textures/${path}.png`;
}

/** Model references used by a blockstate file (`variants` or `multipart`, single or weighted list). */
export function blockstateModels(blockstate: unknown): string[] {
  const out = new Set<string>();
  const add = (apply: unknown): void => {
    for (const entry of Array.isArray(apply) ? apply : [apply]) {
      const model = (entry as { model?: unknown } | null)?.model;
      if (typeof model === 'string') out.add(model);
    }
  };
  const state = blockstate as { variants?: Record<string, unknown>; multipart?: { apply?: unknown }[] };
  if (state?.variants && typeof state.variants === 'object') Object.values(state.variants).forEach(add);
  if (Array.isArray(state?.multipart)) state.multipart.forEach((part) => add(part?.apply));
  return [...out];
}
