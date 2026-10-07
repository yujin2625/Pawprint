# Pawprint

A client-side building planner for Minecraft.

- **Blueprint mode**: place ghost blocks to plan a build, then save it.
- **Placement overlay**: see which block goes where, with progress and a material list.
- **Studio**: copy the terrain around you into a local creative world, design there, and bring the plan back to the server.
- **Library**: browse, group, sort, filter and search your blueprints. Share them as files or text strings.

Works on multiplayer servers without any server-side install. No auto-placing.

> Status: early development. Only the build setup and a placeholder library screen exist so far.
> See [docs/DESIGN.md](docs/DESIGN.md) (Korean) for the full plan.

## Planned support
- Minecraft 1.21.1 (NeoForge, Fabric) first, then 1.20.1 (Forge, Fabric) and the latest version
- Import/export: `.litematic`, `.schem`, `.nbt`, `.schematic` (import only)
- English and Korean

## Building

Requires JDK 21.

```
./gradlew build
```

Jars are written to `fabric/build/libs` and `neoforge/build/libs`.

## License

[MIT](LICENSE)
