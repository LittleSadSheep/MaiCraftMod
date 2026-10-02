package org.maiwithu.maicraft.core.scan;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import sun.misc.Unsafe;

/** 完整高度的石层分刻扫描：不重读前缀、不截断候选，卸载只记未知，较晚的可见目标仍可抵达核查。 */
public final class LoadedBlockScanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        denseColumnIsLinear();
        lateCandidateSurvivesManyHiddenBatches();
        unloadingDoesNotReadStaleSections();
        horizontalRadiusDoesNotInventUnknownColumns();
        tinyTimeSliceStillMakesRealProgress();
        System.out.println("LoadedBlockScanTest: passed");
    }

    private static void denseColumnIsLinear() throws Exception {
        var world = world(); var seen = new HashSet<BlockPos>();
        var scan = new LoadedBlockScan(world, new BlockPos(8, 77, 8), List.of(Blocks.STONE), 16);
        long previous = 0;
        while (!scan.complete()) {
            long examined = scan.examinedStates();
            check(!scan.advance(37, Long.MAX_VALUE, pos -> {
                check(seen.add(pos), "a candidate was rescanned after yielding"); return false;
            }), "hidden candidates cannot end the scan early");
            check(scan.processedCells() > previous, "budget exhaustion preserves forward progress");
            check(scan.examinedStates() - examined <= 37, "cell budget is honored inside saturated sections");
            previous = scan.processedCells();
        }
        check(seen.size() == 16 * 16 * 384 && scan.candidates() == seen.size(), "all loaded height samples are inspected once");
        check(scan.processedCells() == scan.totalCells() && scan.unloadedSections() == 8 * 24,
                "unknown surrounding columns are accounted for without being read");
    }

    private static void lateCandidateSurvivesManyHiddenBatches() throws Exception {
        var scan = new LoadedBlockScan(world(), new BlockPos(8, 77, 8), List.of(Blocks.STONE), 16);
        BlockPos late = new BlockPos(15, 319, 15);
        boolean found = false;
        while (!scan.complete() && !found) found = scan.advance(64, Long.MAX_VALUE, late::equals);
        check(found && scan.candidates() > 64, "a visible candidate after many hidden batches remains searchable");
    }

    private static void unloadingDoesNotReadStaleSections() throws Exception {
        var world = world();
        var scan = new LoadedBlockScan(world, new BlockPos(8, 77, 8), List.of(Blocks.STONE), 16);
        scan.advance(7, Long.MAX_VALUE, pos -> false);
        world.chunks.loaded = false;
        while (!scan.complete()) scan.advance(32, Long.MAX_VALUE, pos -> { throw new AssertionError("read stale unloaded section"); });
        check(scan.candidates() == 7 && scan.unloadedSections() > 0, "unloading preserves only already checked evidence");
    }

    private static World world() throws Exception {
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        World world = (World) memory.allocateInstance(World.class);
        world.chunks = (Chunks) memory.allocateInstance(Chunks.class); world.chunks.loaded = true;
        world.chunks.chunk = (LevelChunk) memory.allocateInstance(LevelChunk.class);
        var sections = new LevelChunkSection[24];
        for (int i = 0; i < sections.length; i++) {
            // 所有高度都使用原生密集调色板；扫描不能把饱和标志当作只有一个候选。
            sections[i] = new LevelChunkSection(new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY, Blocks.STONE.defaultBlockState(),
                    PalettedContainer.Strategy.SECTION_STATES), null);
        }
        field(LevelChunk.class, "sections").set(world.chunks.chunk, sections);
        return world;
    }

    private static void tinyTimeSliceStillMakesRealProgress() throws Exception {
        var scan = new LoadedBlockScan(world(), new BlockPos(8, 77, 8), List.of(Blocks.STONE), 16);
        // 冷区块准备比时间片更长时也完成一个最小工作单元，不能只反复构造查询却一直续期。
        for (int step = 0; step < 4; step++) {
            long before = scan.processedCells();
            scan.advance(4, 1, pos -> false);
            check(scan.processedCells() > before && scan.processedCells() - before <= 4,
                    "a tiny CPU slice must advance the retained cursor without discarding facts");
        }
    }

    private static void horizontalRadiusDoesNotInventUnknownColumns() throws Exception {
        var scan = new LoadedBlockScan(world(), new BlockPos(8, 77, 8), List.of(Blocks.STONE), 4);
        while (!scan.complete()) scan.advance(1024, Long.MAX_VALUE, pos -> {
            int dx = pos.getX() - 8, dz = pos.getZ() - 8;
            check(dx * dx + dz * dz <= 16, "candidate must lie inside the declared horizontal radius"); return false;
        });
        check(scan.totalSections() == 24 && scan.unloadedSections() == 0,
                "out-of-scope neighboring chunks are not reported as unknown search terrain");
        check(scan.candidates() == 49L * 384, "the complete vertical cylinder remains covered");
    }

    private static final class World extends ClientLevel {
        Chunks chunks;
        private World() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public ClientChunkCache getChunkSource() { return chunks; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
    }

    private static final class Chunks extends ClientChunkCache {
        LevelChunk chunk; boolean loaded;
        private Chunks() { super(null, 0); }
        @Override public LevelChunk getChunk(int x, int z, ChunkStatus status, boolean load) {
            if (load) throw new AssertionError("read-only scan tried to load terrain");
            return loaded && x == 0 && z == 0 ? chunk : null;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        // 原生区块把段数组保存在父类；夹具沿继承层级找到真实字段，不替扫描器伪造查询结果。
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field result = owner.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
