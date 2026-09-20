// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import net.minecraft.tags.FluidTags;

/** 从实际连通流体发现加工区域和地面站位；池子形状由蓝图或现有地形决定，不内置某种材料的固定池模板。 */
final class WorldProcessSite {
    record Stand(BlockPos feet, BlockPos receiver) {}
    // 与生产日志每连接最多保留的端点数相符；无法完整覆盖的大水域必须另选有界接收区，不能只监测其中一角。
    private static final int MAX_CELLS = 32;
    private final Level world;
    private final Map<BlockPos, BlockState> structure;
    final List<BlockPos> fluids;
    final AABB region;

    private WorldProcessSite(Level world, Map<BlockPos, BlockState> structure, List<BlockPos> fluids, AABB region) {
        this.world = world; this.structure = Map.copyOf(structure); this.fluids = List.copyOf(fluids); this.region = region;
    }

    static WorldProcessSite inspect(LocalPlayer player, BlockPos anchor, WorldProcessRecipe recipe) {
        Level world = player.level();
        var seen = new HashSet<BlockPos>(); var queue = new ArrayDeque<BlockPos>(); queue.add(anchor);
        Map<BlockPos, BlockState> structure = new LinkedHashMap<>(); var cells = new ArrayList<BlockPos>(); AABB bounds = null;
        while (!queue.isEmpty()) {
            BlockPos at = queue.remove().immutable(); if (!seen.add(at)) continue;
            if (!world.isLoaded(at)) throw new IllegalArgumentException("world_process_receiver_unloaded");
            BlockState state = world.getBlockState(at); structure.put(at, state);
            if (!recipe.supports(state.getFluidState()) || !state.getCollisionShape(world, at).isEmpty()) continue;
            if (cells.size() >= MAX_CELLS || at.distManhattan(anchor) > 16)
                throw new IllegalArgumentException("world_process_receiver_not_bounded");
            cells.add(at); bounds = bounds == null ? new AABB(at) : bounds.minmax(new AABB(at));
            for (Direction side : Direction.values()) queue.add(at.relative(side));
        }
        if (cells.isEmpty()) throw new IllegalArgumentException("world_process_required_environment_missing");
        // 记录池壁、池底和上方空间，运行中出现破口或环境改变时停止，不往未知水流继续扔料。
        return new WorldProcessSite(world, structure, cells, bounds.inflate(1.1));
    }

    void requireUnchanged(LocalPlayer player) {
        if (player.level() != world) throw new IllegalStateException("world_process_world_changed");
        for (var entry : structure.entrySet()) if (!world.isLoaded(entry.getKey())
                || !world.getBlockState(entry.getKey()).equals(entry.getValue()))
            throw new IllegalStateException("world_process_receiver_structure_changed");
    }

    List<Stand> feedingStands(LocalPlayer player) {
        var candidates = new ArrayList<Stand>();
        for (BlockPos receiver : fluids) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            double horizontal = x * x + z * z;
            if (horizontal < 4 || horizontal > 16) continue;
            for (int y = 0; y <= 2; y++) {
                BlockPos feet = receiver.offset(x, y, z);
                if (standable(player, feet) && safeWaiting(player, feet)) candidates.add(new Stand(feet, receiver));
            }
        }
        // 优先复用附近地面；真正能否将物品投进此格由原生投掷轨迹检查决定，不靠距离就宣称能投到。
        candidates.sort(Comparator.comparingDouble(value -> player.distanceToSqr(Vec3.atBottomCenterOf(value.feet()))
                + value.feet().distSqr(value.receiver()) * .25));
        return List.copyOf(candidates);
    }

    boolean safeWaiting(LocalPlayer player, BlockPos feet) {
        AABB pickup = pickupBox(player, Vec3.atBottomCenterOf(feet));
        for (BlockPos fluid : fluids) if (pickup.intersects(new AABB(fluid).inflate(.125, .25, .125))) return false;
        return true;
    }

    boolean safeWaiting(LocalPlayer player) {
        AABB pickup = player.getBoundingBox().inflate(1, .5, 1);
        for (BlockPos fluid : fluids) if (pickup.intersects(new AABB(fluid).inflate(.125, .25, .125))) return false;
        return true;
    }

    BlockPos collectingStand(LocalPlayer player, Vec3 output) {
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos fluid : fluids) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            for (int y = 0; y <= 2; y++) {
                BlockPos feet = fluid.offset(x, y, z);
                // 成品已确认后才允许接近拾取范围；仍只在普通地面站立，不跳进未知深度或危险流体。
                if (standable(player, feet, true) && pickupBox(player, Vec3.atBottomCenterOf(feet)).intersects(
                        new AABB(output.x - .125, output.y, output.z - .125, output.x + .125, output.y + .25, output.z + .125)))
                    candidates.add(feet);
            }
        return candidates.stream().min(Comparator.comparingDouble(at -> player.distanceToSqr(Vec3.atBottomCenterOf(at))))
                .orElseThrow(() -> new IllegalStateException("world_process_output_has_no_safe_collection_stand"));
    }

    private boolean standable(LocalPlayer player, BlockPos feet) {
        return standable(player, feet, false);
    }
    private boolean standable(LocalPlayer player, BlockPos feet, boolean collecting) {
        if (!world.isLoaded(feet.below()) || !world.isLoaded(feet.above())
                || !world.getFluidState(feet.above()).isEmpty()) return false;
        // 确认成品后可以在有坚实底部、头顶露出水面的浅水里捡取；投料等待仍只站干地，其他流体不按水处理。
        var fluid = world.getFluidState(feet);
        if (!fluid.isEmpty() && !(collecting && fluids.contains(feet) && fluid.is(FluidTags.WATER))) return false;
        return world.getBlockState(feet.below()).isCollisionShapeFullBlock(world, feet.below())
                && world.noCollision(player, bodyBox(player, Vec3.atBottomCenterOf(feet)));
    }
    // 使用当前角色的真实碰撞体，蹲姿或其他体型变化不能沿用写死的普通玩家尺寸。
    private static AABB bodyBox(LocalPlayer player, Vec3 feet) { return player.getBoundingBox().move(feet.subtract(player.position())); }
    private static AABB pickupBox(LocalPlayer player, Vec3 feet) { return bodyBox(player, feet).inflate(1, .5, 1); }
}
