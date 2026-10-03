// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineBreak;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineSession;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.settings.ClearanceWhitelist;

/** 按当前通行缺口选方块与命中面；原生线段挖断后重新补齐，实际走通后才推进下一段。 */
final class ProspectTunnelDriver implements AutoCloseable {
    record Effect(BlockPos origin, UltimineBreak.Result result) {}
    private final LocalPlayer player;
    private final int targetY;
    private final Direction heading;
    private final BlockDigger probe;
    private final List<Effect> effects = new ArrayList<>();
    private ProspectTunnelPlan plan;
    private UltimineBreak action;
    private PlayerNav nav;
    private BlockPos destination;
    private String failure;
    private boolean uncertain;
    private Map<String, Object> interrupted = Map.of();
    private int removed;

    ProspectTunnelDriver(LocalPlayer player, int targetY) {
        this.player = player; this.targetY = targetY; heading = player.getDirection(); probe = new BlockDigger(player);
    }
    boolean tick(int blockBudget) {
        if (failure != null) return false;
        if (action != null) {
            var result = action.tick();
            if (result.status() == UltimineBreak.Status.RUNNING) return true;
            effects.add(new Effect(action.origin(), result)); removed += result.removed().size();
            action.close(); action = null;
            if (result.status() != UltimineBreak.Status.COMPLETE || result.uncertain()) {
                failure = "native_tunnel_break: " + result.evidence().get("reason"); uncertain |= result.uncertain(); return false;
            }
            return true;
        }
        if (nav != null) {
            return switch (nav.tick()) {
                case RUNNING -> true;
                case ARRIVED -> { nav.stop(); nav = null; plan = null; yield true; }
                case FAILED -> { failure = "tunnel_walk: " + nav.failReason(); nav.stop(); nav = null; yield false; }
            };
        }
        BlockPos feet = PlayerNav.playerFeet(player);
        if (!player.onGround()) return true;
        if (feet.getY() < targetY) {
            // 采矿绕到较低洞室后先复用普通导航回到目标层，不能把水平通道继续挖在错误高度。
            destination = new BlockPos(feet.getX(), targetY, feet.getZ());
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(destination), 1, () -> false, PlayerNav.ContextProvider.TERRAFORM);
            return true;
        }
        if (plan == null) plan = new ProspectTunnelPlan(feet, heading, targetY, player.getBoundingBox().getYsize(), Math.max(1, blockBudget));
        BlockPos walk = plan.walkableEnd(this::bodyClear, this::safeFloor);
        if (walk != null) {
            // 只有完整断面和实底连续成立才移动；走通道用保留地形导航，不能顺路把未补齐的地方当作已完成。
            destination = walk;
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(destination), 1, () -> false, PlayerNav.ContextProvider.DEFAULT).walkingOnly();
            return true;
        }
        int reach = (int) Math.ceil(AimGeometry.blockReachDistance(player));
        for (BlockPos at : plan.obstacles(this::read, reach)) {
            if (!allowed(at)) continue;
            BlockHitResult hit = hit(at, heading.getOpposite());
            if (hit == null && plan.descending()) hit = hit(at, Direction.UP);
            if (hit == null) continue;
            var mode = plan.descending() ? UltimineSession.Mode.DESCENDING_TUNNEL : UltimineSession.Mode.SMALL_TUNNEL;
            action = new UltimineBreak(player, at, hit.getDirection(), mode, this::allowed,
                    cell -> cell.equals(PlayerNav.playerFeet(player).below())).maximumBlocks(blockBudget);
            return true;
        }
        failure = "no_reachable_obstacle_or_supported_passage_in_tunnel_direction";
        return false;
    }
    private BlockHitResult hit(BlockPos at, Direction face) { probe.requiredFace(face); return probe.reachableHit(at); }
    private BlockState read(BlockPos at) { return player.level().isLoaded(at) ? player.level().getBlockState(at) : null; }
    private boolean allowed(BlockPos at) {
        BlockState state = read(at);
        return plan != null && plan.contains(at) && state != null && ClearanceWhitelist.allows(state)
                && !NavigationSafetyContext.protectsMutation(at) && !state.hasBlockEntity()
                && state.getFluidState().isEmpty() && state.getDestroySpeed(player.level(), at) >= 0;
    }
    private boolean bodyClear(BlockPos at) {
        BlockState state = read(at);
        return state != null && !NavigationSafetyContext.forbidsBody(at) && state.getFluidState().isEmpty()
                && !MovementHelper.avoidWalkingInto(state) && state.getCollisionShape(player.level(), at).isEmpty();
    }
    private boolean safeFloor(BlockPos at) {
        BlockState state = read(at);
        return state != null && state.getFluidState().isEmpty() && !MovementHelper.avoidWalkingInto(state)
                && MovementHelper.canWalkOn(player.level(), at);
    }
    boolean breaking() { return action != null; }
    boolean yieldForMining() {
        // 先交接走路中尚未结清的原生动作，再允许矿工接管镜头和镐；回到通道时按新的脚位重建前沿。
        if (action != null || nav != null && !nav.yieldForExternalAction()) return false;
        if (nav != null) { nav.stop(); nav = null; }
        plan = null; return true;
    }
    List<Effect> drainEffects() { var result = List.copyOf(effects); effects.clear(); return result; }
    String failure() { return failure; }
    boolean uncertain() { return uncertain; }
    Map<String, Object> evidence() {
        return Map.of("heading", heading.getName(), "target_y", targetY, "confirmed_excavation_blocks", removed,
                "phase", action != null ? "clearing_passage" : nav != null ? "walking_open_passage" : "checking_passage",
                "failure", failure == null ? "none" : failure, "interrupted_action", interrupted);
    }
    @Override public void close() {
        if (action != null) {
            var result = action.interruptedResult(); interrupted = result.evidence(); uncertain |= result.uncertain();
            effects.add(new Effect(action.origin(), result)); removed += result.removed().size();
            action.close(); action = null;
            if (uncertain) failure = "native_tunnel_interrupted_with_unresolved_effects";
        }
        if (nav != null) { nav.stop(); nav = null; }
        plan = null;
    }
}
