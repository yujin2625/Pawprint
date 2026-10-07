package com.dumaru.pawprint.client;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.render.ThumbnailRenderer;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.text.TextBlueprintReader;
import com.dumaru.pawprint.format.text.TextBlueprintWriter;
import com.mojang.blaze3d.platform.NativeImage;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.client.studio.Studio;
import com.dumaru.pawprint.client.studio.StudioSession;
import com.dumaru.pawprint.client.studio.StudioWorld;
import com.dumaru.pawprint.client.studio.TerrainSnapshot;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.format.convert.Formats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.nio.file.Path;

/**
 * Development check, enabled with {@code -Dpawprint.selftest=true}: once the main menu is up, reads the sample
 * text blueprint, writes it back as text, reads that again, renders a thumbnail to {@code pawprint/selftest.png},
 * audits mixins, then runs a studio round trip in a fresh superflat world. Results go to the log.
 * Does nothing in normal play.
 */
public final class SelfTest {
    private static final boolean ENABLED = Boolean.getBoolean("pawprint.selftest");
    private static boolean done;

    private static final String SAMPLE = """
            {
              "pawprint": 1,
              "name": "Small Oak Cabin",
              "tags": ["house", "oak"],
              "palette": {
                "#": "minecraft:cobblestone",
                "L": "minecraft:oak_log[axis=y]",
                "P": "minecraft:oak_planks",
                "G": "minecraft:glass_pane",
                "D": "minecraft:oak_door[facing=south,half=lower,hinge=left]",
                "d": "minecraft:oak_door[facing=south,half=upper,hinge=left]",
                "S": "minecraft:oak_stairs[facing=north,half=bottom]",
                "s": "minecraft:oak_stairs[facing=south,half=bottom]",
                "_": "air"
              },
              "operations": [
                { "shape": "box",   "from": [0, 0, 0], "to": [6, 0, 4], "block": "#" },
                { "shape": "walls", "from": [0, 1, 0], "to": [6, 3, 4], "block": "P" },
                { "shape": "box",   "from": [1, 1, 1], "to": [5, 3, 3], "block": "_" },
                { "shape": "line",  "from": [0, 1, 0], "to": [0, 3, 0], "block": "L" },
                { "shape": "line",  "from": [6, 1, 0], "to": [6, 3, 0], "block": "L" },
                { "shape": "line",  "from": [0, 1, 4], "to": [0, 3, 4], "block": "L" },
                { "shape": "line",  "from": [6, 1, 4], "to": [6, 3, 4], "block": "L" },
                { "shape": "sphere", "center": [3, 7, 2], "radius": 1, "block": "minecraft:oak_leaves" }
              ],
              "layers": {
                "origin": [0, 1, 0],
                "grid": [
                  [".......", ".......", ".......", ".......", "...D..."],
                  ["..G.G..", "G.....G", ".......", "G.....G", "...d..."],
                  [".......", ".......", ".......", ".......", "......."],
                  ["sssssss", "sssssss", "PPPPPPP", "SSSSSSS", "SSSSSSS"]
                ]
              }
            }
            """;

    private SelfTest() {
    }

    public static void tick(Minecraft minecraft) {
        if (!ENABLED) {
            return;
        }
        if (done) {
            worldTick(minecraft);
            return;
        }
        // Any menu screen after resource loading will do; a first launch shows an onboarding screen, not the title.
        if (minecraft.getOverlay() != null || minecraft.screen == null || minecraft.level != null) {
            return;
        }
        done = true;
        minecraft.options.pauseOnLostFocus = false; // The test window usually is not focused.
        try {
            Blueprint blueprint = TextBlueprintReader.read("Here you go:\n```json\n" + SAMPLE + "```", "test").blueprint();
            Pawprint.LOG.info("SELFTEST read: size {}x{}x{}, {} blocks, {} removals, blocks {}",
                    blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ(), blueprint.meta().blockCount,
                    blueprint.meta().removalCount, blueprint.meta().blocks);
            String text = TextBlueprintWriter.write(blueprint);
            Blueprint again = TextBlueprintReader.read(text, "test").blueprint();
            boolean same = again.meta().blockCount == blueprint.meta().blockCount
                    && again.meta().removalCount == blueprint.meta().removalCount;
            Pawprint.LOG.info("SELFTEST round trip through text: {}", same ? "OK" : "MISMATCH");
            try {
                TextBlueprintReader.read("{\"pawprint\":1,\"name\":\"x\",\"operations\":[{\"shape\":\"box\",\"from\":[0,0,0],"
                        + "\"to\":[1,1,1],\"block\":\"minecraft:oak_stairs[facing=up]\"}]}", "test");
                Pawprint.LOG.info("SELFTEST bad state: NOT REJECTED");
            } catch (TextBlueprintReader.FormatException e) {
                Pawprint.LOG.info("SELFTEST bad state rejected: {}", e.getMessage());
            }
            NativeImage image = ThumbnailRenderer.render(blueprint);
            Path file = Pawprint.dataDir().resolve("selftest.png");
            java.nio.file.Files.createDirectories(file.getParent());
            if (image != null) {
                image.writeToFile(file);
                image.close();
            }
            Pawprint.LOG.info("SELFTEST thumbnail: {}", image != null ? file.toAbsolutePath() : "none");
            formatRoundTrips(blueprint);
            // Loads every mixin target now, so injection errors show up without joining a world.
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            Pawprint.LOG.info("SELFTEST mixin audit finished");
            openOriginWorld(minecraft);
        } catch (Exception e) {
            Pawprint.LOG.error("SELFTEST failed", e);
        }
    }

