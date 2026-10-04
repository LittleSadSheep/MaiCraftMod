// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.chain.MLGChain;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskSelector;
import org.maiwithu.maicraft.task.TaskState;

/** 驾驶座的残留下降速度不能触发自救，已有救援上车后让位；本次抓住的救援船仍按原流程落稳。 */
public final class MlgPassengerSuppressionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        passengerYieldsAndCanFallAgain();
        queuedSessionRetainsEffects();
        ownRescueBoatRetainsOwner();
        System.out.println("MlgPassengerSuppressionTest: passed");
    }

    private static void passengerYieldsAndCanFallAgain() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(12, -.0784, false);
        Entity seat = mount(f);
        var reflex = new MLGChain();
        check(!EmergencyLanding.triggered(f.player) && !reflex.canRun(f.player), "坐着悬空不属于自由坠落");
        f.position(12, -1.2, false);
        check(!EmergencyLanding.triggered(f.player) && EmergencyLanding.find(f.context) == null,
                "载具下降再快也不能建立玩家落地救援");
        check(!reflex.prepareMissedLandingTakeover(f.player), "接管入口不能抢走座椅控制");
        f.time++; reflex.tick(f.context);
        check(f.uses == 0 && f.selections == 0 && f.bodyWrites == 0 && f.player.getVehicle() == seat,
                "排队的空会话不操作、不下车");
        field(Entity.class, "vehicle").set(f.player, null);
        check(reflex.canRun(f.player), "真正离开载具坠落后仍可立即自救");
    }

    private static void queuedSessionRetainsEffects() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(2, -1, false); f.player.setXRot(90);
        check(f.session.prepareAlreadyHeld(f.context), "先准备原生水桶");
        f.tick(); f.tick(); f.tick();
        check(f.uses == 1 && Boolean.TRUE.equals(f.session.diagnostics().get("confirmed_own_placement")), "放水已确认");
        var reflex = active(f);
        mount(f);
        Task driving = new Task() {
            public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
            public void stop(LocalPlayer player, StopReason reason) {}
            public String name() { return "stop_aircraft_throttle"; }
        };
        check(TaskSelector.select(List.of(reflex), null, driving, List.of(), f.player) == driving,
                "旧自救必须让驾驶员执行关油门");
        int writes = f.bodyWrites;
        f.time++; reflex.tick(f.context);
        check(field(MLGChain.class, "session").get(reflex) == null && f.uses == 1 && f.bodyWrites == writes,
                "上座椅后的旧 tick 只结算原生效果");
        var facts = LandingAssistPolicy.diagnosticState();
        check(Boolean.TRUE.equals(facts.get("confirmed_own_placement"))
                        && Boolean.TRUE.equals(facts.get("disabled_while_riding")) && f.world.water,
                "保留已放未收的水与乘坐停用原因");
    }

    private static void ownRescueBoatRetainsOwner() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        Entity boat = mount(f);
        var controller = new BoatLandingAssist(new BoatLandingSnapshot.Plan(new BlockPos(0, 12, 0),
                BlockPos.ZERO, null, boat.getUUID(), new Vec3(.5, 0, .5), true));
        var rescue = new LandingBoatRescue(f.session.plan(), true);
        field(LandingBoatRescue.class, "boat").set(rescue, controller);
        field(LandingAssistSession.class, "boat").set(f.session, rescue);
        var reflex = active(f);
        check(reflex.canRun(f.player), "本次救援抓住的船仍须完成原生落稳和下船");
        mount(f);
        check(!reflex.canRun(f.player), "换到其他载具后不能沿用救援船身份");
    }

    // 仅测试替身写入原生乘坐关系，生产控制始终由服务器的实体乘客同步确认。
    private static Entity mount(WaterLandingReplayTest.Fixture f) throws Exception {
        var entity = (Boat) f.memory.allocateInstance(Boat.class);
        field(Entity.class, "uuid").set(entity, UUID.randomUUID());
        field(Entity.class, "vehicle").set(f.player, entity);
        return entity;
    }
    private static MLGChain active(WaterLandingReplayTest.Fixture f) throws Exception {
        var reflex = new MLGChain();
        field(MLGChain.class, "session").set(reflex, f.session);
        field(MLGChain.class, "attentionActive").setBoolean(reflex, true);
        return reflex;
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
