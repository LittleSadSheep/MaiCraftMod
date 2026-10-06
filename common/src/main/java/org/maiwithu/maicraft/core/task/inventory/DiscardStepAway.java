// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.DiscardedItems;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;

/**
 * 原地丢弃后离开拾取范围：原地形态是因为前方有障碍才选的，物品撞墙后落在身边，
 * 原版玩家丢出物品只有 40 刻拾取冷却，人站着不动就会被自动捡回。
 * 等丢出的物品被客户端看到 -> 若脚下仍在物品拾取范围内，就走到最近一块范围外的可站立格 -> 收场。
 * 走不开时不改判丢弃失败（物品确实丢出了），只在回执里如实写明可能被捡回。
 */
final class DiscardStepAway {
    /** 找范围外落脚格的水平半径；原地丢弃的落点都在站位邻域，四格足够走出拾取盒。 */
    private static final int SEARCH_RADIUS = 4;
    /** 等丢出物品出现在客户端的上限（刻）；超过就按当前观察判断，不无限等。 */
    private static final int OBSERVE_WAIT_TICKS = 10;
    /** 走开的总预算（刻），要赶在 40 刻拾取冷却结束之前。 */
    private static final int STEP_BUDGET_TICKS = 30;
    private static final double WALK_SPEED = 1.0;

    private final List<DiscardedItems.Watch> watches;
    private PlayerNav nav;
    private BlockPos destination;
    private int waited, walked;
    private String outcome = "pending";

    DiscardStepAway(List<DiscardedItems.Watch> watches) {
        this.watches = List.copyOf(watches);
    }

    /** 每刻推进一次；返回 true 表示已收场（走开、本来就在范围外，或尽力后仍未走开）。 */
    boolean tick(LocalPlayer player) {
        if (!"pending".equals(outcome)) return true;
        DiscardedItems.observe(player);
        // 先等丢出的物品实体被客户端看到，拾取范围才算数；短暂等不到就按当前观察判断。
        if (watches.stream().anyMatch(DiscardedItems.Watch::pending) && waited++ < OBSERVE_WAIT_TICKS) return false;
        LongSet forbidden = DiscardedItems.forbiddenBodyCells();
        if (!forbidden.contains(player.blockPosition().asLong())) {
            return finish(walked == 0 ? "not_needed" : "left_pickup_range");
        }
        if (++walked > STEP_BUDGET_TICKS) return finish("pickup_range_not_left");
        if (nav == null) {
            destination = nearestOutside(player, forbidden);
            if (destination == null) return finish("no_standable_cell_outside_pickup_range");
            BlockPos goal = destination;
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(goal), WALK_SPEED,
                    () -> !DiscardedItems.forbiddenBodyCells().contains(player.blockPosition().asLong()),
                    PlayerNav.ContextProvider.DEFAULT).walkingOnly();
        }
        return switch (nav.tick()) {
            case RUNNING -> false;
            // 到了目标格却仍在范围内（物品漂移扩大了拾取盒）：下一刻按新范围重新选格。
            case ARRIVED -> { stopNav(); yield false; }
            case FAILED -> finish("pickup_range_not_left");
        };
    }

    /** 站位附近最近的、不在任何丢弃物拾取范围内的可站立格；没有返回 null。 */
    private static BlockPos nearestOutside(LocalPlayer player, LongSet forbidden) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos at : BlockPos.betweenClosed(origin.offset(-SEARCH_RADIUS, -1, -SEARCH_RADIUS),
                origin.offset(SEARCH_RADIUS, 1, SEARCH_RADIUS))) {
            if (forbidden.contains(at.asLong()) || !player.level().isLoaded(at)
                    || !BlockHelper.isStandable(player.level(), at)) continue;
            double distance = at.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = at.immutable();
            }
        }
        return best;
    }

    private boolean finish(String result) {
        outcome = result;
        stopNav();
        return true;
    }

    private void stopNav() {
        if (nav != null) nav.stop();
        nav = null;
    }

    /** 任务取消或收尾时停下走位导航，不留按住的移动键。 */
    void stop() {
        stopNav();
        if ("pending".equals(outcome)) outcome = "interrupted";
    }

    /** 走开没成功时，丢出的物品可能已被自动捡回，调用方应复核背包。 */
    boolean pickupRisk() {
        return !"not_needed".equals(outcome) && !"left_pickup_range".equals(outcome);
    }

    Map<String, Object> result() {
        return destination == null ? Map.of("outcome", outcome, "pickup_risk", pickupRisk())
                : Map.of("outcome", outcome, "pickup_risk", pickupRisk(), "destination", destination.toShortString());
    }
}
