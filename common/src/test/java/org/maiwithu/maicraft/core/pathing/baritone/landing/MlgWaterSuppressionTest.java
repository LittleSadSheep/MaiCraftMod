// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.task.chain.MLGChain;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskSelector;
import org.maiwithu.maicraft.task.TaskState;

/** 水中不新开 MLG，空中开始的旧会话入水后也必须让位；真实放置证据保留，不能继续抢桶和准星。 */
public final class MlgWaterSuppressionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        activePreparationYields();
        freshWaterDoesNothing();
        confirmedWaterIsRetained();
        pendingWaterIsNotRepeated();
        preparedCandidateDoesNotRevive();
        System.out.println("MlgWaterSuppressionTest: passed");
    }

    private static void activePreparationYields() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(true);
        f.position(.2, -.8, false); f.player.wet = true; f.player.fallDistance = 12;
        f.player.inventory.setItem(0, ItemStack.EMPTY); f.player.inventory.setItem(3, new ItemStack(Items.WATER_BUCKET));
        f.player.setYRot(37); f.player.setXRot(-12);
        var reflex = running(f);
        check(!reflex.canRun(f.player), "旧 MLG 会话不能绕过水中停用条件");
        Task primary = new Task() {
            public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
            public void stop(LocalPlayer player, StopReason reason) {}
            public String name() { return "water_work"; }
        };
        check(TaskSelector.select(List.of(reflex), null, primary, List.of(), f.player) == primary,
                "入水后调度器应选中主任务");
        check(!reflex.prepareMissedLandingTakeover(f.player), "错过落点的接管入口也不能在水中建新救援");
        f.time++; reflex.tick(f.context);
        check(field("session").get(reflex) == null && f.uses == 0 && f.selections == 0 && f.bodyWrites == 0,
                "已排队的旧 tick 只收尾，不倒水、不选桶、不写新的移动");
        check(f.player.getYRot() == 37 && f.player.getXRot() == -12, "入水收尾不能继续低头瞄地");
    }

    private static void freshWaterDoesNothing() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(true);
        f.position(.2, -1.2, false); f.player.wet = true;
        var reflex = new MLGChain(); f.player.setXRot(-10);
        check(!reflex.canRun(f.player), "快速下沉也不是空中 MLG");
        f.time++; reflex.tick(f.context);
        check(f.uses == 0 && f.selections == 0 && f.bodyWrites == 0 && f.player.getXRot() == -10,
                "没有救援会话时，水中 tick 完全不操作身体");
        f.player.wet = false; f.player.swimming = true;
        check(!reflex.canRun(f.player) && !reflex.prepareMissedLandingTakeover(f.player), "游泳状态也不能触发或接管 MLG");
        f.time++; reflex.tick(f.context);
        check(f.uses == 0 && f.bodyWrites == 0, "游泳中的执行入口同样不写身体");
    }

    private static void confirmedWaterIsRetained() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(2, -1, false); f.player.setXRot(90);
        check(f.session.prepareAlreadyHeld(f.context), "水桶已在主手");
        f.tick(); f.tick(); f.tick();
        check(f.uses == 1 && Boolean.TRUE.equals(f.session.diagnostics().get("confirmed_own_placement")), "先确认真实放置");
        var reflex = running(f);
        f.position(.2, 0, false); f.player.wet = true; f.player.fallDistance = 0;
        f.time++; reflex.tick(f.context);
        check(field("session").get(reflex) == null && !reflex.canRun(f.player) && f.uses == 1 && f.world.water,
                "入水后留下已放水源，不继续吸水或重放水桶动作");
        check(Boolean.TRUE.equals(LandingAssistPolicy.diagnosticState().get("confirmed_own_placement"))
                && !Boolean.TRUE.equals(LandingAssistPolicy.diagnosticState().get("removed_own_aid")), "放置与未回收事实都保留");
        f.player.wet = false; f.position(0, 0, true);
        check(!reflex.canRun(f.player), "上岸后不会复活已结束的旧会话");
        f.position(12, -1, false);
        check(reflex.canRun(f.player), "新的真实空中坠落仍可触发 MLG");
    }

    private static void pendingWaterIsNotRepeated() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(2, -1, false); f.player.setXRot(90); f.inventoryEvidence = false;
        check(f.session.prepareAlreadyHeld(f.context), "准备有待确认效果的原生放置"); f.tick();
        var reflex = running(f);
        f.position(.2, 0, false); f.player.wet = true;
        f.time++; reflex.tick(f.context);
        check(f.uses == 1 && f.releases == 1 && f.world.water && field("session").get(reflex) == null,
                "旧点击未确认时只结束原使用，不重新倒水或吸水");
        check(!Boolean.TRUE.equals(LandingAssistPolicy.diagnosticState().get("confirmed_own_placement")),
                "缺少返桶证据时不能声称已经确认放置");
    }

    private static void preparedCandidateDoesNotRevive() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var reflex = new MLGChain(); field("session").set(reflex, f.session);
        f.position(.2, -.8, false); f.player.wet = true;
        check(!reflex.prepareMissedLandingTakeover(f.player) && field("session").get(reflex) == null,
                "入水时作废尚未接管的候选，不等待下一次调度");
        f.player.wet = false; f.position(0, 0, true);
        check(!reflex.canRun(f.player) && f.uses == 0, "上岸后不为旧候选重新启动自救");
    }

    private static MLGChain running(WaterLandingReplayTest.Fixture f) throws Exception {
        var reflex = new MLGChain(); field("session").set(reflex, f.session); field("attentionActive").setBoolean(reflex, true);
        return reflex;
    }
    private static Field field(String name) throws Exception { var field = MLGChain.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
