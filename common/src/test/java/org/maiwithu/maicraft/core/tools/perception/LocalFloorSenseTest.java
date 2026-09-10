package org.maiwithu.maicraft.core.tools.perception;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * 用原版碰撞形状构造楼层、墙、半砖和楼梯口，检查观察结果是否区分可见地面、楼下和未验证的路线。
 * 未知区块由测试自己提供的判断模拟；没有覆盖现场 hasChunkAt 的语义，也没有覆盖邻格伸出的碰撞。
 */
public final class LocalFloorSenseTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Scene building = new Scene();
        JsonObject sense = LocalFloorSense.describe(building, pos -> true,
                new Vec3(-83.24, 115, -13.31), 0.6, 1.8, 1.62);
        JsonObject current = floor(sense, 115), downstairs = floor(sense, 103);
        check(current != null && downstairs != null, "roof height hid the current or lower floor");
        check(current.get("visible").getAsBoolean() && !downstairs.get("visible").getAsBoolean(),
                "the lower floor was falsely visible through the intervening slab");
        check(downstairs.get("relative_height").getAsDouble() == -12
                        && !downstairs.get("route_verified").getAsBoolean(),
                "a loaded lower floor was mistaken for a verified stair route");
        check(current.getAsJsonObject("feet_cell").get("x").getAsInt() == -84
                        && current.getAsJsonObject("feet_cell").get("z").getAsInt() == -14,
                "negative stance coordinates were truncated instead of floored");
        for (var value : sense.getAsJsonArray("standable_regions")) {
            JsonObject region = value.getAsJsonObject();
            check(!region.has("max_y") || region.get("max_y").getAsDouble() == 115,
                    "roof Y139 or hidden downstairs Y103 contaminated the current-floor statistics");
        }
        check(building.maxReadY < 138, "local perception unnecessarily read the roof height");

        Scene divided = new Scene(); divided.wall = true;
        JsonObject partition = describe(divided);
        for (var value : partition.getAsJsonArray("standable_regions")) {
            JsonObject region = value.getAsJsonObject();
            if (region.get("region").getAsString().equals("east")) {
                check(region.get("standable_fraction").getAsDouble() == 0 && !region.has("min_y"),
                        "two-block sampling stepped through a one-block wall and claimed visible floor");
            }
        }
        Scene slab = new Scene(); slab.slabs = true;
        JsonObject half = floor(describe(slab), 114.5);
        check(half != null && half.getAsJsonObject("feet_cell").get("y").getAsInt() == 114,
                "a bottom slab lost its exact support height or canonical feet cell");
        Scene low = new Scene(); low.lowCeiling = true;
        check(floor(describe(low), 115) == null, "a floor without player-height clearance was marked standable");
        Scene stairs = new Scene(); stairs.opening = true; stairs.ladder = true;
        JsonObject opening = describe(stairs);
        check(opening.getAsJsonArray("local_features").size() > 0, "visible ladder evidence was omitted");
        check(opening.getAsJsonArray("hazards").toString().contains("drop_or_lower_floor"),
                "an open lower level was not distinguished from supported current floor");
        Scene unloaded = new Scene(); unloaded.rejectEastReads = true;
        JsonObject partial = LocalFloorSense.describe(unloaded, pos -> pos.getX() <= 0,
                new Vec3(0.5, 115, 0.5), 0.6, 1.8, 1.62);
        check(partial.get("partial").getAsBoolean() && partial.get("block_reads").getAsInt() <= 8192,
                "unloaded or bounded sampling was presented as complete geometry");
        System.out.println("LocalFloorSenseTest: passed");
    }

    private static JsonObject describe(Scene scene) {
        return LocalFloorSense.describe(scene, pos -> true, new Vec3(0.5, 115, 0.5), 0.6, 1.8, 1.62);
    }
    private static JsonObject floor(JsonObject sense, double y) {
        for (var value : sense.getAsJsonArray("floor_candidates")) {
            JsonObject floor = value.getAsJsonObject();
            if (floor.getAsJsonObject("position").get("y").getAsDouble() == y) return floor;
        }
        return null;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Scene implements BlockGetter {
        boolean wall, slabs, lowCeiling, opening, ladder, rejectEastReads;
        int maxReadY = Integer.MIN_VALUE;
        public BlockState getBlockState(BlockPos pos) {
            if (rejectEastReads && pos.getX() > 0) throw new AssertionError("read an unloaded block");
            maxReadY = Math.max(maxReadY, pos.getY());
            if (wall && pos.getX() == 1 && pos.getY() >= 115 && pos.getY() < 119) return Blocks.STONE.defaultBlockState();
            if (lowCeiling && pos.getY() == 116) return Blocks.STONE.defaultBlockState();
            if (ladder && pos.equals(new BlockPos(0, 115, 2))) return Blocks.LADDER.defaultBlockState();
            if (pos.getY() == 138 || pos.getY() == 102) return Blocks.STONE.defaultBlockState();
            if (pos.getY() == 114 && (!opening || pos.getX() < 2))
                return (slabs ? Blocks.STONE_SLAB : Blocks.STONE).defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
