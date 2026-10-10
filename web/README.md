# Pawprint Web

Browser editor for Pawprint blueprints. One codebase for every Minecraft version: block data comes from block packs
(`.pawpack`), never from game code. Design: [docs/WEB_DESIGN.md](../docs/WEB_DESIGN.md), plan:
[docs/WEB_IMPLEMENTATION.md](../docs/WEB_IMPLEMENTATION.md), formats: [docs/FORMAT_PAWPRINT.md](../docs/FORMAT_PAWPRINT.md),
[docs/FORMAT_PAWPACK.md](../docs/FORMAT_PAWPACK.md).

```bash
npm install
npm run dev      # http://localhost:5173
npm test         # unit tests (core logic, language files)
npm run e2e      # screen test in a browser (installed Chrome; CI uses Playwright's Chromium)
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

## Publishing

- Web: every push to `main` that changes `web/` is tested, built and published to GitHub Pages
  (`.github/workflows/web.yml`): https://yujin2625.github.io/Pawprint/
- Desktop: set the same version in `src-tauri/tauri.conf.json` and `package.json`, then push a tag `app-v<version>`
  (for example `app-v0.1.0`). `.github/workflows/desktop.yml` builds Windows, macOS and Linux installers into a
  draft release; check it and publish it on GitHub.

## HTML viewer

"HTML viewer…" in the editor saves one .html file with the blueprint, the part of the block pack it uses and a viewer
runtime (`src/viewer/main.ts`, built by `npm run viewer` into `viewer-dist/viewer.js`; `dev` and `build` run it
first). It opens offline in any browser.

## Notes

- `src/core/` has no browser or Svelte dependencies and is tested in Node.
- No Mojang assets are in this repository. Tests use their own small fake data. To try the jar pack builder on a
  client jar installed on your PC: `npx vite-node tools/check-jar.ts <path to jar>`.
- Fonts: `public/fonts` is generated from Silver (CC BY 4.0) by `python tools/build_font.py <Silver.ttf>`
  (needs `pip install fonttools`). See `public/fonts/LICENSE.txt`.
