# Pawprint

A client-side building planner for Minecraft. Works on multiplayer servers without any server-side install,
and never places blocks for you.

Everything is reached from **one key, `B`**: hold it, point toward an item of the radial menu and let go
(or tap it and click). The mouse does the rest.

- **Edit mode**: place ghost blocks one by one or as lines, boxes, walls, spheres and cylinders, with undo/redo
  (Ctrl+Z / Ctrl+Y). Clicks are never sent to the server while editing. Pick any registered block from a palette
  searchable in English and Korean. A Select Area tool marks boxes to save.
- **Placement mode**: see which block goes where, with wrong blocks in red, wrong orientation in yellow and blocks
  to remove outlined. Ghosts are lit like real blocks; large builds stay smooth thanks to cached per-section meshes.
- **Adjust Placement**: wheel moves the placement (Shift: up/down, Ctrl: sideways), right click rotates, middle
  click mirrors.
- **Progress**: choose a placement, see build progress and the materials still needed; layer view (Alt+wheel).
- **Freecam** for planning from anywhere.
- **Studio**: copy the terrain around you into a local creative void world at the same coordinates, build there,
  save only what changed, and come back to see it placed.
- **Library**: thumbnails, list/grid views, search (name, block, `#tag`, `@mod`), groups, favorites, sorting,
  rename/tags/move/duplicate/delete.
- **Sharing**: share strings for chat, export to `.litematic`, `.schem` and `.nbt`, import those plus legacy
  `.schematic` (drop files onto the library screen).
- **AI blueprints**: copy a prompt for any AI chat, paste its JSON reply back. See
  [docs/AI_BLUEPRINT_FORMAT.md](docs/AI_BLUEPRINT_FORMAT.md).

Every feature also has its own key in the controls menu, unbound by default so Pawprint does not take keys other mods use. English and Korean.

> Status: early development. This branch (`mc/1.20.1`) targets Minecraft 1.20.1 (Forge, Fabric); `main` targets 1.21.1 (NeoForge, Fabric). See [docs/DESIGN.md](docs/DESIGN.md) (Korean).

## Building

Requires JDK 21.

```
./gradlew build
```

Jars are written to `fabric/build/libs` and `neoforge/build/libs`.

### Self-test

`-Dpawprint.selftest=true` (e.g. via `JAVA_TOOL_OPTIONS` with `./gradlew :fabric:runClient`) checks the text
format, format conversions, share strings, thumbnails and all mixins at the main menu, then runs a full studio
round trip in a fresh superflat world. Results are logged with the prefix `SELFTEST`.

## License

[MIT](LICENSE)
