/** Block state strings: `minecraft:oak_stairs[facing=east,half=bottom]`. See docs/FORMAT_PAWPRINT.md §3. */

export interface ParsedState {
  id: string;
  props: Record<string, string>;
}

export function parseState(state: string): ParsedState {
  const bracket = state.indexOf('[');
  let id = bracket < 0 ? state : state.slice(0, bracket);
  if (!id.includes(':')) id = 'minecraft:' + id;
  const props: Record<string, string> = {};
  if (bracket >= 0) {
    const body = state.slice(bracket + 1, state.endsWith(']') ? -1 : undefined);
    for (const pair of body.split(',')) {
      const eq = pair.indexOf('=');
      if (eq > 0) props[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim();
    }
  }
  return { id, props };
}

export function formatState(id: string, props: Record<string, string>): string {
  const keys = Object.keys(props);
  return keys.length ? `${id}[${keys.map((k) => `${k}=${props[k]}`).join(',')}]` : id;
}
