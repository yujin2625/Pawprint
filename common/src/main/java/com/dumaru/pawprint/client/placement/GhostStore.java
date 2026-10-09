package com.dumaru.pawprint.client.placement;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Ghost blocks grouped by 16x16x16 world section, in flat arrays, so large blueprints cost little memory and
 * can be checked against the world and drawn one section at a time.
 *
 * <p>A target of air means "remove the block here"; a null target is a block unknown in this game.
 */
public final class GhostStore {
    private static final BlockStatus[] STATUSES = BlockStatus.values();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static final class Section {
        public final long key;
        public final int originX;
        public final int originY;
        public final int originZ;
        private final short[] local;
        private final BlockState[] targets;
        private final byte[] statuses;
        private final short[] lookup = new short[4096];
        private int size;
        /** False until the first comparison with the world; unchecked sections are not drawn. */
        private boolean checked;
        /** Set when statuses changed and the cached mesh no longer matches. */
        public boolean meshDirty = true;
        /** Cached mesh, owned by the renderer. */
        public @Nullable Object mesh;

        private Section(long key, int capacity) {
            this.key = key;
            this.originX = SectionPos.sectionToBlockCoord(SectionPos.x(key));
            this.originY = SectionPos.sectionToBlockCoord(SectionPos.y(key));
            this.originZ = SectionPos.sectionToBlockCoord(SectionPos.z(key));
            this.local = new short[capacity];
            this.targets = new BlockState[capacity];
            this.statuses = new byte[capacity];
            Arrays.fill(lookup, (short) -1);
        }

        public int size() {
            return size;
        }

        public boolean isChecked() {
            return checked;
        }

        public int x(int i) {
            return originX + (local[i] & 15);
        }

        public int y(int i) {
            return originY + (local[i] >> 8 & 15);
        }

        public int z(int i) {
            return originZ + (local[i] >> 4 & 15);
        }

        public @Nullable BlockState target(int i) {
            return targets[i];
        }

        public BlockStatus status(int i) {
            return STATUSES[statuses[i]];
        }

        private void put(int localIndex, @Nullable BlockState target) {
            short existing = lookup[localIndex];
            if (existing >= 0) {
                targets[existing] = target; // Overlapping placements: the later one wins.
                return;
            }
            lookup[localIndex] = (short) size;
            local[size] = (short) localIndex;
            targets[size] = target;
            statuses[size] = (byte) BlockStatus.MISSING.ordinal();
            size++;
        }

        private int find(int x, int y, int z) {
            return lookup[(y & 15) << 8 | (z & 15) << 4 | (x & 15)];
        }

        /** Compares every block with the world. Returns whether any status changed. */
        private boolean check(Level level) {
            LevelChunk chunk = (LevelChunk) level.getChunkSource()
                    .getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false);
            LevelChunkSection section = null;
            if (chunk != null) {
                int index = level.getSectionIndexFromSectionY(SectionPos.y(key));
                if (index >= 0 && index < chunk.getSectionsCount()) {
                    section = chunk.getSection(index);
                }
            }
            boolean changed = !checked;
            for (int i = 0; i < size; i++) {
                int l = local[i];
                BlockState actual = section == null ? AIR : section.getBlockState(l & 15, l >> 8 & 15, l >> 4 & 15);
                byte status = (byte) statusOf(targets[i], actual).ordinal();
                if (statuses[i] != status) {
                    statuses[i] = status;
                    changed = true;
                }
            }
            checked = true;
            return changed;
        }
    }

    private static BlockStatus statusOf(@Nullable BlockState target, BlockState actual) {
        if (target == null) {
            return BlockStatus.MISSING;
        }
        if (target.isAir()) {
            return actual.isAir() ? BlockStatus.CORRECT : BlockStatus.REMOVE;
        }
        return BlockStatus.compare(target, actual);
    }

    private final Long2ObjectMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private long[] order = new long[0];
    private int cursor;

    /** Replaces the contents. Old meshes are handed to {@code release} so the renderer can free them. */
    public void build(long[] positions, BlockState[] targets, int count, Consumer<Section> release) {
        sections.values().forEach(release);
        sections.clear();
        Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
        for (int i = 0; i < count; i++) {
            counts.addTo(SectionPos.blockToSection(positions[i]), 1);
        }
        for (Long2IntOpenHashMap.Entry entry : counts.long2IntEntrySet()) {
            sections.put(entry.getLongKey(), new Section(entry.getLongKey(), entry.getIntValue()));
        }
        for (int i = 0; i < count; i++) {
            long pos = positions[i];
            int x = BlockPos.getX(pos);
            int y = BlockPos.getY(pos);
            int z = BlockPos.getZ(pos);
            sections.get(SectionPos.blockToSection(pos)).put((y & 15) << 8 | (z & 15) << 4 | (x & 15), targets[i]);
        }
        order = new long[0];
        cursor = 0;
    }

    public void clear(Consumer<Section> release) {
        build(new long[0], new BlockState[0], 0, release);
    }

    public Iterable<Section> sections() {
        return sections.values();
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** The planned block at a position when it is still missing, for culling and shading between ghosts. */
    public @Nullable BlockState missingTarget(int x, int y, int z) {
        Section section = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (section == null) {
            return null;
        }
        int i = section.find(x, y, z);
        if (i < 0 || section.statuses[i] != BlockStatus.MISSING.ordinal()) {
            return null;
        }
        BlockState target = section.targets[i];
        return target == null || target.isAir() ? null : target;
    }

    /**
     * Compares sections with the world, nearest to {@code center} first, until about {@code budget} blocks were
     * checked. Sections farther than {@code range} are skipped. Call every tick.
     */
    public void updateStatus(Level level, Vec3 center, double range, int budget) {
        if (sections.isEmpty()) {
            return;
        }
        if (cursor >= order.length) {
            order = sortedByDistance(center, range);
            cursor = 0;
        }
        while (budget > 0 && cursor < order.length) {
            Section section = sections.get(order[cursor++]);
            if (section != null) {
                if (section.check(level)) {
                    section.meshDirty = true;
                }
                budget -= section.size;
            }
        }
    }

    private long[] sortedByDistance(Vec3 center, double range) {
        double maxDistance = (range + 16) * (range + 16);
        return sections.values().stream()
                .filter(section -> distanceSq(section, center) <= maxDistance)
                .sorted((a, b) -> Double.compare(distanceSq(a, center), distanceSq(b, center)))
                .mapToLong(section -> section.key)
                .toArray();
    }

    public static double distanceSq(Section section, Vec3 point) {
        double dx = section.originX + 8 - point.x;
        double dy = section.originY + 8 - point.y;
        double dz = section.originZ + 8 - point.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
