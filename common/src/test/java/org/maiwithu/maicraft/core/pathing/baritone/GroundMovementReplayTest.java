package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.path.PathExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

/**
 * 把给定的位置样本交给真实移动更新：已批准的斜向跑跳可继续路线，普通长直行不应在格子边界停步或改变方向。位置由测试设置，不模拟完整移动物理。
 */
public final class GroundMovementReplayTest {
    public static void main(String[] args) throws Exception {
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        IPlayerContext context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "player" -> player;
                    case "playerFeet" -> new BetterBlockPos(BlockPos.containing(player.position()));
                    default -> throw new AssertionError("unexpected fixture request " + method);
                });
        PathExecutor executor = (PathExecutor) memory.allocateInstance(PathExecutor.class);
        var jump = new GroundJumpContinuation();
        field(PathExecutor.class, "groundJump").set(executor, jump);
        field(PathExecutor.class, "waterTravel").set(executor, new SubmergedWaterTravelPolicy(context, null, new SwimTravelControl()));
        IPathingBehavior behavior = (IPathingBehavior) Proxy.newProxyInstance(IPathingBehavior.class.getClassLoader(), new Class<?>[]{IPathingBehavior.class},
                (proxy, method, values) -> { if (method.getName().equals("getCurrent")) return executor; throw new AssertionError(method); });
        IBaritone baritone = (IBaritone) Proxy.newProxyInstance(IBaritone.class.getClassLoader(), new Class<?>[]{IBaritone.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "getPlayerContext" -> context; case "getPathingBehavior" -> behavior; default -> throw new AssertionError(method);
                });
        var src = new BetterBlockPos(15, -60, 14);
        var diagonal = new MovementDiagonal(baritone, src, Direction.WEST, Direction.NORTH, 0);
        var next = new MovementDiagonal(baritone, diagonal.getDest(), Direction.WEST, Direction.NORTH, 0);
        Vec3 apex = new Vec3(14.468085892699952, -58.75081292125532, 13.424615404688565);
        field(LocalPlayer.class, "position").set(player, apex);
        BetterBlockPos physical = context.playerFeet();
        check(jump.feet(diagonal, physical).equals(physical), "an arbitrary airborne body has no projected route permission");
        jump.launch(-60, -60, java.util.List.of(diagonal, next)); jump.observe(false, apex.y);
        check(diagonal.updateState(running()).getStatus() == MovementStatus.SUCCESS,
                "the native diagonal must advance at destination XZ during its verified hop instead of chasing it until landing");
        check(player.position().equals(apex), "route projection must never write the actual player's position");
        check(executor.groundJumpFeet(diagonal, physical).equals(diagonal.getDest()),
                "executor recovery and movement completion must use the same route layer");
        Vec3 descent = new Vec3(13.910359438632359, -59.2032643993313, 12.945148956239061);
        field(LocalPlayer.class, "position").set(player, descent); jump.observe(false, descent.y);
        check(next.updateState(running()).getStatus() == MovementStatus.SUCCESS,
                "the old 60564 cancellation sample now continues across the next diagonal");
        check(!jump.feet(diagonal, src.above()).equals(diagonal.getDest()), "backward displacement cannot complete a forward segment");
        var ascend = new MovementAscend(baritone, src, src.east().above());
        check(jump.feet(ascend, physical).equals(physical), "an ascend cannot inherit the flat-hop override");
        var other = new MovementDiagonal(baritone, diagonal.getDest(), Direction.WEST, Direction.NORTH, 0);
        check(jump.feet(other, physical).equals(physical), "a later route or unverified interaction movement cannot inherit the hop");
        jump.observe(true, -60);
        check(jump.feet(diagonal, physical).equals(physical), "actual landing ends the hop's route layer");
        jump.launch(-60, -60, java.util.List.of(diagonal)); jump.observe(false, -60.2);
        check(jump.feet(diagonal, physical).equals(physical), "falling below the runway hands back to ordinary recovery");

        Vec3 start = new Vec3(.5, 0, .5), end = new Vec3(32.5, 0, 17.5);
        var straight = new MovementGroundStraight(baritone, new BetterBlockPos(0, 0, 0), new BetterBlockPos(32, 0, 17), start, end, 160);
        field(LocalPlayer.class, "onGround").setBoolean(player, true);
        Float firstYaw = null;
        for (int i = 0; i < 100; i++) {
            field(LocalPlayer.class, "position").set(player, start.lerp(end, i / 100.0));
            var state = straight.updateState(running());
            check(state.getStatus() == MovementStatus.RUNNING && state.getInputStates().get(Input.MOVE_FORWARD),
                    "crossing a grid cell cannot stop a long straight movement");
            check(!Boolean.TRUE.equals(state.getInputStates().get(Input.JUMP)), "walking shortcut must not create an unowned jump");
            float yaw = state.getTarget().getRotation().orElseThrow().getYaw();
            if (firstYaw == null) firstYaw = yaw;
            check(Math.abs(yaw - firstYaw) < .001, "a 32:17 straight route must keep the same physical bearing across grid cells");
        }
        field(LocalPlayer.class, "position").set(player, end);
        check(straight.updateState(running()).getStatus() == MovementStatus.SUCCESS, "arrival ends the long movement");
        field(LocalPlayer.class, "onGround").setBoolean(player, false);
        field(LocalPlayer.class, "position").set(player, start.add(2, 1.2, 1));
        check(straight.updateState(running()).getStatus() == MovementStatus.UNREACHABLE,
                "an unplanned airborne body cannot claim a straight travel hop");
        jump.launch(0, 0, java.util.List.of(straight)); jump.observe(false, 1.2);
        var airborne = straight.updateState(running());
        check(airborne.getStatus() == MovementStatus.RUNNING && airborne.getInputStates().get(Input.MOVE_FORWARD)
                && airborne.getInputStates().get(Input.SPRINT) && !straight.safeToCancel(),
                "a verified straight hop keeps forward control and cannot hand off midair");
        check(!Boolean.TRUE.equals(airborne.getInputStates().get(Input.JUMP)),
                "release jump while airborne so the native repeat delay clears before the next landing");
        field(LocalPlayer.class, "position").set(player, end.add(0, .01, 0));
        check(straight.updateState(running()).getStatus() == MovementStatus.RUNNING,
                "being above the endpoint does not complete a hop before physical touchdown");
        jump.observe(true, 0);
        field(LocalPlayer.class, "onGround").setBoolean(player, true);
        field(LocalPlayer.class, "position").set(player, end);
        check(straight.safeToCancel() && straight.updateState(running()).getStatus() == MovementStatus.SUCCESS,
                "touchdown releases the temporary hop and completes the ordinary route");
        System.out.println("GroundMovementReplayTest: passed");
    }

    private static MovementState running() { return new MovementState().setStatus(MovementStatus.RUNNING); }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try { var result = at.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
