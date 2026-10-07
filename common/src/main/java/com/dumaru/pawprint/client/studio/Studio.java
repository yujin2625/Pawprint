package com.dumaru.pawprint.client.studio;

import com.dumaru.pawprint.Pawprint;
import com.dumaru.pawprint.client.ClientContext;
import com.dumaru.pawprint.client.PawprintClient;
import com.dumaru.pawprint.client.placement.BlockStatus;
import com.dumaru.pawprint.client.placement.Placement;
import com.dumaru.pawprint.client.placement.PlacementManager;
import com.dumaru.pawprint.format.Blueprint;
import com.dumaru.pawprint.format.BlueprintIO;
import com.dumaru.pawprint.format.BlueprintMeta;
import com.dumaru.pawprint.library.BlueprintLibrary;
import com.dumaru.pawprint.library.LibraryState;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Round trip between the world being planned for and the local studio world:
 * snapshot the terrain, open the studio with the terrain at the same coordinates, build freely, save only what
 * changed as a blueprint, go back and see it placed at the original spot.
 */
public final class Studio {
    /** Blocks changed per server tick while pasting, to keep the studio responsive. */
    private static final int PASTE_BATCH = 30_000;
    /** Diffing also looks this many chunks beyond the snapshot, for things built next to it. */
    private static final int DIFF_MARGIN_CHUNKS = 2;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static @Nullable StudioSession session;
    private static volatile int pasteProgress = -1;

    private Studio() {
    }

    public static @Nullable StudioSession session() {
        if (session == null) {
            session = StudioSession.load();
        }
        return session;
    }

    /** Paste progress in percent while pasting, otherwise -1. */
    public static int pasteProgress() {
        return pasteProgress;
    }

    // Leaving for the studio

    /**
     * Saves the snapshot, remembers how to come back, leaves the current world and opens the studio.
     * Must be called from the client thread while in a world (not the studio).
     */
    public static void open(Minecraft minecraft, TerrainSnapshot.Result snapshot) throws IOException {
        StudioSession next = new StudioSession();
        next.server = ClientContext.server();
        next.dimension = ClientContext.dimension();
        if (minecraft.isLocalServer()) {
            next.returnType = "local";
            next.localFolder = next.server.substring("local/".length());
        } else {
            ServerData data = minecraft.getCurrentServer();
            if (data == null || data.isRealm()) {
                throw new IOException("Realms and unknown servers are not supported");
            }
            next.returnType = "server";
            next.serverName = data.name;
            next.serverIp = data.ip;
        }
        next.setBox(snapshot.bounds());
        Files.createDirectories(StudioSession.folder());
        BlueprintIO.write(snapshot.blueprint(), StudioSession.snapshotFile());
        next.save();
        session = next;

        leaveWorld(minecraft);
        StudioWorld.open(minecraft);
    }

    private static void leaveWorld(Minecraft minecraft) {
        boolean local = minecraft.isLocalServer();
        if (minecraft.level != null) {
            minecraft.level.disconnect();
        }
        minecraft.disconnect(new GenericMessageScreen(Component.translatable(local ? "menu.savingLevel" : "pawprint.studio.leaving")));
    }

    // In the studio

