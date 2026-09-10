package org.maiwithu.maicraft.core.pathing.baritone.landing;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BlockBreakHelper;
import baritone.utils.InputOverrideHandler;
import baritone.utils.PathingControlManager;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;

/**
 * 检查跳跃错过完整方块或半砖后如何交给救援；清掉旧路线按键、保留交通控制边界，站稳后重新编译原目标。
 */
public final class MissedLandingHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var f = new WaterLandingReplayTest.Fixture(false);
        field(Minecraft.class, "gameThread").set(f.minecraft, Thread.currentThread());
        field(Minecraft.class, "gameDirectory").set(f.minecraft, new java.io.File("missed-landing-settings-fixture"));
        field(LocalPlayer.class, "clientLevel").set(f.player, f.world);
        var context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, values) -> {
                    if (method.getName().equals("player")) return f.player;
                    throw new AssertionError(method.getName());
                });
        var movement = (MovementParkour) f.memory.allocateInstance(MovementParkour.class);
        var destination = new BetterBlockPos(3, 12, 0);
        field(Movement.class, "dest").set(movement, destination);
        f.world.scene.blocks.put(destination.below(), Blocks.STONE.defaultBlockState());
        IPath path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("movements")) return List.of(movement);
                    throw new AssertionError(method.getName());
                });
        var executor = (PathExecutor) f.memory.allocateInstance(PathExecutor.class);
        field(PathExecutor.class, "groundJump").set(executor, new org.maiwithu.maicraft.core.pathing.baritone.GroundJumpContinuation());
        field(PathExecutor.class, "path").set(executor, path);
        var backend = (Baritone) f.memory.allocateInstance(Baritone.class);
        var behavior = (PathingBehavior) f.memory.allocateInstance(PathingBehavior.class);
        field(PathingBehavior.class, "current").set(behavior, executor);
        field(PathingBehavior.class, "baritone").set(behavior, backend);
        field(PathingBehavior.class, "toDispatch").set(behavior, new LinkedBlockingQueue<>());
        var controls = (PathingControlManager) f.memory.allocateInstance(PathingControlManager.class);
        field(PathingControlManager.class, "processes").set(controls, new HashSet<>());
        field(PathingControlManager.class, "active").set(controls, new ArrayList<>());
        var inputs = (InputOverrideHandler) f.memory.allocateInstance(InputOverrideHandler.class);
        field(InputOverrideHandler.class, "inputForceStateMap").set(inputs, new HashMap<>());
        field(InputOverrideHandler.class, "blockBreakHelper").set(inputs, f.memory.allocateInstance(BlockBreakHelper.class));
        var look = (LookBehavior) f.memory.allocateInstance(LookBehavior.class);
        field(LookBehavior.class, "smoothYawBuffer").set(look, new ArrayDeque<>());
        field(LookBehavior.class, "smoothPitchBuffer").set(look, new ArrayDeque<>());
        field(Baritone.class, "pathingControlManager").set(backend, controls);
        field(Baritone.class, "playerContext").set(backend, context);
        field(Baritone.class, "pathingBehavior").set(backend, behavior);
        field(Baritone.class, "inputOverrideHandler").set(backend, inputs);
        field(Baritone.class, "lookBehavior").set(backend, look);
        int[] resumes = {0};
        var navigator = new EmbeddedBaritoneNavigator(f.player, () -> { resumes[0]++; throw new ResumeProbe(); },
                () -> false, PlayerNav.ContextProvider.DEFAULT, true);
        field(EmbeddedBaritoneNavigator.class, "started").setBoolean(navigator, true);
        field(EmbeddedBaritoneNavigator.class, "pendingPause").setBoolean(navigator, true);
        var saved = new LinkedHashMap<Field, Object>();
        var policy = EmbeddedBaritonePolicy.snapshot();
        var actor = org.maiwithu.maicraft.client.runtime.ClientRuntime.actor();
        var actorClient = field(actor.getClass(), "minecraft"); Object previousActorClient = actorClient.get(actor);
        try {
            actorClient.set(actor, f.minecraft);
            save(saved, Minecraft.class, "instance", f.minecraft);
            save(saved, EmbeddedBaritoneRuntime.class, "backend", backend);
            save(saved, EmbeddedBaritoneRuntime.class, "world", f.world);
            save(saved, EmbeddedBaritoneRuntime.class, "tickingContext", f.context);
            save(saved, EmbeddedBaritoneRuntime.class, "owner", navigator);
            save(saved, EmbeddedBaritoneRuntime.class, "pendingStart", null);
            save(saved, EmbeddedBaritoneRuntime.class, "pendingPolicyOwner", null);
            save(saved, EmbeddedBaritoneRuntime.class, "pendingPolicyGoal", null);
            save(saved, TransportRuntime.class, "active", null);
            f.position(12.2, -0.4, false);
            check(!EmbeddedBaritoneRuntime.canHandOffMissedLanding(f.player), "descending over a gap still above its platform retains parkour");
            f.position(11.8, -0.4, false);
            check(EmbeddedBaritoneRuntime.canHandOffMissedLanding(f.player), "missed full-height support permits a prepared rescue handoff");
            f.world.scene.blocks.put(destination.below(), Blocks.STONE_SLAB.defaultBlockState());
            check(!EmbeddedBaritoneRuntime.canHandOffMissedLanding(f.player), "half-slab target uses its real lower support face");
            f.position(11.4, -0.4, false);
            check(EmbeddedBaritoneRuntime.canHandOffMissedLanding(f.player), "missed half slab is also detected");
            var falling = (MovementFall) f.memory.allocateInstance(MovementFall.class);
            IPath assisted = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                    (proxy, method, values) -> List.of(falling));
            field(PathExecutor.class, "path").set(executor, assisted);
            check(!EmbeddedBaritoneRuntime.canHandOffMissedLanding(f.player), "fall movement retains its own rescue and cleanup owner");
            field(PathExecutor.class, "path").set(executor, path);
            var lease = f.memory.allocateInstance(Class.forName(TransportRuntime.class.getName() + "$Lease"));
            field(TransportRuntime.class, "active").set(null, lease);
            check(!EmbeddedBaritoneRuntime.canHandOffMissedLanding(f.player), "flight or elevator transport ownership cannot be stolen");
            field(TransportRuntime.class, "active").set(null, null);

            var rescue = EmergencyLanding.find(f.context);
            check(rescue != null, "handoff has a legal native landing continuation before retiring the route");
            inputs.setInputForceState(Input.MOVE_FORWARD, true);
            check(EmbeddedBaritoneRuntime.handOffMissedLanding(f.player), "verified missed landing handoff succeeds");
            check(behavior.getCurrent() == null && !inputs.isInputForcedDown(Input.MOVE_FORWARD), "real forceCancel clears the old path and its keys");
            var orphan = EmbeddedBaritoneNavigator.class.getDeclaredMethod("requiresOrphanContinuation"); orphan.setAccessible(true);
            check(!(Boolean) orphan.invoke(navigator) && !field(EmbeddedBaritoneNavigator.class, "started").getBoolean(navigator),
                    "old orphan continuation is disabled and the original goal will be replanned");
            navigator.pause(); // The semantic holder's real PREEMPTED callback follows physical release.
            f.time++; EmergencyLanding.tick(f.context, rescue);
            int writes = f.bodyWrites;
            check(writes > 0, "rescue owns actual body steering in the handoff tick");
            EmbeddedBaritoneRuntime.tick(f.context, false);
            check(f.bodyWrites == writes && field(EmbeddedBaritoneRuntime.class, "owner").get(null) == null,
                    "frame-final orphan processing cannot reclaim or overwrite the rescue's inputs");
            f.position(0, 0, true);
            try { navigator.tick(); throw new AssertionError("resumption skipped original goal compilation"); }
            catch (ResumeProbe expected) { check(resumes[0] == 1, "logical task resumes by recompiling its original goal"); }
        } finally {
            actorClient.set(actor, previousActorClient);
            for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
            EmbeddedBaritonePolicy.install(null, policy.protectedCells(), policy.forbiddenBodyCells());
        }
        System.out.println("MissedLandingHandoffTest: passed");
    }
    private static void save(LinkedHashMap<Field, Object> saved, Class<?> type, String name, Object value) throws Exception {
        Field field = field(type, name); saved.put(field, field.get(null)); field.set(null, value);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static final class ResumeProbe extends RuntimeException { }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
