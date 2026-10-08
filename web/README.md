# Pawprint Web

Browser editor for Pawprint blueprints. One codebase for every Minecraft version: block data comes from block packs
(`.pawpack`), never from game code. Design: [docs/WEB_DESIGN.md](../docs/WEB_DESIGN.md), plan:
[docs/WEB_IMPLEMENTATION.md](../docs/WEB_IMPLEMENTATION.md), formats: [docs/FORMAT_PAWPRINT.md](../docs/FORMAT_PAWPRINT.md),
[docs/FORMAT_PAWPACK.md](../docs/FORMAT_PAWPACK.md).

```bash
npm install
npm run dev      # http://localhost:5173
npm test         # unit tests (core logic, language files)
npm run check    # type check
npm run build    # static site in dist/
```

## Desktop app (Tauri)

Needs Rust (stable, MSVC on Windows). The same page runs in the app; `src/platform/platform.ts` holds what differs.

```bash
npm run tauri dev     # the app with live reload
npm run tauri build   # installer in src-tauri/target/release/bundle/nsis/
```

- `src-tauri/src/lib.rs`: file read/write commands, files opened by double-click (one running app; later files are
  handed to it), and panel windows: `window.open` from the editor becomes an app window that shares the page's
  JavaScript, so a panel moved there keeps working on the same blueprint.
- `src-tauri/src/minecraft.rs`: finds `versions/<v>/<v>.jar` and the launcher's language files for multi-language
  vanilla packs.
- `public/_local/` (ignored by git) is for local test files; builds leave it out.

## Notes

- `src/core/` has no browser or Svelte dependencies and is tested in Node.
- No Mojang assets are in this repository. Tests use their own small fake data. To try the jar pack builder on a
  client jar installed on your PC: `npx vite-node tools/check-jar.ts <path to jar>`.
- Fonts: `public/fonts` is generated from Silver (CC BY 4.0) by `python tools/build_font.py <Silver.ttf>`
  (needs `pip install fonttools`). See `public/fonts/LICENSE.txt`.
