package org.maiwithu.maicraft.core.pathing.baritone.landing;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.path.PathExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.task.chain.MLGChain;
import sun.misc.Unsafe;

/**
 * 检查已有落地救援时自救不会再开一套放水流程；原会话完成或导航失去控制后，新的紧急自救仍可启动。
 */
public final class PlannedWaterReflexTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        TestPlayer player = (TestPlayer) memory.allocateInstance(TestPlayer.class);
        ClientLevel world = (ClientLevel) memory.allocateInstance(ClientLevel.class);
        field(LocalPlayer.class, "clientLevel").set(player, world);
        field(LocalPlayer.class, "abilities").set(player, new Abilities());
        field(LocalPlayer.class, "deltaMovement").set(player, new Vec3(0, -1.4, 0));
        player.inventory = new Inventory(player); player.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
        IPlayerContext context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, values) -> {
                    if (method.getName().equals("player")) return player;
                    throw new AssertionError(method.getName());
                });
        var session = new LandingAssistSession(new LandingAssistPlan(LandingAssistPlan.Kind.WATER,
                BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO.below(), Direction.UP, false));
        MovementFall fall = (MovementFall) memory.allocateInstance(MovementFall.class);
        field(MovementFall.class, "landingAssist").set(fall, session);
        IPath path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("movements")) return List.of(fall);
                    throw new AssertionError(method.getName());
                });
        PathExecutor executor = (PathExecutor) memory.allocateInstance(PathExecutor.class);
        field(PathExecutor.class, "groundJump").set(executor, new org.maiwithu.maicraft.core.pathing.baritone.GroundJumpContinuation());
        field(PathExecutor.class, "path").set(executor, path);
        PathingBehavior behavior = (PathingBehavior) memory.allocateInstance(PathingBehavior.class);
        field(PathingBehavior.class, "current").set(behavior, executor);
        Baritone backend = (Baritone) memory.allocateInstance(Baritone.class);
        field(Baritone.class, "playerContext").set(backend, context);
        field(Baritone.class, "pathingBehavior").set(backend, behavior);
        var saved = new LinkedHashMap<Field, Object>();
        try {
            for (String name : new String[]{"backend", "world", "owner"}) {
                Field field = field(EmbeddedBaritoneRuntime.class, name); saved.put(field, field.get(null));
                field.set(null, switch (name) {
                    case "backend" -> backend; case "world" -> world;
                    default -> memory.allocateInstance(EmbeddedBaritoneNavigator.class);
                });
            }
            var reflex = new MLGChain();
            check(!reflex.canRun(player), "rapid descent cannot steal the planned fall's bucket and cleanup session");
            field(LandingAssistSession.class, "submitted").setBoolean(session, true);
            check(!reflex.canRun(player), "pending native water placement retains its scheduler owner");
            field(LandingAssistSession.class, "complete").setBoolean(session, true);
            check(reflex.canRun(player), "a settled planned session does not disable an independent emergency fall");
            field(LandingAssistSession.class, "complete").setBoolean(session, false);
            field(EmbeddedBaritoneRuntime.class, "owner").set(null, null);
            check(reflex.canRun(player), "abandoned navigation cannot keep suppressing survival reflexes");
        } finally {
            for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        }
        System.out.println("PlannedWaterReflexTest: passed");
    }
    private static final class TestPlayer extends LocalPlayer {
        Inventory inventory;
        private TestPlayer() { super(null, null, null, null, null, false, false); }
        public Inventory getInventory() { return inventory; }
        public ItemStack getMainHandItem() { return inventory.getSelected(); }
        public ItemStack getOffhandItem() { return ItemStack.EMPTY; }
        public boolean isInWater() { return false; }
        public boolean isSwimming() { return false; }
        public boolean onClimbable() { return false; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
