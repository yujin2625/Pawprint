/** Builds the single-file HTML viewer page from the viewer bundle and one blueprint's data. */

export interface ViewerData {
  /** `.pawprint` file, base64. */
  blueprint: string;
  /** Block pack subset (subsetPack), base64; null to draw colored boxes. */
  pack: string | null;
  /** UI language: `ko` or `en`. */
  lang: string;
}

export function toBase64(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(out);
}

const escapeHtml = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
/** Keeps text inside a <script> element from closing it. */
const scriptSafe = (s: string) => s.replace(/<\/(script)/gi, '<\\/$1').replace(/<!--/g, '<\\!--');

const STYLE = `
:root { color-scheme: dark; --bg: #2a6fb5; --panel: rgba(18, 41, 66, 0.88); --text: #faeeda; --muted: #c9d8ea; --accent: #ef9f27; --viewport-bg: #2a6fb5; }
html, body { margin: 0; height: 100%; overflow: hidden; background: var(--bg); color: var(--text);
  font: 14px/1.4 system-ui, 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; }
#app, .view { position: absolute; inset: 0; }
.panel { position: absolute; top: 12px; left: 12px; width: min(300px, calc(100vw - 24px)); max-height: calc(100% - 24px); overflow: auto;
  box-sizing: border-box; padding: 12px 14px; background: var(--panel); border: 2px solid rgba(0, 0, 0, 0.35); }
.panel.closed { display: none; }
h1 { margin: 0 0 4px; font-size: 18px; }
.muted { color: var(--muted); margin: 4px 0; }
.small { font-size: 12px; }
summary { cursor: pointer; margin-top: 10px; color: var(--accent); }
.list { display: flex; flex-direction: column; gap: 2px; margin-top: 6px; }
.row { display: flex; gap: 8px; align-items: center; }
.name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.count { font-variant-numeric: tabular-nums; }
a { color: var(--accent); }
.toggle { position: absolute; top: 12px; right: 12px; width: 36px; height: 36px; font-size: 18px; cursor: pointer;
  background: var(--panel); color: var(--text); border: 2px solid rgba(0, 0, 0, 0.35); }
.error { padding: 24px; }
`;

export function viewerHtml(title: string, data: ViewerData, runtime: string): string {
  return `<!doctype html>
<html lang="${data.lang === 'ko' ? 'ko' : 'en'}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="generator" content="Pawprint">
<title>${escapeHtml(title)}</title>
<style>${STYLE}</style>
</head>
<body>
<div id="app"></div>
<script type="application/json" id="pawprint-data">${scriptSafe(JSON.stringify(data))}</script>
<script>${scriptSafe(runtime)}</script>
</body>
</html>
`;
}
