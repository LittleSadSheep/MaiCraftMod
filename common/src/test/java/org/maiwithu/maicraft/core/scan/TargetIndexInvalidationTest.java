// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.scan;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import sun.misc.Unsafe;

/** 两个真实区块段按建索引预算分次扫描，检查无关观察是否迫使查询重走已完成的前缀。 */
public final class TargetIndexInvalidationTest {
    private static final BlockPos CENTER = new BlockPos(1, 1, 1);
    private static final BlockPos LOWER = new BlockPos(2, 1, 1), UPPER = new BlockPos(1, 17, 1);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        unrelatedType(false);
        unrelatedType(true);
        outsideQueryOrExcluded(false);
        outsideQueryOrExcluded(true);
        relatedChange(false);
        relatedChange(true);
        System.out.println("TargetIndexInvalidationTest: unrelated observations preserve pending scans and relevant changes refresh them");
    }

    private static void unrelatedType(boolean released) throws Exception {
        try (var f = new Fixture()) {
            if (released) TargetIndex.unregister(f.level, Set.of(Blocks.STONE));
            check(!f.query(Set.of()).complete(), "the first section must leave a genuine pending scan");
            f.change(LOWER, Blocks.AIR.defaultBlockState());
            int reads = f.chunks.reads;
            check(f.query(Set.of()).hits().equals(List.of(UPPER)), "the resumed query must still find the upper ore");
            check(f.chunks.reads - reads <= 2, "unrelated block changes must not rescan the completed lower section");
            if (released) TargetIndex.register(f.level, Set.of(Blocks.STONE));
            f.clock();
            check(TargetIndex.query(f.level, CENTER, Set.of(Blocks.STONE), 1, 0, 2).hits().isEmpty(),
                    "a zero-reference warm section must still receive relevant block updates before reuse");
        }
    }

    private static void outsideQueryOrExcluded(boolean excluded) throws Exception {
        try (var f = new Fixture()) {
            Set<BlockPos> exclusions = excluded ? Set.of(LOWER) : Set.of();
            check(!f.query(exclusions).complete(), "the upper section must remain unscanned");
            if (excluded) f.change(LOWER, Blocks.IRON_ORE.defaultBlockState());
            else for (BlockPos outside : List.of(new BlockPos(16, 1, 1), new BlockPos(-1, 1, 1),
                    new BlockPos(1, 1, 16), new BlockPos(1, 1, -1))) {
                TargetIndex.onBlockChange(f.level, outside,
                        Blocks.AIR.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState());
            }
            int reads = f.chunks.reads;
            var result = f.query(exclusions);
            check(result.complete() && result.hits().equals(List.of(UPPER)), "only in-range, non-excluded ore is returned");
            check(f.chunks.reads - reads <= 2, "changes outside this query's candidates must preserve its cursor");
        }
    }

    private static void relatedChange(boolean releasedBetweenTicks) throws Exception {
        try (var f = new Fixture()) {
            check(!f.query(Set.of()).complete(), "the lower section is scanned before the observation changes");
            if (releasedBetweenTicks) TargetIndex.unregister(f.level, Set.of(Blocks.IRON_ORE));
            f.change(LOWER, Blocks.IRON_ORE.defaultBlockState());
            if (releasedBetweenTicks) TargetIndex.register(f.level, Set.of(Blocks.IRON_ORE));
            check(f.query(Set.of()).hits().equals(List.of(LOWER)),
                    "new nearer ore in a scanned section must invalidate the old partial result");
            f.change(LOWER, Blocks.AIR.defaultBlockState());
            check(f.query(Set.of()).hits().equals(List.of(UPPER)),
                    "removing a completed hit must invalidate the completed result too");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final World level;
        final Chunks chunks;
        final LevelChunkSection[] sections = new LevelChunkSection[2];

        Fixture() throws Exception {
            TargetIndex.dropAll();
            Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            level = (World) memory.allocateInstance(World.class);
            chunks = (Chunks) memory.allocateInstance(Chunks.class);
            chunks.chunk = (LevelChunk) memory.allocateInstance(LevelChunk.class);
            for (int i = 0; i < sections.length; i++) {
                sections[i] = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                        Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES), null);
            }
            field(LevelChunk.class, "sections").set(chunks.chunk, sections);
            field(Level.class, "dimension").set(level, Level.OVERWORLD);
            level.chunks = chunks;
            change(LOWER, Blocks.STONE.defaultBlockState());
            change(UPPER, Blocks.IRON_ORE.defaultBlockState());
            TargetIndex.register(level, Set.of(Blocks.STONE, Blocks.IRON_ORE));
        }

        void change(BlockPos pos, BlockState state) {
            LevelChunkSection section = sections[pos.getY() >> 4];
            BlockState old = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
            TargetIndex.onBlockChange(level, pos, old, state);
        }

        TargetIndex.Result query(Set<BlockPos> excluded) throws Exception {
            clock();
            return TargetIndex.query(level, CENTER, Set.of(Blocks.IRON_ORE), 1, 0, 1, excluded);
        }

        void clock() throws Exception {
            // 固定墙钟预算，只检验每次构建一个 section 的让步与续扫，避免机器速度影响断言。
            level.tick++;
            field(TargetIndex.class, "queryTick").setLong(null, level.tick);
            field(TargetIndex.class, "queryDeadline").setLong(null, Long.MAX_VALUE);
        }

        @Override public void close() { TargetIndex.dropAll(); }
    }

    private static final class World extends ClientLevel {
        Chunks chunks;
        long tick;
        private World() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public ClientChunkCache getChunkSource() { return chunks; }
        @Override public int getHeight() { return 32; }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public long getGameTime() { return tick; }
    }

    private static final class Chunks extends ClientChunkCache {
        LevelChunk chunk;
        int reads;
        private Chunks() { super(null, 0); }
        @Override public LevelChunk getChunk(int x, int z, ChunkStatus status, boolean load) {
            if (load) throw new AssertionError("a target query must not load terrain");
            reads++;
            return x == 0 && z == 0 ? chunk : null;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