    /** Writes the sample in every format, reads it back, and reads a hand-made legacy schematic. */
    private static void formatRoundTrips(Blueprint blueprint) throws Exception {
        Path folder = Pawprint.dataDir().resolve("selftest-formats");
        for (Formats format : Formats.values()) {
            if (!format.canWrite) {
                continue;
            }
            Path file = folder.resolve("sample" + format.extension);
            format.write(blueprint, file);
            Blueprint back = format.read(file);
            Pawprint.LOG.info("SELFTEST format {}: wrote {} blocks, read {} blocks{}", format.extension,
                    blueprint.meta().blockCount, back.meta().blockCount,
                    back.meta().blockCount == blueprint.meta().blockCount ? " OK" : " MISMATCH");
        }
        String share = com.dumaru.pawprint.format.BlueprintIO.toShareString(blueprint);
        Blueprint shared = com.dumaru.pawprint.format.BlueprintIO.fromShareString("Look: " + share + " (my house)", "test");
        Pawprint.LOG.info("SELFTEST share string: {} chars, {} blocks, {} removals, name {}", share.length(),
                shared.meta().blockCount, shared.meta().removalCount, shared.meta().name);
        Blueprint replaced = com.dumaru.pawprint.format.BlockReplace.replace(
                com.dumaru.pawprint.format.BlockReplace.replace(blueprint, state -> state.is(Blocks.OAK_PLANKS), Blocks.STONE),
                state -> state.is(Blocks.OAK_STAIRS), Blocks.STONE_STAIRS);
        Pawprint.LOG.info("SELFTEST replace: {} blocks, palette {}", replaced.meta().blockCount,
                replaced.palette().stream().filter(entry -> entry.contains("stone")).toList());
        CompoundTag legacy = new CompoundTag();
        legacy.putShort("Width", (short) 3);
        legacy.putShort("Height", (short) 1);
        legacy.putShort("Length", (short) 1);
        legacy.putByteArray("Blocks", new byte[]{1, 17, 53});
        legacy.putByteArray("Data", new byte[]{0, 0, 2});
        legacy.putString("Materials", "Alpha");
        Path file = folder.resolve("legacy.schematic");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(legacy, bytes);
        java.nio.file.Files.write(file, bytes.toByteArray());
        Blueprint old = Formats.MCEDIT_SCHEMATIC.read(file);
        Pawprint.LOG.info("SELFTEST legacy schematic palette: {}", old.palette());
    }

    private static final String ORIGIN_FOLDER = "pawprint_selftest_origin";
    private static int stage;
    private static int stageTicks;

