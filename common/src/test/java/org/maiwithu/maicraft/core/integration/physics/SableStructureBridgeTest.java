// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.physics;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import sun.misc.Unsafe;
import java.util.ArrayList;

/**
 * 用具有相同方法名的替身对象检查接口缺失、部分字段失败、结构身份、观察上限和已加载区块；不证明某个实际 Sable 安装版本的接口兼容。
 */
public final class SableStructureBridgeTest {
    private static final BlockPos STORAGE = new BlockPos(20_000_000, 64, -20_000_000);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        availabilityAndFields();
        identityAndRemoval();
        boundedSelection();
        localCompleteness();
        presentationSelection();
        loadedChunkBudget();
        System.out.println("SableStructureBridgeTest: passed");
    }

    private static void availabilityAndFields() {
        var absent = SableStructureBridge.openBound(() -> { throw new ClassNotFoundException("fixture"); }, null, null);
        check(absent.state().equals("not_installed") && absent.total() == -1,
                "an absent API was presented as an empty world");
        var unavailable = SableStructureBridge.openBound(Object::new, null, null);
        check(unavailable.state().equals("unsupported_api"), "a missing enumeration method was hidden");
        var empty = observe(new NativeContainer(List.of()), null, null);
        check(empty.state().equals("ready_empty") && empty.total() == 0, "known empty container became unknown");
        ShipWithoutLastPose broken = new ShipWithoutLastPose(new Counts());
        var partial = observe(new NativeContainer(List.of(broken)), null, null);
        var brokenSnapshot = partial.structures().getFirst();
        check(partial.state().equals("partial") && brokenSnapshot.errors().containsKey("last_pose")
                && brokenSnapshot.lastPose() == null, "a missing native field was presented as complete evidence");
        NativeShip loading = new NativeShip(new Counts()); loading.ready = false;
        var row = observe(new NativeContainer(List.of(loading)), null, null).structures().getFirst();
        check(Boolean.FALSE.equals(row.ready()) && row.state().equals("loading"), "not-finalized data looked ready");
        check(row.storageBounds().minX == STORAGE.getX() && row.storageBounds().maxX == STORAGE.getX() + 3.0
                && row.storageBounds().maxY == STORAGE.getY() + 2.0, "inclusive plot bounds lost the last block");
        check(row.readBlock(STORAGE).state().equals("not_loaded"), "missing loaded chunks became air");
    }

    private static void identityAndRemoval() {
        NativeShip ship = new NativeShip(new Counts());
        NativeContainer container = new NativeContainer(List.of(ship)); container.hit = ship.plot;
        var frame = observe(container, Vec3.ZERO, STORAGE);
        check(frame.resolveHit(STORAGE).state().equals("hit")
                && frame.resolveHit(STORAGE).structureId().equals(ship.id), "native plot identity did not resolve");
        check(container.lastChunkX == STORAGE.getX() >> 4 && container.lastChunkZ == STORAGE.getZ() >> 4,
                "storage hit was not converted to native chunk coordinates");
        check(frame.resolveHit(BlockPos.ZERO).state().equals("not_structure"), "world block was mislabeled as a ship");
        NativeShip replacement = new NativeShip(new Counts()); replacement.id = ship.id;
        container.hit = replacement.plot;
        check(frame.resolveHit(STORAGE).state().equals("unknown"), "same UUID hid a changed native object in an old frame");
        container.hit = ship.plot; ship.removed = true;
        check(frame.resolveHit(STORAGE).state().equals("not_loaded"), "client removal was reported as a live or destroyed ship");
        var next = observe(container, null, STORAGE);
        check(next.structures().isEmpty() && next.resolveHit(STORAGE).state().equals("not_loaded"),
                "a removed structure survived into the next frame");
        ship.removed = false;
        check(observe(container, null, STORAGE).structures().size() == 1, "a prior removal poisoned fresh observations");
    }

    private static void boundedSelection() {
        Counts counts = new Counts();
        NativeShip preferred = new NativeShip(counts);
        preferred.world = new NativeBounds(1_000_000, 0, 0, 1_000_001, 1, 1);
        List<Object> large = new AbstractList<>() {
            @Override public Object get(int index) {
                if (++counts.listReads > SableStructureBridge.MAX_METADATA_PROBES) {
                    throw new AssertionError("native structure list was scanned or copied beyond the probe budget");
                }
                NativeShip ship = new NativeShip(counts);
                ship.world = new NativeBounds(100 - index % 100, 0, 0, 101 - index % 100, 1, 1);
                return index == 9_999 ? preferred : ship;
            }
            @Override public int size() { return 10_000; }
        };
        NativeContainer container = new NativeContainer(large); container.hit = preferred.plot;
        var frame = observe(container, Vec3.ZERO, STORAGE);
        check(frame.state().equals("partial") && frame.truncated() && frame.total() == 10_000
                && frame.omitted() == 10_000 - SableStructureBridge.MAX_STRUCTURES, "structure truncation was hidden");
        check(frame.structures().size() == SableStructureBridge.MAX_STRUCTURES
                && counts.snapshots == SableStructureBridge.MAX_STRUCTURES
                && counts.metadata == SableStructureBridge.MAX_METADATA_PROBES
                && frame.metadataProbes() == SableStructureBridge.MAX_METADATA_PROBES,
                "snapshot or lightweight probe budget was exceeded");
        check(frame.structures().getFirst().id().equals(preferred.id)
                && frame.resolveHit(STORAGE).structureId().equals(preferred.id), "distant gaze target was truncated");
        check(frame.structures().get(1).worldBounds().minX == 1.0, "nearby structures were not ordered by bounds distance");
        NativeShip far = new NativeShip(new Counts()); far.world = preferred.world;
        var range = observe(new NativeContainer(List.of(far)), Vec3.ZERO, null);
        // 只确认附近为空，仍报告全局有一个结构在范围外；不能让远处已知物体阻塞本地站位。
        check(range.state().equals("ready_empty") && range.structures().isEmpty() && range.omitted() == 1
                        && range.total() == 1 && range.rangeExcluded() == 1 && range.metadataUnscanned() == 0 && !range.truncated(),
                "已知范围外结构被错误当成局部观察缺失");
        var obstacles = PhysicalObstacleSnapshot.capture(null, range.structures(), Vec3.ZERO, range.state());
        check(obstacles.state().equals("ready_empty") && obstacles.boxes().isEmpty(), "范围内已知为空仍污染了导航观察状态");
    }

    private static void localCompleteness() {
        // 范围筛选不放宽未知边界、详细对象预算或尚未探测的列表；这些仍不能证明附近可通行。
        NativeShip far = new NativeShip(new Counts()); far.world = new NativeBounds(1000,0,0,1001,1,1);
        NativeShip near = new NativeShip(new Counts());
        var mixed = observe(new NativeContainer(List.of(far,near)), Vec3.ZERO, null);
        check(mixed.state().equals("ready") && mixed.structures().size()==1 && mixed.total()==2
                && mixed.rangeExcluded()==1 && !mixed.truncated(), "远处结构不应污染已完整观察的附近结构");
        NativeShip unknown = new NativeShip(new Counts()); unknown.world = null;
        var missing = observe(new NativeContainer(List.of(far,unknown)), Vec3.ZERO, null);
        check(missing.state().equals("partial") && missing.rangeExcluded()==1
                && missing.structures().getFirst().errors().containsKey("world_bounds"), "未知边界被当成确定范围外");
        unknown.world = new NativeBounds(Double.NaN,0,0,1,1,1);
        check(observe(new NativeContainer(List.of(unknown)), Vec3.ZERO, null).state().equals("partial"), "非有限边界被当成空旷区域");
        NativeShip boundary = new NativeShip(new Counts()); boundary.world = new NativeBounds(128,0,0,129,1,1);
        check(observe(new NativeContainer(List.of(boundary)), Vec3.ZERO, null).rangeExcluded()==0, "范围边界上的结构被提前排除");
        boundary.world = new NativeBounds(128.001,0,0,129,1,1);
        check(observe(new NativeContainer(List.of(boundary)), Vec3.ZERO, null).rangeExcluded()==1, "确定超出范围的边界没有记数");
        var farMany = new ArrayList<NativeShip>();
        for(int i=0;i<129;i++) { var ship=new NativeShip(new Counts());ship.world=far.world;farMany.add(ship); }
        var unscanned = observe(new NativeContainer(farMany), Vec3.ZERO, null);
        check(unscanned.state().equals("partial") && unscanned.truncated() && unscanned.rangeExcluded()==128
                && unscanned.metadataUnscanned()==1 && unscanned.total()==129, "未探测的最后一项不能推断为也在远处");
        var nearMany = new ArrayList<NativeShip>();
        for(int i=0;i<17;i++) nearMany.add(new NativeShip(new Counts()));
        nearMany.add(far);
        var detailLimit = observe(new NativeContainer(nearMany), Vec3.ZERO, null);
        check(detailLimit.state().equals("partial") && detailLimit.truncated() && detailLimit.rangeExcluded()==1
                && detailLimit.metadataUnscanned()==0 && detailLimit.structures().size()==16, "范围内详情截断不能被范围外计数抵消");
        var gaze = new NativeContainer(List.of(far));gaze.hit=far.plot;
        var selected = observe(gaze, Vec3.ZERO, STORAGE);
        check(selected.state().equals("ready") && selected.structures().size()==1 && selected.rangeExcluded()==0
                && selected.metadataUnscanned()==0, "原生准星优先对象应保留，不能同时算作范围排除");
        // 原生列表与准星句柄若在同次读取间发生变化，额外的优先目标仍要计入详情预算。
        var changedList = new NativeContainer(nearMany.subList(0,16));changedList.hit=far.plot;
        check(observe(changedList, Vec3.ZERO, STORAGE).state().equals("partial"), "额外准星对象掩盖了范围内详情截断");
        var unknownWithFar = PhysicalObstacleSnapshot.capture(null, List.of(), Vec3.ZERO, unscanned.state());
        check(unknownWithFar.state().equals("partial"), "未知列表被障碍快照清洗成可用局部证据");
    }

    private static void loadedChunkBudget() throws Exception {
        var singleton = Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        FakeChunk chunk = (FakeChunk) ((Unsafe) singleton.get(null)).allocateInstance(FakeChunk.class);
        chunk.position = new ChunkPos(STORAGE);
        Counts counts = new Counts(); NativeShip ship = new NativeShip(counts);
        ship.plot.holders = Collections.nCopies(1_000, new NativeHolder(chunk, counts));
        var frame = observe(new NativeContainer(List.of(ship)), null, null);
        var row = frame.structures().getFirst();
        check(counts.chunkReads == SableStructureBridge.MAX_LOADED_CHUNKS
                && row.loadedChunks().size() == SableStructureBridge.MAX_LOADED_CHUNKS
                && frame.truncated() && frame.state().equals("partial"), "chunk observation was not capped");
        check(row.isLoaded(STORAGE) && row.readBlock(STORAGE).blockState().is(Blocks.STONE),
                "a known retained chunk became unknown because other chunks were truncated");
        BlockPos omittedChunk = STORAGE.offset(32, 0, 0);
        check(!row.isLoaded(omittedChunk) && row.readBlock(omittedChunk).state().equals("unknown")
                && chunk.blockReads == 1, "omitted chunk was read, loaded, or treated as air");
        ship.plot.holders = List.of(new BrokenHolder());
        var unknown = observe(new NativeContainer(List.of(ship)), null, null).structures().getFirst();
        check(unknown.errors().containsKey("loaded_chunks") && unknown.readBlock(STORAGE).state().equals("unknown"),
                "a failed native chunk handle became a known unloaded chunk");
    }

    private static SableStructureBridge.Frame observe(NativeContainer container, Vec3 eye, BlockPos hit) {
        return SableStructureBridge.openBound(() -> container, eye, hit);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void presentationSelection() {
        Counts counts = new Counts();
        var nativeList = new ArrayList<NativeShip>();
        for (int i = 0; i < 30; i++) {
            var small = new NativeShip(counts); small.world = new NativeBounds(i,0,0,i+1,1,1); nativeList.add(small);
        }
        var ship = new NativeShip(counts); ship.world = new NativeBounds(50,10,0,72,24,12); nativeList.add(ship);
        var container = new NativeContainer(nativeList);
        var frame = SableStructureBridge.openBound(() -> container,Vec3.ZERO,null,true);
        check(frame.structures().stream().anyMatch(s -> ship.id.equals(s.id())),
                "small nearby objects cannot fill the full-snapshot budget before the vessel");
        check(frame.metadataProbes() <= 128 && frame.structures().size() <= 16, "presentation retains native observation budgets");
    }
    private static final class Counts { int listReads, metadata, snapshots, chunkReads; }

    public static final class NativeContainer {
        private final List<?> structures;
        NativePlot hit;
        int lastChunkX, lastChunkZ;
        NativeContainer(List<?> structures) { this.structures = structures; }
        public List<?> getAllSubLevels() { return structures; }
        public NativePlot getPlot(int x, int z) {
            lastChunkX = x; lastChunkZ = z;
            return x == STORAGE.getX() >> 4 && z == STORAGE.getZ() >> 4 ? hit : null;
        }
        public Object getChunk(ChunkPos ignored) { throw new AssertionError("container must not load chunks"); }
    }
    public static class ShipWithoutLastPose {
        UUID id = UUID.randomUUID();
        boolean removed, ready = true;
        final Counts counts;
        final NativePlot plot = new NativePlot(this);
        final NativePose pose = new NativePose();
        NativeBounds world = new NativeBounds(0, 0, 0, 3, 2, 3);
        ShipWithoutLastPose(Counts counts) { this.counts = counts; }
        public boolean isRemoved() { return removed; }
        public UUID getUniqueId() { return id; }
        public String getName() { return null; }
        public boolean isFinalized() { return ready; }
        public NativePose logicalPose() { counts.snapshots++; return pose; }
        public NativeBounds boundingBox() { counts.metadata++; return world; }
        public NativePlot getPlot() { return plot; }
    }
    public static final class NativeShip extends ShipWithoutLastPose {
        NativeShip(Counts counts) { super(counts); }
        public NativePose lastPose() { return pose; }
    }
    public static final class NativePlot {
        private final Object ship;
        Iterable<?> holders = List.of();
        NativePlot(Object ship) { this.ship = ship; }
        public Object getSubLevel() { return ship; }
        public BlockPos getCenterBlock() { return STORAGE; }
        public NativeBounds getBoundingBox() {
            return new NativeBounds(STORAGE.getX(), STORAGE.getY(), STORAGE.getZ(),
                    STORAGE.getX() + 2, STORAGE.getY() + 1, STORAGE.getZ() + 2);
        }
        public Iterable<?> getLoadedChunks() { return holders; }
        public Object getChunk(ChunkPos ignored) { throw new AssertionError("plot must not load chunks"); }
    }
    public record NativeBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public static final class NativePose {
        public Vector3d position() { return new Vector3d(); }
        public Quaterniond orientation() { return new Quaterniond(); }
        public Vector3d rotationPoint() { return new Vector3d(STORAGE.getX(), STORAGE.getY(), STORAGE.getZ()); }
        public Vector3d scale() { return new Vector3d(1); }
    }
    public record NativeHolder(LevelChunk chunk, Counts counts) {
        public LevelChunk getChunk() { counts.chunkReads++; return chunk; }
    }
    public static final class BrokenHolder {
        public LevelChunk getChunk() { throw new IllegalStateException("fixture chunk unavailable"); }
    }
    /** 不调用构造器；直接分配实例即可在没有 Minecraft 世界的情况下提供区块夹具。 */
    public static final class FakeChunk extends LevelChunk {
        ChunkPos position;
        int blockReads;
        private FakeChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public ChunkPos getPos() { return position; }
        @Override public BlockState getBlockState(BlockPos pos) { blockReads++; return Blocks.STONE.defaultBlockState(); }
    }
}
