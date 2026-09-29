// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.sleep;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.build.BuildSupportWalking;

/** 普通休息只走已有的可往返地形；能从高柱跳下去并不足以证明睡醒能爬回去。 */
public final class NightRestRouteProbe {
    private record Node(Vec3 feet, Node previous) {}
    private final LocalPlayer player;
    private final Object level;
    private final BlockPos bed;
    private final Vec3 origin;
    private final BlockGetter terrain;
    private final BuildSupportWalking walking;
    private final PriorityQueue<Node> frontier;
    private final Set<BlockPos> visited = new HashSet<>();
    private boolean complete, connected;
    public NightRestRouteProbe(LocalPlayer player, BlockPos bed, PhysicalObstacleSnapshot physical) {
        this.player = player; level = player.level(); this.bed = bed.immutable(); origin = player.position();
        // 可手开的门按原生打开后的形状证明；实际开关仍由正常导航执行，受保护的门保持阻塞。
        terrain = new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) {
                if (!player.level().isLoaded(pos)) return Blocks.BARRIER.defaultBlockState();
                var state = player.level().getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()) {
                    BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
                    if (!NavigationSafetyContext.protectsUse(pos) && !NavigationSafetyContext.protectsUse(other)) return state.setValue(DoorBlock.OPEN, true);
                }
                if (state.getBlock() instanceof FenceGateBlock && !NavigationSafetyContext.protectsUse(pos)) return state.setValue(FenceGateBlock.OPEN, true);
                return state;
            }
            public BlockEntity getBlockEntity(BlockPos pos) { return player.level().isLoaded(pos) ? player.level().getBlockEntity(pos) : null; }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public int getHeight() { return player.level().getHeight(); }
            public int getMinBuildHeight() { return player.level().getMinBuildHeight(); }
        };
        double height = player.getDimensions(Pose.STANDING).height();
        walking = new BuildSupportWalking(terrain, player.level()::isLoaded, player.getBbWidth(), height,
                NavigationSafetyContext.forbiddenBodyCells(), physical);
        frontier = new PriorityQueue<>(Comparator.comparingDouble(node -> node.feet().distanceToSqr(Vec3.atCenterOf(bed))));
        var start = new GroundCorridor(terrain, player.level()::isLoaded, player.getBbWidth(), height,
                NavigationSafetyContext.forbiddenBodyCells(), physical);
        if (!Set.of("ready", "ready_empty", "not_installed").contains(physical.state()) || !start.clear(origin, origin)) complete = true;
        else { frontier.add(new Node(origin, null)); visited.add(BlockPos.containing(origin)); }
    }
    public boolean matches(LocalPlayer actor, BlockPos target) {
        return actor == player && actor.level() == level && bed.equals(target) && actor.position().distanceToSqr(origin) <= .16;
    }
    public boolean advance() {
        long deadline = System.nanoTime() + 2_000_000L;
        try {
            for (int work = 0; work < 24 && !complete && System.nanoTime() < deadline; work++) {
                Node node = frontier.poll();
                if (node == null || visited.size() >= 2048) { complete = true; break; }
                if (reachesBed(node.feet()) && recheck(node)) { connected = complete = true; break; }
                for (Direction side : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) {
                    BlockPos cell = BlockPos.containing(node.feet()).relative(side).offset(0, dy, 0);
                    if (visited.contains(cell) || Math.abs(cell.getX() - origin.x) > 36 || Math.abs(cell.getZ() - origin.z) > 36
                            || cell.getY() < Math.min(origin.y, bed.getY()) - 3 || cell.getY() > Math.max(origin.y, bed.getY()) + 3) continue;
                    Vec3 next = walking.stance(cell);
                    // 每条边同时验证反向；原生一格上下行可用，单向大落差、挖通道或补支撑均不作为休息路线。
                    if (next != null && walking.edge(node.feet(), next) && walking.edge(next, node.feet())
                            && visited.add(BlockPos.containing(next))) frontier.add(new Node(next, node));
                }
            }
        } catch (RuntimeException | LinkageError unavailable) { complete = true; connected = false; }
        return complete;
    }
    public boolean connected() { return connected; }
    private boolean recheck(Node node) {
        // 分帧搜索结束时重读整条路线，途中被施工改变的旧通道不能凭缓存授权离开工位。
        for (Node at = node; at.previous() != null; at = at.previous())
            if (!walking.edge(at.previous().feet(), at.feet()) || !walking.edge(at.feet(), at.previous().feet())) return false;
        return true;
    }
    private boolean reachesBed(Vec3 feet) {
        Vec3 eye = feet.add(0, player.getEyeHeight(Pose.STANDING), 0), point = Vec3.atCenterOf(bed);
        if (eye.distanceTo(point) > 4.25) return false;
        var hit = terrain.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().distManhattan(bed) <= 1
                && terrain.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock;
    }
}
