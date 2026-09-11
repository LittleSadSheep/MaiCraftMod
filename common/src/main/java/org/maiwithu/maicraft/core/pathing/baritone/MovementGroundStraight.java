package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 执行合并后的地面直线：朝终点前进，远时冲刺，接近后停下。真正轮到这一步时，仍反复检查眼前一段路和惯性可能带到的位置。
 */
public final class MovementGroundStraight extends Movement {
    private final Vec3 from, target;
    private final double originalCost;

    MovementGroundStraight(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest,
                           Vec3 from, Vec3 target, double cost) {
        super(baritone, src, dest, new BetterBlockPos[0]);
        this.from = from; this.target = target; this.originalCost = cost;
        override(cost);
    }

    @Override public boolean calculatedWhileLoaded() { return true; }
    @Override public double getCost(CalculationContext context) { return calculateCost(context); }
    @Override public double recalculateCost(CalculationContext context) { return calculateCost(context); }

    @Override public double calculateCost(CalculationContext context) {
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null || executor.getPosition() >= executor.getPath().movements().size()
                || executor.getPath().movements().get(executor.getPosition()) != this) return originalCost;
        Vec3 start = ctx.player().position(), delta = target.subtract(start);
        if (ownsHop()) {
            start = new Vec3(start.x, from.y, start.z);
            delta = target.subtract(start);
        }
        double distance = delta.length();
        // Installation checked the full line. Execution refreshes a braking-distance window,
        // including the current momentum, instead of rescanning a distant route every tick.
        double reach = Math.max(4, ctx.player().getDeltaMovement().horizontalDistance() * 6 + 1);
        Vec3 end = distance <= reach ? target : start.add(delta.scale(reach / distance));
        GroundCorridor geometry = corridor();
        Vec3 drift = ctx.player().getDeltaMovement().multiply(4, 0, 4);
        return geometry.clear(start, end) && geometry.clear(start, start.add(drift)) ? originalCost : COST_INF;
    }

    private GroundCorridor corridor() {
        return new GroundCorridor(ctx.world(), pos -> ctx.world().isLoaded(pos)
                && ctx.world().getWorldBorder().isWithinBounds(pos), ctx.player().getBbWidth(), ctx.player().getBbHeight(),
                EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells(), EmbeddedBaritoneRuntime.physicalObstacles());
    }

    // 允许角色在直线附近少量偏移，避免不是恰好踩在格心就算离开路线；这份容许位置集合不替代实时碰撞检查。
    @Override protected Set<BetterBlockPos> calculateValidPositions() {
        // Include traversed cells, not merely endpoints: executor recovery and distance checks
        // still use the movement's physical corridor when the line crosses no graph nodes.
        var cells = new HashSet<BetterBlockPos>();
        for (int x = Math.min(src.x, dest.x); x <= Math.max(src.x, dest.x); x++) {
            for (int z = Math.min(src.z, dest.z); z <= Math.max(src.z, dest.z); z++) {
                Vec3 p = new Vec3(x + 0.5, from.y, z + 0.5);
                Vec3 delta = target.subtract(from);
                double t = Mth.clamp(p.subtract(from).dot(delta) / delta.lengthSqr(), 0, 1);
                if (p.distanceToSqr(from.lerp(target, t)) <= 1) cells.add(new BetterBlockPos(x, src.y, z));
            }
        }
        cells.add(src); cells.add(dest);
        return Set.copyOf(cells);
    }

    @Override public MovementState updateState(MovementState state) {
        super.updateState(state);
        boolean hopping = ownsHop();
        if ((!ctx.player().onGround() && !hopping) || ctx.player().isInWater() || ctx.player().isPassenger()) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        Vec3 delta = target.subtract(ctx.player().position());
        if (ctx.player().onGround() && delta.horizontalDistanceSqr() < 0.0625 && Math.abs(delta.y) < 0.05) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90;
        return state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, 8), false))
                .setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, hopping || delta.horizontalDistanceSqr() > 4);
    }

    @Override protected boolean prepared(MovementState state) { return true; }
    @Override protected boolean safeToCancel(MovementState state) { return !ownsHop(); }

    private boolean ownsHop() {
        var executor = baritone.getPathingBehavior().getCurrent();
        return executor instanceof baritone.pathing.path.PathExecutor path && path.controlsGroundJump(this);
    }

    public Vec3 target() { return target; }
    Vec3 origin() { return from; }
}
