// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

// 检查甲板报告保留正确坐标、头顶空间、未知读取、倾斜面和预算限制，防止候选报告冒充已站稳或已规划好的路线。
public final class PhysicalDeckSamplerTest {
    public static void main(String[] args) {
        World world = new World();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) world.blocks.put(new BlockPos(x,0,z), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1,2,1), Blocks.STONE.defaultBlockState());
        var bounds = new AABB(0,0,0,3,3,3);
        var pose = new StructurePose(new Vec3(20,100,30),0,0,0,1,Vec3.ZERO,new Vec3(1,1,1));
        var sample = PhysicalDeckSampler.sample(world, p -> true, pose, bounds, BlockPos.ZERO, new Vec3(1,0,1), .6,1.8);
        check(sample.get("state").getAsString().equals("sampled"), "fully loaded bounded sample should be usable evidence");
        check(!sample.getAsJsonArray("support_surface_candidates").isEmpty(), "broad native floor should yield support candidates");
        for (var entry : sample.getAsJsonArray("support_surface_candidates")) {
            var row = entry.getAsJsonObject();
            var storage = row.getAsJsonObject("block_storage");
            check(!(storage.get("x").getAsInt()==1 && storage.get("y").getAsInt()==0 && storage.get("z").getAsInt()==1),
                    "low roof must reject the full player's body under it");
            check(!row.get("standing_verified").getAsBoolean() && !row.get("route_verified").getAsBoolean(),
                    "native voxel samples must never claim a completed touchdown or route");
            check(row.getAsJsonObject("surface_world").get("y").getAsDouble() >= 101,
                    "world-space support points must include the structure pose");
        }
        world.forbiddenX = 2;
        var partial = PhysicalDeckSampler.sample(world, p -> p.getX()!=2, pose, bounds, BlockPos.ZERO, new Vec3(1,0,1), .6,1.8);
        check(partial.get("state").getAsString().equals("partial") && partial.get("unknown_reads").getAsInt()>0,
                "missing plot chunks must remain unknown instead of becoming empty clearance");
        world.forbiddenX = Integer.MIN_VALUE;
        Quaterniond tilted = new Quaterniond().rotateZ(Math.toRadians(10));
        var tilt = new StructurePose(Vec3.ZERO, tilted.x, tilted.y, tilted.z, tilted.w, Vec3.ZERO, new Vec3(1,1,1));
        var tiltedSample = PhysicalDeckSampler.sample(world, p -> true, tilt, bounds, BlockPos.ZERO, Vec3.ZERO, .6,1.8);
        check(!tiltedSample.getAsJsonArray("support_surface_candidates").isEmpty(), "a mildly tilted broad surface should still produce qualified candidates");
        var vertical = new StructurePose(Vec3.ZERO, 0,0,Math.sqrt(.5),Math.sqrt(.5),Vec3.ZERO,new Vec3(1,1,1));
        var sideways = PhysicalDeckSampler.sample(world, p -> true, vertical, bounds, BlockPos.ZERO, Vec3.ZERO, .6,1.8);
        check(sideways.getAsJsonArray("support_surface_candidates").isEmpty(), "a vertical hull face must not be labeled an upright support candidate");
        var tiny = new StructurePose(Vec3.ZERO,0,0,0,1,Vec3.ZERO,new Vec3(1e-12,1e-12,1e-12));
        var extreme = PhysicalDeckSampler.sample(world, p -> true, tiny, bounds, BlockPos.ZERO, Vec3.ZERO,.6,1.8);
        check(extreme.getAsJsonArray("support_surface_candidates").isEmpty(), "extreme inverse body bounds must not overflow grid loops into false clearance");
        World tower = new World();
        for (int y = 0; y < 300; y += 3) tower.blocks.put(new BlockPos(0,y,0), Blocks.STONE.defaultBlockState());
        var lowerDeck = PhysicalDeckSampler.sample(tower, p -> true, pose, new AABB(0,0,0,1,300,1),
                BlockPos.ZERO, new Vec3(.5,1,.5), .6,1.8);
        check(lowerDeck.getAsJsonArray("support_surface_candidates").get(0).getAsJsonObject()
                        .getAsJsonObject("block_storage").get("y").getAsInt() == 0,
                "many higher floors cannot consume the budget before sampling the viewed lower deck");
        var huge = PhysicalDeckSampler.sample(new World(), p -> true, pose, new AABB(0,-64,0,100,320,100), BlockPos.ZERO,
                new Vec3(50,0,50), .6,1.8);
        check(huge.get("budget_exhausted").getAsBoolean() && huge.get("block_reads").getAsInt()<=8192,
                "large plots must yield a partial bounded observation, not scan the whole plotyard");
        System.out.println("PhysicalDeckSamplerTest: passed");
    }
    private static final class World implements BlockGetter {
        final Map<BlockPos,BlockState> blocks = new HashMap<>(); int forbiddenX = Integer.MIN_VALUE;
        public BlockState getBlockState(BlockPos p) {
            if (p.getX()==forbiddenX) throw new AssertionError("unloaded block was read");
            return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
        }
        public BlockEntity getBlockEntity(BlockPos p) { return null; }
        public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
