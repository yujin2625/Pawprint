package com.dumaru.pawprint.client;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.render.ThumbnailRenderer;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.text.TextBlueprintReader;
import com.dumaru.pawprint.format.text.TextBlueprintWriter;
import com.mojang.blaze3d.platform.NativeImage;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.client.render.GhostRenderer;
import com.dumaru.pawprint.client.studio.StudioWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

/**
 * Development check, enabled with {@code -Dpawprint.selftest=true}: once the main menu is up, reads the sample
 * text blueprint, writes it back as text, reads that again, and renders a thumbnail to {@code pawprint/selftest.png}.
 * Results go to the log. Does nothing in normal play.
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
            // Loads every mixin target now, so injection errors show up without joining a world.
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            Pawprint.LOG.info("SELFTEST mixin audit finished");
            StudioWorld.open(minecraft);
        } catch (Exception e) {
            Pawprint.LOG.error("SELFTEST failed", e);
        }
    }

    private static int worldTicks;

    /**
     * In the studio world: places a large blueprint in front of the player, then reports frame rate and
     * renderer timings and saves a screenshot, so the cached section renderer can be checked without a person.
     */
    private static void worldTick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
            return;
        }
        worldTicks++;
        if (worldTicks == 100) {
            Blueprint.Builder builder = Blueprint.builder();
            BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
            BlockState glass = Blocks.GLASS.defaultBlockState();
            BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState();
            for (int y = 0; y < 48; y++) {
                for (int z = 0; z < 64; z++) {
                    for (int x = 0; x < 64; x++) {
                        builder.put(x, y, z, y % 8 == 7 ? glass : (x + z) % 16 == 0 ? stairs : stone);
                    }
                }
            }
            Blueprint big = builder.build("Selftest Fortress", "test", null);
            BlockPos origin = minecraft.player.blockPosition().offset(-32, -20, 12);
            long start = System.nanoTime();
            PlacementManager.add(new Placement("selftest.pawprint", big, origin, Rotation.NONE, Mirror.NONE));
            PlacementManager.setViewing(true);
            Pawprint.LOG.info("SELFTEST placed {} blocks in {} ms", big.meta().blockCount, (System.nanoTime() - start) / 1_000_000);
            minecraft.player.setYRot(0f); // Face south, toward the blueprint.
            minecraft.player.setXRot(20f);
            GhostRenderer.resetStats();
        } else if (worldTicks == 300) {
            Pawprint.LOG.info("SELFTEST fps {}, {}", minecraft.getFps(), GhostRenderer.stats());
            Screenshot.grab(minecraft.gameDirectory, "pawprint_selftest.png", minecraft.getMainRenderTarget(),
                    message -> Pawprint.LOG.info("SELFTEST screenshot: {}", message.getString()));
        }
    }
}
