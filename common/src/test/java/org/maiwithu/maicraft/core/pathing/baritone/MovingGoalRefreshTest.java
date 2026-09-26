package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import baritone.process.CustomGoalProcess;
import baritone.utils.PathingControlManager;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import sun.misc.Unsafe;

/** 连续移动的敌人只触发原生路线重检；有效的部分路径能继续走，旧终点真正失效时仍重新规划。 */
public final class MovingGoalRefreshTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var w = new InteractionWorldTestHarness()) {
            // 比较原生目标的剩余距离会读取Baritone设置，夹具提供独立目录，避免误用未初始化的游戏客户端字段。
            field(Minecraft.class, "gameDirectory").set(Minecraft.getInstance(), new File("moving-goal-settings-fixture"));
            var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            var backend = (Baritone) memory.allocateInstance(Baritone.class);
            var ctx = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "playerFeet" -> new BetterBlockPos(0, 1, 3);
                        case "player" -> w.player;
                        default -> throw new AssertionError(method.getName());
                    });
            var elytra = Proxy.newProxyInstance(IElytraProcess.class.getClassLoader(), new Class<?>[]{IElytraProcess.class},
                    (proxy, method, values) -> { if (method.getName().equals("isActive")) return false; throw new AssertionError(method.getName()); });
            field(Baritone.class, "playerContext").set(backend, ctx); field(Baritone.class, "elytraProcess").set(backend, elytra);
            var process = new CustomGoalProcess(backend); process.onLostControl();
            var original = new GoalBlock(10, 1, 3); process.setGoalAndPath(original);
            check(process.onTick(false, true).commandType == PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, "ordinary new requests keep strict goal replacement");
            for (int tick = 0; tick < 80; tick++) {
                var moved = new GoalBlock(10 + tick, 1, 3); process.updateGoalAndPath(moved);
                var command = process.onTick(false, tick % 2 == 0);
                check(command.goal == moved && command.commandType == PathingCommandType.REVALIDATE_GOAL_AND_PATH,
                        "moving targets never request unconditional path cancellation");
            }
            check(process.onTick(true, true).commandType == PathingCommandType.CANCEL_AND_SET_GOAL && !process.isActive(),
                    "continuous tracking still handles a real path calculation failure");
            process.setGoalAndPath(original);
            check(process.onTick(false, true).commandType == PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH,
                    "tracking mode cannot leak into a later ordinary goal");
            // 原终点向前移动时保留仍有效的完整或部分通道；目标换到身后，原方向失效才重算。
            var pathing = (PathingBehavior) memory.allocateInstance(PathingBehavior.class);
            var executor = (PathExecutor) memory.allocateInstance(PathExecutor.class);
            field(PathingBehavior.class, "current").set(pathing, executor); field(Baritone.class, "pathingBehavior").set(backend, pathing);
            var manager = (PathingControlManager) memory.allocateInstance(PathingControlManager.class); field(PathingControlManager.class, "baritone").set(manager, backend);
            for (boolean full : new boolean[]{false, true}) {
                var path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                        (proxy, method, values) -> switch (method.getName()) {
                            case "getGoal" -> original;
                            case "getDest" -> new BetterBlockPos(full ? 10 : 5, 1, 3);
                            default -> throw new AssertionError(method.getName());
                        });
                field(PathExecutor.class, "path").set(executor, path);
                check(!manager.revalidateGoal(new GoalBlock(12, 1, 3)), "native revalidation keeps forward progress even when an old full path needs extending");
                check(manager.revalidateGoal(new GoalBlock(-12, 1, 3)), "a path heading away from the changed goal must be replanned");
            }
            var following = PlayerNav.trackGoal(w.player, () -> NavGoal.exact(new BlockPos(10, 1, 3)), 1, () -> false);
            var transport = field(PlayerNav.class, "navigator").get(following);
            check(((EmbeddedBaritoneNavigator) field(transport.getClass(), "ground").get(transport)).tracksMovingGoal(), "tracking intent reaches the ground navigator");
            var reset = transport.getClass().getDeclaredMethod("newGround"); reset.setAccessible(true);
            check(((EmbeddedBaritoneNavigator) reset.invoke(transport)).tracksMovingGoal(), "transport handoff preserves tracking semantics");
        }
        System.out.println("MovingGoalRefreshTest: passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var f = owner.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