    /** A plain superflat world to start the studio round trip from. */
    private static void openOriginWorld(Minecraft minecraft) {
        LevelSettings settings = new LevelSettings("Pawprint Selftest", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, new GameRules(), WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(ORIGIN_FOLDER, settings, new WorldOptions(0L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(), new TitleScreen());
    }

    /**
     * Studio round trip: snapshot the origin world, open the studio, build a pillar and dig one block,
     * save the difference, return, and check that the blueprint was placed back.
     */
    private static void worldTick(Minecraft minecraft) {
        if (minecraft.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            minecraft.setScreen(null);
        }
        boolean ownScreen = stage >= 4 && minecraft.screen != null;
        if (minecraft.level == null || minecraft.player == null || (minecraft.screen != null && !ownScreen)) {
            return;
        }
        stageTicks++;
        String context = ClientContext.server();
        try {
            switch (stage) {
                case 0 -> {
                    if (("local/" + ORIGIN_FOLDER).equals(context) && stageTicks > 60) {
                        BlueprintMeta.Origin origin = new BlueprintMeta.Origin(context, ClientContext.dimension(), 0, 0, 0);
                        TerrainSnapshot.Result snapshot = TerrainSnapshot.surface(minecraft.level,
                                minecraft.player.blockPosition(), 2, 4, origin);
                        Pawprint.LOG.info("SELFTEST snapshot: {} blocks in {}", snapshot.blueprint().meta().blockCount,
                                snapshot.bounds());
                        next();
                        Studio.open(minecraft, snapshot);
                    }
                }
                case 1 -> {
                    StudioSession session = Studio.session();
                    if (StudioWorld.isCurrent(minecraft) && session != null && session.pasted && stageTicks > 20) {
                        BoundingBox box = session.box();
                        int x = (box.minX() + box.maxX()) / 2;
                        int z = (box.minZ() + box.maxZ()) / 2;
                        IntegratedServer server = minecraft.getSingleplayerServer();
                        server.execute(() -> {
                            ServerLevel level = server.overworld();
                            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                            for (int i = 0; i < 5; i++) {
                                level.setBlock(new BlockPos(x, top + i, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                            }
                            level.setBlock(new BlockPos(x + 3, top - 1, z), Blocks.AIR.defaultBlockState(), 3);
                            Pawprint.LOG.info("SELFTEST studio: terrain top at {}, built 5 blocks and dug 1", top);
                        });
                        next();
                    }
                }
                case 2 -> {
                    if (stageTicks > 20) {
                        Blueprint changes = Studio.diff(minecraft, "Selftest Pillar");
                        Pawprint.LOG.info("SELFTEST diff: {}", changes == null ? "none"
                                : changes.meta().blockCount + " blocks, " + changes.meta().removalCount + " removals, origin "
                                + java.util.Arrays.toString(changes.meta().origin.pos));
                        if (changes != null) {
                            Studio.saveForReturn(changes);
                        }
                        next();
                        Studio.returnToOrigin(minecraft);
                    }
                }
                case 3 -> {
                    if (("local/" + ORIGIN_FOLDER).equals(context) && stageTicks > 60) {
                        Pawprint.LOG.info("SELFTEST back in origin: {} placement(s){}", PlacementManager.placements().size(),
                                PlacementManager.placements().isEmpty() ? ""
                                        : ", first at " + PlacementManager.placements().get(0).origin().toShortString());
                        minecraft.player.setXRot(60f);
                        var placement = PlacementManager.active();
                        var before = placement.origin();
                        AdjustMode.toggle(minecraft);
                        PawprintClient.onScroll(minecraft, 1);
                        PawprintClient.onScroll(minecraft, 1);
                        AdjustMode.finish(minecraft);
                        Pawprint.LOG.info("SELFTEST adjust by wheel: {} -> {} (distance {})", before.toShortString(),
                                placement.origin().toShortString(), Math.sqrt(before.distSqr(placement.origin())));
                        IntegratedServer server = minecraft.getSingleplayerServer();
                        server.execute(() -> server.getPlayerList().getPlayers().forEach(player -> {
                            player.getInventory().add(new net.minecraft.world.item.ItemStack(Blocks.STONE_BRICKS, 3));
                            player.getInventory().add(new net.minecraft.world.item.ItemStack(Blocks.OAK_PLANKS, 10));
                        }));
                        next();
                    }
                }
                case 4 -> {
                    if (stageTicks == 20) {
                        minecraft.setScreen(new com.dumaru.pawprint.client.screen.PlacementScreen());
                    } else if (stageTicks == 40) {
                        Screenshot.grab(minecraft.gameDirectory, "pawprint_selftest_panel.png", minecraft.getMainRenderTarget(),
                                message -> Pawprint.LOG.info("SELFTEST screenshot: {}", message.getString()));
                        Blueprint sample = TextBlueprintReader.read(SAMPLE, "test").blueprint();
                        var materials = com.dumaru.pawprint.client.placement.MaterialList.forBlueprint(sample, minecraft.player);
                        Pawprint.LOG.info("SELFTEST blueprint materials text:\n{}",
                                com.dumaru.pawprint.client.placement.MaterialList.toText(sample.meta().name, materials, false));
                        minecraft.setScreen(com.dumaru.pawprint.client.screen.LibraryScreen.materialsFor(sample));
                    } else if (stageTicks == 60) {
                        Screenshot.grab(minecraft.gameDirectory, "pawprint_selftest_materials.png", minecraft.getMainRenderTarget(),
                                message -> Pawprint.LOG.info("SELFTEST screenshot: {}", message.getString()));
                        minecraft.setScreen(null);
                        Pawprint.LOG.info("SELFTEST studio round trip finished");
                        next();
                    }
                }
                default -> {
                }
            }
        } catch (Exception e) {
            Pawprint.LOG.error("SELFTEST failed in stage {}", stage, e);
            stage = 99;
        }
    }

    private static void next() {
        stage++;
        stageTicks = 0;
    }
}
