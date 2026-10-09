package com.dumaru.pawprint.client.studio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * The local studio world: an empty creative void where nothing grows, burns, spawns or changes by itself,
 * so a pasted terrain snapshot stays exactly as captured.
 */
public final class StudioWorld {
    /** Folder name under {@code saves}. */
    public static final String FOLDER = "pawprint_studio";
    public static final String CONTEXT = "local/" + FOLDER;

    private StudioWorld() {
    }

    public static boolean isCurrent(Minecraft minecraft) {
        return minecraft.getSingleplayerServer() != null
                && CONTEXT.equals(com.dumaru.pawprint.client.ClientContext.server());
    }

    /** A still world for building: no time, weather, mobs, fire or random ticks. Call on the server thread. */
    public static void applyRules(net.minecraft.server.MinecraftServer server) {
        GameRules rules = server.getGameRules();
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
        rules.set(GameRules.MOB_GRIEFING, false, server);
        rules.set(GameRules.SPAWN_PATROLS, false, server);
        rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
        rules.set(GameRules.SPAWN_WARDENS, false, server);
    }

    /** Opens the studio world, creating it first if needed. The caller must have left any world already. */
    public static void open(Minecraft minecraft) {
        Screen fallback = new TitleScreen();
        if (minecraft.getLevelSource().levelExists(FOLDER)) {
            minecraft.createWorldOpenFlows().openWorld(FOLDER, () -> minecraft.gui.setScreen(fallback));
            return;
        }
        LevelSettings settings = new LevelSettings("Pawprint Studio", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(FOLDER, settings, new WorldOptions(0L, false, false),
                registries -> {
                    var voidSettings = registries.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                            .getOrThrow(FlatLevelGeneratorPresets.THE_VOID).value().settings();
                    return WorldPresets.createNormalWorldDimensions(registries)
                            .replaceOverworldGenerator(registries, new FlatLevelSource(voidSettings));
                }, fallback);
    }
}
