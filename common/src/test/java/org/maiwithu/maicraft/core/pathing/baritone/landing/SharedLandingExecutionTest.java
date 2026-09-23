package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.util.Set;
import sun.misc.Unsafe;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import org.maiwithu.maicraft.core.task.chain.MLGChain;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.movements.MovementFall;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/**
 * 把预先计划、意外下落和已在执行的下落交给同一救援流程，比较放置、确认与回收；还检查现成船优先级、干草减伤及不能转换到世界位置的射线。
 */
public final class SharedLandingExecutionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var client = (Minecraft) memory.allocateInstance(Minecraft.class);
        field(Minecraft.class, "gameThread").set(client, Thread.currentThread());
        var instance = field(Minecraft.class, "instance"); Object previous = instance.get(null);
        instance.set(null, client);
        try {
            water(false, true); water(true, true); water(true, false); runningFallRescue(); hay(); rejectsPlotStorageRay();
            earlyMaterialFreeTrigger();
            existingBoatBeforeSupply();
        } finally { instance.set(null, previous); }
        System.out.println("SharedLandingExecutionTest: passed");
    }

    // 同一场景依次保留水桶、只保留现成船、两者都没有，检查选择顺序且此时不发起物品操作。
    private static void existingBoatBeforeSupply() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(2.5, -0.08, false); f.player.fallDistance = 5;
        var id = UUID.randomUUID();
        field(Entity.class, "uuid").set(f.player, id);
        var connection = (ClientPacketListener) f.memory.allocateInstance(ClientPacketListener.class);
        var info = f.memory.allocateInstance(PlayerInfo.class);
        field(ClientPacketListener.class, "playerInfoMap").set(connection, Map.of(id, info));
        var context = (LocalPlayerContext) Proxy.newProxyInstance(
                LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{LocalPlayerContext.class}, (proxy, method, values) ->
                        method.getName().equals("connection") ? connection : method.invoke(f.context, values));
        var boat = (Boat) f.memory.allocateInstance(Boat.class);
        field(Entity.class, "type").set(boat, EntityType.BOAT);
        field(Entity.class, "uuid").set(boat, UUID.randomUUID());
        field(Entity.class, "position").set(boat, new Vec3(0.5, 0, 0.5));
        field(Entity.class, "bb").set(boat, BoatLandingGeometry.boatBox(boat.position()));
        field(Entity.class, "deltaMovement").set(boat, Vec3.ZERO);
        field(Entity.class, "passengers").set(boat, ImmutableList.of());
        field(Entity.class, "onGround").setBoolean(boat, true);
        f.world.observedEntities = List.of(boat);
        var adopt = MovementFall.class.getDeclaredMethod("adoptEmergencyLanding",
                LocalPlayerContext.class); adopt.setAccessible(true);
        for (int mode = 0; mode < 3; mode++) {
            if (mode > 0) f.player.inventory.setItem(0, ItemStack.EMPTY);
            if (mode == 2) f.world.observedEntities = List.of();
            var movement = (MovementFall) f.memory.allocateInstance(MovementFall.class);
            field(Movement.class, "dest").set(movement, new BetterBlockPos(BlockPos.ZERO));
            adopt.invoke(movement, context);
            check(mode == 1 ? movement.landingBoat() != null && movement.landingAssist() == null
                            : movement.landingBoat() == null && movement.landingAssist() != null,
                    "protection preference must be carried water, legal existing boat, then conditional AE supply; mode=" + mode);
        }
        check(f.uses == 0 && f.selections == 0, "choosing protection must not issue item or AE actions");
    }

    private static void earlyMaterialFreeTrigger() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.player.inventory.setItem(0, ItemStack.EMPTY);
        f.position(24, -0.08, false);
        check(EmergencyLanding.triggered(f.player), "predicted injury starts rescue before fast descent, even without a carried aid");
        var session = EmergencyLanding.find(f.context);
        check(session != null && f.uses == 0, "missing inventory creates a conditional supply plan, not placement evidence");
        f.position(2, -0.08, false);
        check(!EmergencyLanding.triggered(f.player), "harmless small descent does not open a material acquisition");
        f.player.getAbilities().mayfly = true; f.position(24, -0.08, false);
        check(!EmergencyLanding.triggered(f.player), "correct vanilla flight immunity retains harmless admission at low speed");
    }

    private static void runningFallRescue() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.player.inventory.setItem(0, ItemStack.EMPTY);
        f.player.inventory.setItem(3, new ItemStack(Items.WATER_BUCKET));
        f.position(24, -0.8, false);
        // 导航估算可能依据同步到的原版飞行免伤；若估算与实际下降不符，独立的快速坠落触发仍由当前移动所有者处理。
        // 不得改变原版 mayfly 伤害规则。
        f.player.getAbilities().mayfly = true;
        check(EmergencyLanding.triggered(f.player), "survival fast-fall rescue is independent of the damage estimate");
        var movement = (MovementFall) f.memory.allocateInstance(
                MovementFall.class);
        field(Movement.class, "dest").set(movement, new BetterBlockPos(BlockPos.ZERO));
        field(Movement.class, "currentState").set(movement,
                new MovementState().setStatus(MovementStatus.RUNNING));
        field(Movement.class, "ctx").set(movement, Proxy.newProxyInstance(
                IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("player")) return f.player;
                    throw new AssertionError(method.getName());
                }));
        check(!movement.safeToCancel() && movement.landingAssist() == null,
                "ordinary launched fall cannot be suspended for a separate reflex");
        var adopt = movement.getClass().getDeclaredMethod("adoptEmergencyLanding",
                LocalPlayerContext.class);
        adopt.setAccessible(true); adopt.invoke(movement, f.context);
        var rescue = movement.landingAssist();
        check(rescue != null && rescue.plan().feet().equals(BlockPos.ZERO), "same movement adopts its own destination rescue");
        adopt.invoke(movement, f.context);
        check(movement.landingAssist() == rescue, "repeated offer preserves the native placement and cleanup owner");
        double y = 24, velocity = -0.8;
        while (y > 0) {
            f.position(y, velocity, false); f.player.setXRot(90); f.time++; rescue.tick(f.context);
            y = Math.max(0, y + velocity); velocity = (velocity - 0.08) * (double) 0.98F;
            f.player.fallDistance = (float) (24 - y);
            if (f.world.water && y < 0.8) { f.player.wet = true; f.player.fallDistance = 0; }
        }
        check(f.uses == 1 && f.world.water && f.selections == 1, "owned fallback selects hotbar and places before 24-block impact");
        f.position(0, 0, true); f.player.wet = true; f.player.fallDistance = 0;
        for (int i = 0; i < 60 && !rescue.complete(); i++) { f.time++; rescue.tick(f.context); }
        check(rescue.complete() && !rescue.failed() && f.uses == 2 && !f.world.water,
                "in-owner emergency executes the same contact, recovery and stable-support confirmation");
    }

    private static void water(boolean emergency, boolean inventoryEvidence) throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var reflex = new MLGChain();
        f.position(12, 0, true);
        if (emergency) {
            f.player.inventory.setItem(0, ItemStack.EMPTY);
            f.player.inventory.setItem(3, new ItemStack(Items.WATER_BUCKET));
        } else check(f.session.prepareAlreadyHeld(f.context) && f.session.prepare(f.context), "planned pre-equipment");
        f.inventoryEvidence = inventoryEvidence;
        LandingAssistSession session = f.session;
        double y = 12, velocity = -0.8;
        while (y > 0) {
            f.position(y, velocity, false);
            if (emergency) {
                check(reflex.canRun(f.player), "unexpected fall and its in-flight receipt keep the reflex active");
                f.time++; reflex.tick(f.context);
                session = (LandingAssistSession) field(MLGChain.class, "session").get(reflex);
                check(session != null, "native support rays selected an emergency landing plan");
            } else { f.player.setXRot(90); f.tick(); }
            check(!session.complete(), "neither trigger may end before actual touchdown");
            y = Math.max(0, y + velocity); velocity = (velocity - 0.08) * (double) 0.98F;
            f.player.fallDistance = (float) (12 - y);
            if (f.world.water && y < 0.8) { f.player.wet = true; f.player.fallDistance = 0; }
        }
        check(f.uses == 1 && f.world.water, "both triggers submitted one reachable native placement");
        f.position(0, 0, true); f.player.wet = true; f.player.fallDistance = 0;
        for (int i = 0; i < 60 && !session.complete(); i++) {
            if (emergency) { check(reflex.canRun(f.player), "recovery retains the emergency owner"); f.time++; reflex.tick(f.context); }
            else f.tick();
        }
        check(session.complete(), "shared session must terminate after its bounded evidence windows");
        if (inventoryEvidence) {
            check(!session.failed() && f.uses == 2 && !f.world.water && f.player.getMainHandItem().is(Items.WATER_BUCKET),
                    "both triggers require source, bucket, native water contact, pickup and fresh support");
            check(Boolean.TRUE.equals(session.diagnostics().get("native_water_contact"))
                    && Boolean.TRUE.equals(session.diagnostics().get("removed_own_aid")), "shared authoritative evidence recorded");
        } else check(session.failed() && f.uses == 1 && f.world.water,
                "the emergency trigger cannot bypass missing inventory evidence or recover unowned water");
        check(f.selections == (emergency ? 1 : 0), "only emergency hotbar preparation selected an item");
        if (emergency) check(!reflex.canRun(f.player), "confirmed or failed terminal session yields the reflex owner");
    }

    // 用原版干草落地方法确认它减少伤害但不免伤；成功记录必须保留实际生命损失。
    private static void hay() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var plan = new LandingAssistPlan(LandingAssistPlan.Kind.HAY, BlockPos.ZERO.above(), BlockPos.ZERO,
                BlockPos.ZERO.below(), Direction.UP, true);
        check(plan.survives(budget(4), 17, false) && !plan.survives(budget(3), 17, false),
                "native 0.2 cushioning may save a fall, but an equal-to-health hit remains fatal");
        var waterOnly = new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.HAY), true, false, false);
        check(waterOnly.plans(f.world, BlockPos.ZERO, p -> false).isEmpty(), "water-only permission cannot authorize hay");
        var landing = new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.HAY), true, true, false);
        check(landing.plans(f.world, BlockPos.ZERO, p -> false).stream().anyMatch(p -> p.kind() == LandingAssistPlan.Kind.HAY),
                "general landing assistance retains the native hay option");
        f.world.scene.aid = Blocks.HAY_BLOCK.defaultBlockState();
        f.position(1, 0, true);
        field(Level.class, "damageSources").set(f.world, f.memory.allocateInstance(DamageSources.class));
        var session = new LandingAssistSession(plan);
        f.time++; session.tick(f.context);
        // HayBlock.fallOn 本身会向原生伤害回调传入 0.2。测试夹具在此处应用普通向上取整伤害；生产预算也会独立核验同一次命中。
        Blocks.HAY_BLOCK.fallOn(f.world, Blocks.HAY_BLOCK.defaultBlockState(), BlockPos.ZERO, f.player, 16);
        check(f.player.hayMultiplier == 0.2F && f.player.getHealth() == 17, "native hay behavior must cushion rather than reset damage");
        for (int i = 0; i < 12; i++) { f.time++; session.tick(f.context); }
        check(session.complete() && !session.failed() && Boolean.TRUE.equals(session.diagnostics().get("mitigated_with_damage"))
                        && ((Number) session.diagnostics().get("health_lost")).floatValue() == 3,
                "confirmed hay support and survival succeed while explicitly reporting the observed injury");
        check(f.uses == 0 && f.world.scene.aid.is(Blocks.HAY_BLOCK), "existing hay gains no removal ownership");
    }

    private static FallDamageBudget budget(float health) {
        return new FallDamageBudget(health, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false);
    }
    private static void rejectsPlotStorageRay() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(12, -1, false);
        // 原生集成可能同时以存储空间返回位置和方块格，也可能保留世界交点但只返回存储空间格；两种结果在此处都不可用。
        var storage = new BlockPos(28_000_000, 70, 28_000_000);
        for (var location : new Vec3[]{
                Vec3.atCenterOf(storage), new Vec3(0.5, 0, 0.5)}) {
            f.world.nativeHit = new BlockHitResult(location, Direction.UP, storage, false);
            check(EmergencyLanding.find(f.context) == null && f.uses == 0 && f.selections == 0,
                    "plot-storage hits cannot become global landing goals or native item operations");
            check("unsupported".equals(LandingAssistPolicy.diagnosticState().get("support_state")),
                    "unverified physical support must report its unsupported coordinate frame");
        }
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