    /** Called every client tick; starts pasting the snapshot once the player is in the studio. */
    public static void tick(Minecraft minecraft) {
        StudioSession current = session();
        if (current == null || current.pasted || pasteProgress >= 0 || minecraft.player == null
                || !StudioWorld.isCurrent(minecraft)) {
            return;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        Blueprint snapshot;
        try {
            snapshot = BlueprintIO.read(StudioSession.snapshotFile());
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not read the terrain snapshot", e);
            current.pasted = true;
            current.save();
            return;
        }
        pasteProgress = 0;
        PawprintClient.notify(minecraft, Component.translatable("pawprint.studio.pasting", 0));
        server.execute(() -> new PasteJob(server, server.overworld(), snapshot, current).run());
    }

    /** Clears the snapshot area, then pastes the snapshot in batches, then moves the player above it. */
    private static final class PasteJob implements Runnable {
        private final IntegratedServer server;
        private final ServerLevel level;
        private final StudioSession current;
        private final BlockPos min;
        private final long[] positions;
        private final BlockState[] states;
        private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
        private int chunkCursor;
        private int blockCursor;

        PasteJob(IntegratedServer server, ServerLevel level, Blueprint snapshot, StudioSession current) {
            this.server = server;
            this.level = level;
            this.current = current;
            this.min = current.min();
            BoundingBox box = current.box();
            minChunkX = box.minX() >> 4;
            maxChunkX = box.maxX() >> 4;
            minChunkZ = box.minZ() >> 4;
            maxChunkZ = box.maxZ() >> 4;
            positions = new long[snapshot.blocks().size()];
            states = new BlockState[positions.length];
            int i = 0;
            for (Long2IntMap.Entry entry : snapshot.blocks().long2IntEntrySet()) {
                positions[i] = entry.getLongKey();
                BlockState state = snapshot.state(entry.getIntValue());
                states[i++] = state != null ? state : Blocks.AIR.defaultBlockState();
            }
        }

        private int chunkCount() {
            return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        }

        @Override
        public void run() {
            int budget = PASTE_BATCH;
            // Clear whatever an earlier studio session left in the area.
            while (budget > 0 && chunkCursor < chunkCount()) {
                int width = maxChunkX - minChunkX + 1;
                LevelChunk chunk = level.getChunk(minChunkX + chunkCursor % width, minChunkZ + chunkCursor / width);
                budget -= clear(chunk);
                chunkCursor++;
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (budget > 0 && blockCursor < positions.length) {
                long relative = positions[blockCursor];
                pos.set(min.getX() + BlockPos.getX(relative), min.getY() + BlockPos.getY(relative), min.getZ() + BlockPos.getZ(relative));
                if (level.isInWorldBounds(pos)) {
                    level.setBlock(pos, states[blockCursor], FLAGS);
                }
                blockCursor++;
                budget--;
            }
            int total = chunkCount() * 64 + positions.length;
            pasteProgress = (int) (100L * (chunkCursor * 64L + blockCursor) / Math.max(1, total));
            if (chunkCursor < chunkCount() || blockCursor < positions.length) {
                server.tell(new TickTask(server.getTickCount() + 1, this));
                return;
            }
            finish();
        }

        private int clear(LevelChunk chunk) {
            int changed = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (sections[i].hasOnlyAir()) {
                    continue;
                }
                int baseY = level.getSectionYFromSectionIndex(i) << 4;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!sections[i].getBlockState(x, y, z).isAir()) {
                                pos.set(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                                changed++;
                            }
                        }
                    }
                }
            }
            return changed + 64; // Count the scan itself, so empty chunks still use some of the budget.
        }

        private void finish() {
            BoundingBox box = current.box();
            int x = (box.minX() + box.maxX()) / 2;
            int z = (box.minZ() + box.maxZ()) / 2;
            // Stand just above the terrain in the middle of the snapshot.
            int top = Integer.MIN_VALUE;
            for (long relative : positions) {
                if (min.getX() + BlockPos.getX(relative) == x && min.getZ() + BlockPos.getZ(relative) == z) {
                    top = Math.max(top, min.getY() + BlockPos.getY(relative));
                }
            }
            int y = top == Integer.MIN_VALUE ? box.maxY() + 2 : top + 2;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(level, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
            }
            current.pasted = true;
            current.save();
            pasteProgress = -1;
            Minecraft.getInstance().execute(() ->
                    PawprintClient.notify(Minecraft.getInstance(), Component.translatable("pawprint.studio.ready")));
        }
    }

    /**
     * Compares the studio with the snapshot and returns the differences as a blueprint whose origin points at the
     * original world. Returns null when nothing changed.
     */
    public static @Nullable Blueprint diff(Minecraft minecraft, String name) throws IOException {
        StudioSession current = session();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (current == null || server == null) {
            return null;
        }
        Blueprint snapshot = BlueprintIO.read(StudioSession.snapshotFile());
        Long2ObjectMap<BlockState> changes = server.submit(() -> collectChanges(server.overworld(), snapshot, current)).join();
        if (changes.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (long pos : changes.keySet()) {
            minX = Math.min(minX, BlockPos.getX(pos));
            minY = Math.min(minY, BlockPos.getY(pos));
            minZ = Math.min(minZ, BlockPos.getZ(pos));
        }
        Blueprint.Builder builder = Blueprint.builder();
        for (Long2ObjectMap.Entry<BlockState> entry : changes.long2ObjectEntrySet()) {
            long pos = entry.getLongKey();
            int x = BlockPos.getX(pos) - minX;
            int y = BlockPos.getY(pos) - minY;
            int z = BlockPos.getZ(pos) - minZ;
            if (entry.getValue().isAir()) {
                builder.remove(x, y, z);
            } else {
                builder.put(x, y, z, entry.getValue());
            }
        }
        return builder.build(name, minecraft.getUser().getName(),
                new BlueprintMeta.Origin(current.server, current.dimension, minX, minY, minZ));
    }

    /** World position to new state; air means "remove this original block". Runs on the server thread. */
    private static Long2ObjectMap<BlockState> collectChanges(ServerLevel level, Blueprint snapshot, StudioSession current) {
        BlockPos min = current.min();
        Long2ObjectMap<BlockState> original = new Long2ObjectOpenHashMap<>(snapshot.blocks().size());
        for (Long2IntMap.Entry entry : snapshot.blocks().long2IntEntrySet()) {
            long relative = entry.getLongKey();
            BlockState state = snapshot.state(entry.getIntValue());
            if (state != null) {
                original.put(BlockPos.asLong(min.getX() + BlockPos.getX(relative), min.getY() + BlockPos.getY(relative),
                        min.getZ() + BlockPos.getZ(relative)), state);
            }
        }

        Long2ObjectMap<BlockState> changes = new Long2ObjectOpenHashMap<>();
        BoundingBox box = current.box();
        for (int cx = (box.minX() >> 4) - DIFF_MARGIN_CHUNKS; cx <= (box.maxX() >> 4) + DIFF_MARGIN_CHUNKS; cx++) {
            for (int cz = (box.minZ() >> 4) - DIFF_MARGIN_CHUNKS; cz <= (box.maxZ() >> 4) + DIFF_MARGIN_CHUNKS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (sections[i].hasOnlyAir()) {
                        continue;
                    }
                    int baseY = level.getSectionYFromSectionIndex(i) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = sections[i].getBlockState(x, y, z);
                                if (state.isAir()) {
                                    continue;
                                }
                                long pos = BlockPos.asLong((cx << 4) + x, baseY + y, (cz << 4) + z);
                                BlockState before = original.get(pos);
                                if (before == null || BlockStatus.compare(state, before) != BlockStatus.CORRECT) {
                                    changes.put(pos, state);
                                }
                            }
                        }
                    }
                }
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long position : original.keySet()) {
            if (level.getBlockState(pos.set(position)).isAir()) {
                changes.put(position, Blocks.AIR.defaultBlockState());
            }
        }
        return changes;
    }

    /** Saves the blueprint and remembers to place it at its origin when back in the original world. */
    public static Path saveForReturn(Blueprint blueprint) throws IOException {
        Path file = BlueprintLibrary.saveNew(blueprint);
        StudioSession current = session();
        if (current != null && blueprint.meta().origin != null) {
            current.pendingPlacement = BlueprintLibrary.relativize(file);
            current.pendingOrigin = blueprint.meta().origin.pos.clone();
            current.save();
        }
        return file;
    }

    // Going back

    public static void returnToOrigin(Minecraft minecraft) {
        StudioSession current = session();
        if (current == null) {
            return;
        }
        leaveWorld(minecraft);
        if (current.returnType.equals("local")) {
            minecraft.createWorldOpenFlows().openWorld(current.localFolder, () -> minecraft.setScreen(new TitleScreen()));
        } else {
            ServerData data = new ServerData(current.serverName, current.serverIp, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), minecraft,
                    ServerAddress.parseString(current.serverIp), data, false, null);
        }
    }

    /** Called when the world changes; places the studio blueprint once the player is back where it belongs. */
    public static void onWorldChanged(Minecraft minecraft, @Nullable String server, @Nullable String dimension) {
        StudioSession current = session();
        if (current == null || current.pendingPlacement == null
                || !current.server.equals(server) || !current.dimension.equals(dimension)) {
            return;
        }
        String file = current.pendingPlacement;
        current.pendingPlacement = null;
        current.save();
        try {
            Blueprint blueprint = BlueprintLibrary.load(file);
            int[] origin = current.pendingOrigin;
            PlacementManager.add(new Placement(file, blueprint, new BlockPos(origin[0], origin[1], origin[2]),
                    Rotation.NONE, Mirror.NONE));
            PlacementManager.setViewing(true);
            LibraryState.markUsed(file);
            PawprintClient.notify(minecraft, Component.translatable("pawprint.studio.placed_back", blueprint.meta().name));
        } catch (IOException e) {
            Pawprint.LOG.warn("Could not place the studio blueprint {}", file, e);
        }
    }
}
