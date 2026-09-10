package org.maiwithu.maicraft.core.integration.physics;

import baritone.pathing.movement.CollisionGeometry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

/**
 * 用给定结构位置和方块检查碰撞转换、门洞保留、贴边后退、实时更新与后台快照隔离，以及读取不足时的保守障碍。
 */
public final class PhysicalObstacleSnapshotTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BlockPos stored = new BlockPos(20481031, 128, 20521992);
        var pose = new StructurePose(new Vec3(-70.5,106.49735260009766,2.4999616146087646),
                9.431269961238353E-6,4.74058760437751E-5,1.2503789533062188E-5,0.9999999987536947,
                new Vec3(20481031.5,128.5,20521992.5), new Vec3(1,1,1));
        Scene scene = new Scene(); scene.blocks.put(stored, Blocks.STONE.defaultBlockState());
        var ship = structure(pose, new AABB(stored), List.of(chunk(stored, scene)));
        Vec3 feet = new Vec3(-70.49819085851152,106,3.299970815049502);
        var snapshot = PhysicalObstacleSnapshot.capture(scene, List.of(ship), feet, "ready");
        check(snapshot.boxes().size() == 1 && snapshot.conservativeStructures() == 0,
                "native stored voxel must become one world obstacle without using plot coordinates as world cells");
        AtomicReference<PhysicalObstacleSnapshot> observed = new AtomicReference<>(snapshot);
        BlockPos start = new BlockPos(-71,106,3);
        var live = new CollisionGeometry(scene, false, feet, start, observed::get, .6,1.8);
        var worker = new CollisionGeometry(scene, true, feet, start, observed::get, .6,1.8);
        check(!live.clear(-71,106,3,-71,106,2), "the recorded straight route must reject the physical crafter");
        check(live.clear(-71,106,3,-71,106,4), "a body touching the crafter edge must be allowed to back away");
        observed.set(PhysicalObstacleSnapshot.EMPTY);
        check(live.clear(-71,106,3,-71,106,2), "live cost checks must notice an obstacle moving away or unloading");
        check(!worker.clear(-71,106,3,-71,106,2), "a search worker must retain its immutable collision generation");
        openingsAndBudget();
        System.out.println("PhysicalObstacleSnapshotTest: passed");
    }

    private static void openingsAndBudget() throws Exception {
        Scene scene = new Scene();
        for (int y = 0; y < 3; y++) {
            scene.blocks.put(new BlockPos(0,y,0), Blocks.STONE.defaultBlockState());
            scene.blocks.put(new BlockPos(3,y,0), Blocks.STONE.defaultBlockState());
        }
        var pose = new StructurePose(Vec3.ZERO,0,0,0,1,Vec3.ZERO,new Vec3(1,1,1));
        AABB bounds = new AABB(0,0,0,4,3,1);
        var frame = structure(pose, bounds, List.of(chunk(BlockPos.ZERO, scene)));
        var open = PhysicalObstacleSnapshot.capture(scene, List.of(frame), new Vec3(1.5,0,2), "ready");
        check(open.clearSegment(new Vec3(1.5,0,2), new Vec3(1.5,0,-1), .6,1.8),
                "assembled frame openings must remain open instead of treating the entire hull bounds as solid");
        check(!open.clearSegment(new Vec3(1.5,0,2), new Vec3(1.5,0,-1), 2.8,1.8),
                "the same opening must reject a wider body");
        var missing = structure(pose, bounds, List.of());
        var unknown = PhysicalObstacleSnapshot.capture(scene, List.of(missing), new Vec3(1.5,0,2), "partial");
        check(unknown.conservativeStructures() == 1 && !unknown.clearSegment(new Vec3(1.5,0,2),new Vec3(1.5,0,-1),.6,1.8),
                "unloaded structure voxels must not create an unobserved corridor");
        Scene huge = new Scene();
        var chunks = new java.util.ArrayList<LevelChunk>();
        for (int x = 0; x < 32; x += 16) for (int z = 0; z < 32; z += 16)
            chunks.add(chunk(new BlockPos(x,0,z), huge));
        var large = structure(pose,new AABB(0,0,0,32,64,32),chunks);
        var bounded = PhysicalObstacleSnapshot.capture(huge,List.of(large),new Vec3(16,32,16),"ready");
        check(bounded.blockReads() <= 4096 && bounded.conservativeStructures() == 1 && huge.reads <= 4096,
                "large nearby structures must use a bounded capture and conservative incomplete fallback");
    }

    private static SableStructureBridge.Structure structure(StructurePose pose, AABB storage, List<LevelChunk> chunks) {
        return new SableStructureBridge.Structure(UUID.randomUUID(),null,true,pose,pose,
                PhysicalObstacleSnapshot.transformBox(pose, storage, true),BlockPos.containing(pose.pivot()),storage,chunks,Map.of());
    }
    private static LevelChunk chunk(BlockPos pos, Scene scene) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var chunk = (TestChunk)((Unsafe)field.get(null)).allocateInstance(TestChunk.class);
        chunk.position = new ChunkPos(pos); chunk.scene = scene; return chunk;
    }
    public static final class TestChunk extends LevelChunk {
        ChunkPos position; Scene scene;
        private TestChunk() { super(null,new ChunkPos(0,0)); }
        public ChunkPos getPos() { return position; }
        public BlockState getBlockState(BlockPos pos) {
            if (!new ChunkPos(pos).equals(position)) throw new AssertionError("read outside loaded chunk");
            scene.reads++; return scene.blocks.getOrDefault(pos,Blocks.AIR.defaultBlockState());
        }
    }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos,BlockState> blocks = new HashMap<>(); int reads;
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos,(pos.getY()==105 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("unexpected block entity read"); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return 384; } public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
