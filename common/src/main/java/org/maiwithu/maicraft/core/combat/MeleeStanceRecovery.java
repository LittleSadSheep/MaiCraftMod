package org.maiwithu.maicraft.core.combat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.build.BuildEdgeMotion;

/** 导航格已到而身体仍够不着时，沿经过原生碰撞与支撑复核的短段补齐站位；失败格交回寻路绕开。 */
public final class MeleeStanceRecovery {
    private final Set<BlockPos> rejected = new HashSet<>();
    private BuildEdgeMotion alignment;
    private BlockPos cell, targetCell;
    private Entity target;
    private Map<String, Object> lastEvidence = Map.of();

    public void target(LocalPlayer player, Entity current) {
        BlockPos at = current == null ? null : current.blockPosition();
        // 换对手或敌人走到另一格后重新判断可用站位，不把上一处动态阻挡永久套到新战场。
        if (target != current || at != null && !at.equals(targetCell)) {
            stop(player); rejected.clear(); target = current; targetCell = at == null ? null : at.immutable();
        }
    }

    public void begin(LocalPlayer player) {
        cell = PlayerNav.playerFeet(player).immutable();
        alignment = BuildEdgeMotion.alignAt(Vec3.atBottomCenterOf(cell),
                NavigationSafetyContext.forbiddenBodyCells(), at -> !NavigationSafetyContext.forbidsBody(at));
    }

    public boolean active() { return alignment != null; }

    public boolean tick(LocalPlayer player, NavGoal currentGoal, boolean withinReach) {
        if (alignment == null) return false;
        // 敌人走近已可出刀，或目标变化让原格不再合适时，立即交回正常战斗走位。
        if (withinReach || currentGoal == null || !currentGoal.isAt(cell)) { stop(player); return false; }
        var state = alignment.tick(player);
        lastEvidence = alignment.evidence();
        if (state == BuildEdgeMotion.Status.RUNNING) return true;
        // 已完成微调仍未进入射程、或支撑/净空改变，都不能反复导航到同一失败格冒充到位。
        rejected.add(cell); stop(player); return false;
    }

    public void stop(LocalPlayer player) {
        if (alignment != null) { alignment.release(player); alignment = null; }
        cell = null;
    }

    public NavGoal filter(NavGoal base) {
        return rejected.isEmpty() ? base : new RemainingStances(base, Set.copyOf(rejected));
    }

    public Map<String, Object> evidence() { return lastEvidence; }

    private record RemainingStances(NavGoal base, Set<BlockPos> excluded) implements NavGoal {
        // 只排除已经实际尝试失败的终点；不把该格设为禁行，角色仍能从原位安全走向其他候选。
        @Override public boolean isAt(BlockPos feet) { return !excluded.contains(feet) && base.isAt(feet); }
        @Override public double heuristic(BlockPos from) { return base.heuristic(from); }
        @Override public BlockPos center() { return base.center(); }
        @Override public SemanticFingerprint semanticFingerprint() {
            return new SemanticFingerprint("melee_remaining_stances", List.of(base.semanticFingerprint(), excluded));
        }
    }
}
