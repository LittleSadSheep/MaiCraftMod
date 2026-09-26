package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackNativeAdapter;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.integration.jetpack.MovingFlightTarget;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import sun.misc.Unsafe;

/** 在接近旧参考点之前延伸已加载走廊，原生会话保持飞行；只有最终目标或必要备用出口进入落地。 */
public final class ContinuousTravelTargetTest {
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "fixture", "create_jetpack:netherite_jetpack", true, true, 900, 17000, .016, .32, .6, -.03, .08);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var goal = NavGoal.exact(new BlockPos(200, 10, 0));
        var target = new ContinuousTravelTarget(goal, new Vec3(24.5, 10, .5), LongSets.emptySet());
        var space = new Space(); Vec3 current = new Vec3(12.5, 12, .5);
        check(target.extend(current, new Vec3(56.5, 10, .5), false, space, POWER), "extend before reaching the old endpoint");
        check(!target.landingSelected() && target.point().y == 12 && !target.touchdown(), "local forward progress remains airborne");
        check(!target.extend(new Vec3(40.5, 12, .5), new Vec3(88.5, 10, .5), false, space, POWER), "unloaded frontier is never treated as clear air");
        space.loaded = 112;
        check(target.extend(new Vec3(40.5, 12, .5), new Vec3(88.5, 10, .5), false, space, POWER), "new loaded terrain extends the same journey");
        var noFuel = new JetpackNativeAdapter.Snapshot(true, "fixture", "pack", true, true, 1, 100, .016, .32, .6, -.03, .08);
        check(!target.extend(new Vec3(72.5, 12, .5), new Vec3(104.5, 10, .5), false, space, noFuel), "reserve must cover both cruising and the fallback descent");
        space.support = false;
        check(!target.extend(new Vec3(72.5, 12, .5), new Vec3(104.5, 10, .5), false, space, POWER), "lava or void supplies no fallback support");
        space.support = true; space.blocked = true;
        check(!target.extend(new Vec3(72.5, 12, .5), new Vec3(104.5, 10, .5), false, space, POWER), "direction cannot override an obstructed body corridor");
        space.blocked = false; space.loaded = 256;
        check(!target.extend(new Vec3(170.5, 12, .5), new Vec3(199.5, 10, .5), true, space, POWER), "a nearby landing cannot satisfy the exact final goal");
        nativeRetarget(target);
        check(target.extend(new Vec3(170.5, 12, .5), new Vec3(200.5, 10, .5), true, space, POWER)
                && target.landingSelected() && goal.isAt(BlockPos.containing(target.point())), "final arrival restores the original exact destination");
        System.out.println("ContinuousTravelTargetTest: passed");
    }
    private static void nativeRetarget(ContinuousTravelTarget target) throws Exception {
        // 直接调用原生会话的换向环节，检查延伸不创建 LAND／RESTORE 阶段且保留可用退出点。
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        field(LocalPlayer.class, "position").set(player, new Vec3(40.5, 12, .5));
        var context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                (proxy, method, values) -> { if (method.getName().equals("player")) return player; throw new AssertionError(method.getName()); });
        // 用同一个方向目标的已验证点与备用地面，空间检查在上面的真实候选筛选中单独覆盖。
        var moving = new FlightView(target, new Space()); moving.space.loaded = 128;
        var session = new JetpackFlightSession(moving, LongSets.emptySet());
        field(JetpackFlightSession.class, "power").set(session, POWER);
        var old = new JetpackRoute.Plan(List.of(new Vec3(.5, 10, .5), new Vec3(24.5, 12, .5)), List.of(new Vec3(.5, 10, .5)), 500);
        field(JetpackFlightSession.class, "route").set(session, old);
        var phase = field(JetpackFlightSession.class, "phase"); setPhase(phase, session);
        var retarget = JetpackFlightSession.class.getDeclaredMethod("retarget", LocalPlayerContext.class); retarget.setAccessible(true);
        retarget.invoke(session, context);
        var next = (JetpackRoute.Plan) field(JetpackFlightSession.class, "route").get(session);
        check(phase.get(session).toString().equals("FLY") && next.points().getLast().equals(target.point())
                && next.emergencyLandings().contains(target.emergencyLanding()), "native route extension keeps flight and remembers the current safe exit");
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static void setPhase(Field field, Object session) throws Exception {
        field.set(session, Enum.valueOf((Class) field.getType(), "FLY"));
    }
    private record FlightView(ContinuousTravelTarget target, Space space) implements MovingFlightTarget {
        public boolean update(LocalPlayerContext c) { return true; }
        public Vec3 point() { return target.point(); }
        public Vec3 velocity() { return Vec3.ZERO; }
        public boolean contact() { return false; }
        public boolean touchdown() { return false; }
        public boolean landingSelected() { return target.landingSelected(); }
        public Vec3 emergencyLanding() { return target.emergencyLanding(); }
        public JetpackRoute.Space space(LocalPlayerContext c, LongSet f) { return space; }
        public Map<String, Object> diagnostics() { return target.diagnostics(); }
    }
    private static final class Space implements JetpackRoute.Space {
        int loaded = 64; boolean support = true, blocked;
        public boolean clear(Vec3 a, Vec3 b) { return !blocked && a.x >= 0 && b.x >= 0 && a.x < loaded && b.x < loaded && a.y >= 10 && b.y >= 10; }
        public Vec3 landingBelow(Vec3 p) { return support && p.x < loaded && p.y >= 10 ? new Vec3(p.x, 10, p.z) : null; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var f = owner.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
