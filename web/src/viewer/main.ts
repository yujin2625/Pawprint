/**
 * The single-file HTML viewer: one blueprint in 3D, with its block pack subset, in a page that needs no install or
 * network. The editor writes the data into `<script id="pawprint-data">` (see html.ts) next to this bundle.
 */
import { EditableBlueprint } from '../core/blueprint/editable';
import { readPawprint } from '../core/format/pawprint';
import { blockName, readPawpack } from '../core/pack/pawpack';
import { countMaterials, stacks } from '../core/materials/materials';
import { BlockResources } from '../render/resources';
import { Viewport } from '../render/viewport';
import MesherWorker from '../workers/mesher.worker.ts?worker&inline';
import type { ViewerData } from './html';

const TEXT = {
  en: {
    blocks: '{n} blocks', materials: 'Materials', layers: 'Layers', hide: 'Hide', made: 'Made with Pawprint', noPack: 'No block pack: blocks show as colored boxes.',
    help: 'Right-drag look · Right + WASD move · Q/E down/up · Wheel forward/back · Middle-drag pan · F fit', stacks: '{s}×64 + {r}', failed: 'This file could not be read.',
  },
  ko: {
    blocks: '블럭 {n}개', materials: '재료', layers: '레이어', hide: '숨기기', made: 'Pawprint로 만듦', noPack: '블럭 팩이 없어 블럭이 색 상자로 보여요.',
    help: '우클릭 드래그 둘러보기 · 우클릭+WASD 이동 · Q/E 위아래 · 휠 앞뒤 · 휠 클릭 드래그 평행 이동 · F 맞추기', stacks: '{s}×64 + {r}', failed: '이 파일을 읽지 못했어요.',
  },
};

function bytes(base64: string): Uint8Array {
  const raw = atob(base64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, props: Partial<HTMLElementTagNameMap[K]> = {}, ...children: (Node | string)[]): HTMLElementTagNameMap[K] {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
}

async function start(): Promise<void> {
  const data = JSON.parse(document.getElementById('pawprint-data')!.textContent!) as ViewerData;
  const text = TEXT[data.lang === 'ko' ? 'ko' : 'en'];
  const fill = (s: string, p: Record<string, string | number>) => s.replace(/\{(\w+)\}/g, (_, k: string) => String(p[k] ?? ''));
  const app = document.getElementById('app')!;
  try {
    const bp = EditableBlueprint.fromBlueprint(readPawprint(bytes(data.blueprint)));
    const pack = data.pack ? readPawpack(bytes(data.pack)) : null;
    const lang = data.lang === 'ko' ? 'ko_kr' : 'en_us';
    const nameOf = (id: string) => (pack ? blockName(pack.languages, id, lang) : id);

    const view = el('div', { className: 'view' });
    app.append(view);
    Viewport.createWorker = () => new MesherWorker();
    const resources = new BlockResources(pack, 4096);
    const viewport = new Viewport(view, resources);
    await viewport.show(bp);

    // Side panel: name, size, materials, layers.
    const b = bp.bounds();
    const size = b ? `${b.max[0] - b.min[0] + 1} × ${b.max[1] - b.min[1] + 1} × ${b.max[2] - b.min[2] + 1}` : '';
    const blocks = pack ? new Map(pack.blocks.map((d) => [d.id, d])) : null;
    const materials = countMaterials(bp, () => true, blocks);
    const panel = el('aside', { className: 'panel' },
      el('h1', {}, bp.meta.name || 'Blueprint'),
      el('p', { className: 'muted' }, `${size} · ${fill(text.blocks, { n: bp.toBlueprint().states.length.toLocaleString() })}`),
    );
    if (!pack) panel.append(el('p', { className: 'muted' }, text.noPack));

    const hidden = new Set<number>();
    const groups = bp.layers.filter((l) => !l.group);
    if (groups.length > 1) {
      const list = el('div', { className: 'list' });
      for (const layer of groups) {
        const box = el('input', { type: 'checkbox', checked: true });
        box.addEventListener('change', () => {
          if (box.checked) hidden.delete(layer.id);
          else hidden.add(layer.id);
          viewport.setHiddenLayers(new Set(hidden));
        });
        list.append(el('label', { className: 'row' }, box, ' ', layer.name));
      }
      panel.append(el('details', { open: true }, el('summary', {}, text.layers), list));
    }

    const table = el('div', { className: 'list' });
    for (const line of [...materials.lines, ...materials.noItem]) {
      const { stacks: s, rest: r } = stacks(line.count);
      table.append(el('div', { className: 'row' }, el('span', { className: 'name', title: line.id }, nameOf(line.id)),
        el('span', { className: 'count', title: s ? fill(text.stacks, { s, r }) : '' }, line.count.toLocaleString())));
    }
    panel.append(el('details', { open: materials.lines.length <= 40 }, el('summary', {}, `${text.materials} (${materials.lines.length + materials.noItem.length})`), table));
    panel.append(el('p', { className: 'muted small' }, text.help));
    panel.append(el('p', { className: 'small' }, el('a', { href: 'https://yujin2625.github.io/Pawprint/', target: '_blank', rel: 'noopener' }, text.made)));
    const toggle = el('button', { className: 'toggle', type: 'button', title: text.hide }, '☰');
    toggle.addEventListener('click', () => panel.classList.toggle('closed'));
    app.append(panel, toggle);
  } catch (e) {
    console.error(e);
    app.append(el('p', { className: 'error' }, text.failed));
  }
}

void start();
