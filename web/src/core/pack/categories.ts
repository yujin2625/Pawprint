/**
 * Palette categories for packs made from game files. Creative tabs exist only in code, so blocks are grouped by
 * the block tags mods ship as data (`data/<ns>/tags/block/…`). See docs/FORMAT_PAWPACK.md §8.2.
 */

/** Category → the block tags that put a block in it. The first category that matches comes first in `tabs`. */
const CATEGORY_TAGS: [category: string, tags: string[]][] = [
  ['stairs', ['minecraft:stairs']],
  ['slabs', ['minecraft:slabs']],
  ['walls', ['minecraft:walls']],
  ['fences', ['minecraft:fences', 'minecraft:fence_gates', 'c:fences', 'c:fence_gates', 'forge:fences', 'forge:fence_gates']],
  ['doors', ['minecraft:doors', 'minecraft:trapdoors']],
  ['planks', ['minecraft:planks']],
  ['logs', ['minecraft:logs']],
  ['leaves', ['minecraft:leaves']],
  ['wool', ['minecraft:wool', 'minecraft:wool_carpets', 'minecraft:carpets']],
  ['terracotta', ['minecraft:terracotta', 'c:glazed_terracottas', 'c:glazed_terracotta']],
  ['concrete', ['c:concretes', 'c:concrete', 'c:concrete_powders', 'c:concrete_powder', 'minecraft:concrete_powder']],
  ['glass', ['c:glass_blocks', 'c:glass_panes', 'forge:glass', 'forge:glass_panes', 'minecraft:impermeable']],
  ['stone', [
    'minecraft:base_stone_overworld', 'minecraft:base_stone_nether', 'minecraft:stone_bricks', 'c:stones', 'c:cobblestones',
    'c:sandstone/blocks', 'c:end_stones', 'c:netherracks', 'forge:stone', 'forge:cobblestone', 'forge:sandstone',
  ]],
  ['ores', [
    'c:ores', 'forge:ores', 'minecraft:coal_ores', 'minecraft:copper_ores', 'minecraft:iron_ores', 'minecraft:gold_ores',
    'minecraft:redstone_ores', 'minecraft:lapis_ores', 'minecraft:diamond_ores', 'minecraft:emerald_ores',
  ]],
  ['metal', ['c:storage_blocks', 'forge:storage_blocks', 'minecraft:beacon_base_blocks']],
  ['terrain', ['minecraft:dirt', 'minecraft:sand', 'minecraft:snow', 'minecraft:ice', 'minecraft:nylium', 'c:gravels', 'c:sands']],
  ['plants', ['minecraft:flowers', 'minecraft:small_flowers', 'minecraft:tall_flowers', 'minecraft:saplings', 'minecraft:crops', 'minecraft:corals', 'minecraft:wall_corals']],
  ['lights', ['minecraft:candles', 'minecraft:campfires', 'minecraft:candle_cakes']],
  ['signs', ['minecraft:all_signs', 'minecraft:signs', 'minecraft:banners']],
  ['redstone', ['minecraft:buttons', 'minecraft:pressure_plates', 'minecraft:rails']],
  ['decoration', [
    'minecraft:beds', 'minecraft:shulker_boxes', 'minecraft:anvil', 'minecraft:cauldrons', 'minecraft:flower_pots',
    'c:chests', 'c:barrels', 'c:bookshelves', 'forge:chests', 'forge:barrels', 'forge:bookshelves',
  ]],
];

export const CATEGORIES = CATEGORY_TAGS.map(([category]) => category);

/** `tabs` ID of an inferred category. Real creative tabs never use this namespace. */
export const categoryTab = (category: string): string => 'pawprint:' + category;

/**
 * Block ID → the categories its tags put it in. `tags` maps a tag ID to its entries as written in the data files:
 * block IDs and `#other_tag` references, already merged across packs.
 */
export function categorize(tags: Record<string, string[]>): Map<string, string[]> {
  const resolved = new Map<string, Set<string>>();
  const resolve = (tag: string, trail: Set<string>): Set<string> => {
    const known = resolved.get(tag);
    if (known) return known;
    const out = new Set<string>();
    if (trail.has(tag)) return out;
    trail.add(tag);
    for (const entry of tags[tag] ?? []) {
      if (entry.startsWith('#')) for (const id of resolve(withNamespace(entry.slice(1)), trail)) out.add(id);
      else out.add(withNamespace(entry));
    }
    trail.delete(tag);
    resolved.set(tag, out);
    return out;
  };
  const byBlock = new Map<string, string[]>();
  for (const [category, tagIds] of CATEGORY_TAGS) {
    const tab = categoryTab(category);
    for (const tag of tagIds) {
      for (const block of resolve(tag, new Set())) {
        const list = byBlock.get(block) ?? [];
        if (!list.includes(tab)) list.push(tab);
        byBlock.set(block, list);
      }
    }
  }
  return byBlock;
}

/** For blocks no tag describes: the category their name ends in. Many decorative mod blocks are not tagged. */
const CATEGORY_NAMES: [category: string, name: RegExp][] = [
  ['stairs', /_stairs$/],
  ['slabs', /_slab$/],
  ['walls', /_wall$/],
  ['fences', /_fence(_gate)?$/],
  ['doors', /_(trap)?door$/],
  ['planks', /_planks$/],
  ['logs', /_(log|wood|stem|hyphae)$/],
  ['leaves', /_leaves$/],
  ['wool', /_(wool|carpet)$/],
  ['terracotta', /terracotta$/],
  ['concrete', /_concrete(_powder)?$/],
  ['glass', /(^|_)glass(_pane)?$/],
  ['ores', /_ore$/],
  ['stone', /(_bricks?|(^|_)(cobble|sand|black|end_)?stone|deepslate|granite|diorite|andesite|tuff|basalt|calcite)$/],
  ['plants', /(_sapling|_flower|_mushroom|_roots|_bush)$/],
  ['lights', /(lantern|torch|lamp|candle|campfire)$/],
  ['signs', /_(sign|banner)$/],
];

/** `path` is the block ID without its namespace. */
export function categoryByName(path: string): string[] {
  const last = path.split('/').pop()!;
  const found = CATEGORY_NAMES.find(([, name]) => name.test(last));
  return found ? [categoryTab(found[0])] : [];
}

function withNamespace(id: string): string {
  return id.includes(':') ? id : 'minecraft:' + id;
}

const TAG_FILE = /^data\/([a-z0-9_.-]+)\/tags\/blocks?\/([a-z0-9_./-]+)\.json$/;

/** `data/<ns>/tags/block/<path>.json` (or `tags/blocks/` before 1.21) → `<ns>:<path>`. */
export function tagIdOf(file: string): string | null {
  const m = TAG_FILE.exec(file);
  return m ? `${m[1]}:${m[2]}` : null;
}

/**
 * Adds one tag file to the merged entries, as the game does: files of later packs add to earlier ones, unless they
 * say `"replace": true`. Call in pack order, lowest priority first.
 */
export function mergeTagFile(into: Record<string, string[]>, tag: string, json: unknown): void {
  const file = json as { replace?: unknown; values?: unknown } | null;
  if (!file || !Array.isArray(file.values)) return;
  const list = file.replace === true ? [] : (into[tag] ?? []);
  for (const value of file.values) {
    const id = typeof value === 'string' ? value : (value as { id?: unknown } | null)?.id;
    if (typeof id === 'string' && !list.includes(id)) list.push(id);
  }
  into[tag] = list;
}
