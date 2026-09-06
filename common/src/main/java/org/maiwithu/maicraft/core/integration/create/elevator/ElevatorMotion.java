package org.maiwithu.maicraft.core.integration.create.elevator;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorBridge.Cabin;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.entity.InputDriver;

/** Ordinary navigation outside, bounded support-aware walking inside the moving cabin. */
final class ElevatorMotion {
    enum Progress { MOVING, REACHED, BLOCKED }
    private EmbeddedBaritoneNavigator walking;
    private Vec3 staticGoal;
    private Vec3 localGoal;
    private final ArrayDeque<Vec3> route = new ArrayDeque<>();
    String failure = "";

    Progress approach(LocalPlayerContext ctx, Vec3 target, LongSet forbidden) {
        var player = ctx.player();
        if (ElevatorGeometry.forbidden(target, player.getBbWidth(), player.getBbHeight(), forbidden)) return Progress.BLOCKED;
        if (walking == null || !target.equals(staticGoal)) {
            if (!releaseNavigation()) return Progress.MOVING;
            staticGoal = target;
            BlockPos cell = BlockPos.containing(target.x, target.y + 0.1251, target.z);
            var state = ctx.level().getBlockState(cell);
            if (state.getBlock() instanceof SlabBlock || state.getBlock() instanceof StairBlock) cell = cell.above();
            BlockPos goal = cell;
            walking = NavigationSafetyContext.withProtectedArea(LongSets.emptySet(), forbidden,
                    () -> new EmbeddedBaritoneNavigator(player, () -> GoalCompiler.standOn(goal),
                            () -> player.onGround() && player.position().distanceToSqr(target) < 0.2,
                            PlayerNav.ContextProvider.DEFAULT, false));
        }
        PlayerNav.Status status = NavigationSafetyContext.withProtectedArea(LongSets.emptySet(), forbidden, walking::tick);
        if (status == PlayerNav.Status.FAILED) { failure = walking.failReason(); releaseNavigation(); return Progress.BLOCKED; }
        return status == PlayerNav.Status.ARRIVED ? Progress.REACHED : Progress.MOVING;
    }

    Progress inside(LocalPlayerContext ctx, Cabin cabin, ElevatorGeometry geometry, Vec3 destination, LongSet forbidden) {
        if (!releaseNavigation()) return Progress.MOVING;
        Vec3 position = ctx.player().position();
        Vec3 local = cabin.local(position);
        if (!destination.equals(localGoal)) {
            localGoal = destination; route.clear();
            route.addAll(geometry.path(local, destination, cabin.origin(), ctx.player().maxUpStep(), forbidden));
            if (route.isEmpty()) { failure = "no continuous supported cabin walkway"; return Progress.BLOCKED; }
        }
        while (!route.isEmpty() && close(local, route.getFirst())) route.removeFirst();
        if (route.isEmpty()) { stop(ctx); return close(local, destination) && geometry.carries(local) ? Progress.REACHED : Progress.BLOCKED; }
        return step(ctx, cabin, geometry, cabin.global(route.getFirst()), forbidden);
    }

    Progress step(LocalPlayerContext ctx, Cabin cabin, ElevatorGeometry geometry, Vec3 target, LongSet forbidden) {
        if (!releaseNavigation()) return Progress.MOVING;
        Vec3 position = ctx.player().position();
        if (close(position, target)) { stop(ctx); return Progress.REACHED; }
        if (!geometry.canStep(ctx.level(), ctx.level()::hasChunkAt, cabin.origin(), position, target,
                ctx.player().maxUpStep(), forbidden)) {
            stop(ctx); failure = "doorway is closed, unsupported, obstructed, or forbidden";
            return Progress.BLOCKED;
        }
        Vec3 delta = target.subtract(position);
        double flat = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (flat < 0.02) { stop(ctx); return Progress.MOVING; }
        InputDriver.look(ctx.player(), (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90, 10);
        double yaw = Math.toRadians(ctx.player().getYRot());
        float forward = (float) ((-delta.x * Math.sin(yaw) + delta.z * Math.cos(yaw)) / flat * 0.5);
        float strafe = (float) ((delta.x * Math.cos(yaw) + delta.z * Math.sin(yaw)) / flat * 0.5);
        ctx.body().applyMovement(new BodyControlPort.Movement(forward, strafe, false, false, false), ctx.tickRevision());
        return Progress.MOVING;
    }

    boolean releaseNavigation() {
        if (walking == null) return true;
        if (!walking.yieldForExternalAction()) return false;
        walking.stop(); walking = null; staticGoal = null;
        return true;
    }

    void resetLocalPath() { localGoal = null; route.clear(); }
    boolean planning() { return walking != null && walking.planningInFlight(); }
    boolean active() { return walking != null && (walking.planningInFlight() || walking.hasRecentPhysicalProgress(60)); }
    void abandon() { if (walking != null) walking.abandon(); walking = null; resetLocalPath(); }
    static void stop(LocalPlayerContext ctx) { ctx.body().applyMovement(BodyControlPort.Movement.STOPPED, ctx.tickRevision()); }
    static boolean close(Vec3 from, Vec3 to) {
        return Math.abs(from.y - to.y) < 0.13 && (from.x - to.x) * (from.x - to.x) + (from.z - to.z) * (from.z - to.z) < 0.025;
    }
}
