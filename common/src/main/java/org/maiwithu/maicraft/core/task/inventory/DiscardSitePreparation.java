// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.task.TaskState;

/** 准备有回收兜底的点火位置，或沿已有路线找空地、向侧壁逐格挖袋，准备完成前保留待丢物品。 */
final class DiscardSitePreparation {
    private DiscardSitePlan plan;
    private PlayerNav navigation;
    private DiscardBlockAction action;
    private final HashSet<BlockPos> rejected = new HashSet<>();
    private final List<BlockPos> cleared = new ArrayList<>();
    private int index;
    private String failure;
    private boolean ready;
    private long centeringSince = -1;
    private final boolean allowBurn;
    DiscardSitePreparation() { this(true); }
    DiscardSitePreparation(boolean allowBurn) { this.allowBurn = allowBurn; }

    TaskState tick(LocalPlayerContext context) {
        if (ready) return TaskState.SUCCESS;
        var player = context.player();
        if (plan == null) {
            if (rejected.size() >= 3) { failure = "three native approaches could not reach a discard site; items remain in inventory"; return TaskState.FAILED; }
            plan = DiscardSitePlan.find(player, rejected, allowBurn);
            if (plan == null) { failure = "no reachable discard area or small side pocket preserves the passage; items remain in inventory"; return TaskState.FAILED; }
        }
        // 在一格宽的侧袋口先站到格心，避免贴墙出手时随机横向速度使物品撞在入口、落回通道。
        Vec3 delta = Vec3.atBottomCenterOf(plan.stance()).subtract(player.position()).multiply(1, 0, 1);
        if (player.blockPosition().equals(plan.stance()) && delta.lengthSqr() > .01) {
            if (centeringSince < 0) centeringSince = context.level().getGameTime();
            if (context.level().getGameTime() - centeringSince > 100) { failure = "could not settle at the discard stance before throwing"; return TaskState.FAILED; }
            if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
            context.body().applySteering(yaw -> {
                double angle = Math.toRadians(yaw), sin = Math.sin(angle), cos = Math.cos(angle);
                return new BodyControlPort.Movement((float) Mth.clamp((delta.z * cos - delta.x * sin) * 2, -1, 1),
                        (float) Mth.clamp((delta.x * cos + delta.z * sin) * 2, -1, 1), false, false, false);
            }, player.getYRot(), context.tickRevision());
            return TaskState.RUNNING;
        }
        context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        if (navigation != null || !player.blockPosition().equals(plan.stance())) {
            if (navigation == null) navigation = PlayerNav.toGoal(player, () -> NavGoal.exact(plan.stance()), .8,
                    () -> player.blockPosition().equals(plan.stance())).walkingOnly();
            var status = navigation.tick();
            if (status == PlayerNav.Status.RUNNING) return TaskState.RUNNING;
            navigation.stop(); navigation = null;
            centeringSince = -1;
            if (status == PlayerNav.Status.FAILED) { rejected.add(plan.stance()); plan = null; index = 0; }
            return TaskState.RUNNING;
        }
        if (action != null) {
            TaskState status = action.tick(context);
            if (status == TaskState.RUNNING) return status;
            action.close(context);
            if (status != TaskState.SUCCESS) { failure = action.failure(); return TaskState.FAILED; }
            cleared.add(action.target); action = null; index++;
            return TaskState.RUNNING;
        }
        while (index < plan.excavation().size() && context.level().getBlockState(plan.excavation().get(index)).isAir()) index++;
        if (index < plan.excavation().size()) {
            action = new DiscardBlockAction(plan.excavation().get(index), DiscardBlockAction.Kind.EXCAVATE);
            return TaskState.RUNNING;
        }
        // 开挖确实形成空间后才进入分堆；流水、落沙等原生变化保留在现场，不能把计划中的空气当成已挖好。
        if (!plan.clear(context.level())) { failure = "discard side area changed before throwing; no items submitted"; return TaskState.FAILED; }
        ready = true; return TaskState.SUCCESS;
    }
    DiscardSitePlan plan() { return plan; }
    String failure() { return failure; }
    Map<String, Object> result() {
        return Map.of("ready", ready, "requires_burn_to_clear_passage", plan != null && plan.requiresBurn(),
                "site", plan == null ? List.of() : List.of(plan.stance().getX(), plan.stance().getY(), plan.stance().getZ()),
                "excavated", cleared.stream().map(pos -> List.of(pos.getX(), pos.getY(), pos.getZ())).toList());
    }
    void close(LocalPlayerContext context) {
        if (navigation != null) navigation.stop();
        if (action != null) action.close(context);
    }
}
