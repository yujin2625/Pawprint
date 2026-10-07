package com.dumaru.pawprint.client.studio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
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

    /** Opens the studio world, creating it first if needed. The caller must have left any world already. */
    public static void open(Minecraft minecraft) {
        Screen fallback = new TitleScreen();
        if (minecraft.getLevelSource().levelExists(FOLDER)) {
            minecraft.createWorldOpenFlows().loadLevel(fallback, FOLDER);
            return;
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, null);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, null);
        rules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DO_WARDEN_SPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings("Pawprint Studio", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, rules, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(FOLDER, settings, new WorldOptions(0L, false, false),
                registries -> {
                    var voidSettings = registries.registryOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                            .getHolderOrThrow(FlatLevelGeneratorPresets.THE_VOID).value().settings();
                    return WorldPresets.createNormalWorldDimensions(registries)
                            .replaceOverworldGenerator(registries, new FlatLevelSource(voidSettings));
                });
    }
}
