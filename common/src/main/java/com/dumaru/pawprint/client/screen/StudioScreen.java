package com.dumaru.pawprint.client.screen;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.Selection;
import com.dumaru.pawprint.client.studio.Studio;
import com.dumaru.pawprint.client.studio.StudioSession;
import com.dumaru.pawprint.client.studio.StudioWorld;
import com.dumaru.pawprint.client.studio.TerrainSnapshot;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintMeta;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Studio controls. Outside the studio: take a terrain snapshot and go. Inside: save the changes as a blueprint
 * and return to the original world, where it is placed at the same spot.
 */
public class StudioScreen extends Screen {
    private static final int[] RADII = {2, 4, 8, 12, 16};
    private static final int[] DEPTHS = {2, 4, 8, 16, 32};

    private int radius = Pawprint.config().snapshotRadiusChunks;
    private int depth = Pawprint.config().snapshotSurfaceDepth;
    private @Nullable Component status;
    private int statusColor = 0xFFFF5555;

    public StudioScreen() {
        super(Component.translatable("pawprint.studio.title"));
    }

    private boolean inStudio() {
        return StudioWorld.isCurrent(minecraft);
    }

    @Override
    protected void init() {
        int left = width / 2 - 110;
        int y = height / 2 - 10;
        if (inStudio()) {
            addRenderableWidget(Button.builder(Component.translatable("pawprint.studio.save"), b -> askName(false))
                    .bounds(left, y, 220, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("pawprint.studio.save_and_return"), b -> askName(true))
                    .bounds(left, y + 24, 220, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("pawprint.studio.return_without_saving"), b ->
                    minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                        if (yes) {
                            Studio.returnToOrigin(minecraft);
                        } else {
                            minecraft.gui.setScreen(this);
                        }
                    }, Component.translatable("pawprint.studio.return_without_saving"),
                            Component.translatable("pawprint.studio.return_confirm")))).bounds(left, y + 48, 220, 20).build());
        } else {
            Button radiusButton = addRenderableWidget(Button.builder(radiusLabel(), b -> {
                radius = next(RADII, radius);
                b.setMessage(radiusLabel());
            }).bounds(left, y, 108, 20).build());
            Button depthButton = addRenderableWidget(Button.builder(depthLabel(), b -> {
                depth = next(DEPTHS, depth);
                b.setMessage(depthLabel());
            }).bounds(left + 112, y, 108, 20).build());
            Button surface = addRenderableWidget(Button.builder(Component.translatable("pawprint.studio.open_surface"),
                    b -> go(false)).bounds(left, y + 24, 220, 20).build());
            Button selection = addRenderableWidget(Button.builder(Component.translatable("pawprint.studio.open_selection"),
                    b -> go(true)).bounds(left, y + 48, 220, 20).build());
            boolean allowed = Pawprint.config().enableTerrainSnapshot;
            radiusButton.active = depthButton.active = surface.active = allowed;
            selection.active = allowed && Selection.box() != null;
            if (!allowed) {
                status = Component.translatable("pawprint.studio.snapshot_disabled");
            }
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left, y + 76, 220, 20).build());
    }

    private Component radiusLabel() {
        return Component.translatable("pawprint.studio.radius", radius);
    }

    private Component depthLabel() {
        return Component.translatable("pawprint.studio.depth", depth);
    }

    private static int next(int[] options, int current) {
        for (int option : options) {
            if (option > current) {
                return option;
            }
        }
        return options[0];
    }

    private void go(boolean useSelection) {
        try {
            BlueprintMeta.Origin origin = new BlueprintMeta.Origin(ClientContext.server(), ClientContext.dimension(), 0, 0, 0);
            BoundingBox box = Selection.box();
            TerrainSnapshot.Result snapshot = useSelection && box != null
                    ? TerrainSnapshot.box(minecraft.level, box, origin)
                    : TerrainSnapshot.surface(minecraft.level, minecraft.player.blockPosition(), radius, depth, origin);
            Studio.open(minecraft, snapshot);
        } catch (TerrainSnapshot.TooLargeException e) {
            status = Component.translatable("pawprint.studio.too_large", TerrainSnapshot.MAX_BLOCKS);
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not open the studio", e);
            status = Component.translatable("pawprint.library.action_failed", e.getMessage());
        }
    }

    private void askName(boolean thenReturn) {
        minecraft.gui.setScreen(new TextInputScreen(this, Component.translatable("pawprint.screen.save.name"), "", null, false,
                name -> save(name, thenReturn)));
    }

    private void save(String name, boolean thenReturn) {
        try {
            Blueprint blueprint = Studio.diff(minecraft, name);
            if (blueprint == null) {
                minecraft.gui.setScreen(this);
                status = Component.translatable("pawprint.studio.no_changes");
                statusColor = 0xFFFFFF55;
                return;
            }
            Studio.saveForReturn(blueprint);
            if (thenReturn) {
                Studio.returnToOrigin(minecraft);
            } else {
                minecraft.gui.setScreen(null);
                PawprintClient.notify(minecraft, Component.translatable("pawprint.capture.saved",
                        blueprint.meta().name, blueprint.meta().blockCount + blueprint.meta().removalCount));
            }
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not save the studio blueprint", e);
            minecraft.gui.setScreen(this);
            status = Component.translatable("pawprint.capture.write_failed", e.getMessage());
            statusColor = 0xFFFF5555;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 92, 0xFFFFFFFF);
        Component info;
        if (inStudio()) {
            StudioSession session = Studio.session();
            String target = session == null ? "-"
                    : session.returnType.equals("local") ? session.localFolder : session.serverName + " (" + session.serverIp + ")";
            info = Component.translatable("pawprint.studio.info_inside", target);
        } else {
            info = Component.translatable("pawprint.studio.info_outside");
        }
        int y = height / 2 - 76;
        for (FormattedCharSequence line : font.split(info, Math.min(width - 40, 360))) {
            graphics.centeredText(font, line, width / 2, y, 0xFFC0C0C0);
            y += 10;
        }
        if (status != null) {
            List<FormattedCharSequence> lines = font.split(status, Math.min(width - 40, 360));
            graphics.centeredText(font, lines.get(0), width / 2, height / 2 + 100, statusColor);
        }
    }
}
