// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Map;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.lighting.AutomaticLighting;
import org.maiwithu.maicraft.core.task.lighting.OffhandTorchPlacer;
import org.maiwithu.maicraft.intent.SemanticResultView;

/** 回放挖矿路上的低光、分包确认、明处、缺料和停用；只检查真实提交次数，不把预测方块算作成灯。 */
public final class AutomaticLightingTest {
    public static void main(String[] args) throws Exception {
        var lighting = AutomaticLighting.get();
        try (var h = new InteractionWorldTestHarness()) {
            prepareBody(h);
            h.enableInventoryTransactions(true); h.h.minecraft.screen = null;
            h.inventory.setItem(12, new ItemStack(Items.TORCH, 16));
            h.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            h.inventory.setItem(40, new ItemStack(Items.SHIELD));
            // 即使处于暗处且带着火把，初始状态与会话重置后都不能自行换掉副手或开始放置。
            var initial = new AutomaticLighting();
            tick(h, initial, true);
            check(!(boolean) initial.snapshot(h.player).get("enabled") && initial.snapshot(h.player).get("state").equals("disabled"),
                    "初始补光关闭");
            initial.configure(h.player, true, 8, List.of()); initial.reset();
            h.nextTick(); tick(h, initial, true);
            check(!(boolean) initial.snapshot(h.player).get("enabled") && h.blockUses() == 0
                    && h.mode.menuClicks == 0 && h.player.getOffhandItem().is(Items.SHIELD),
                    "会话重置后等待新的开启指令，保留原副手");
            h.nextTick();
            lighting.configure(h.player, true, 8, List.of());
            var busy = ClientRuntime.requireContext(h.player);
            busy.body().requestLook(0, 0, busy.tickRevision());
            tick(h, lighting, true);
            check(h.player.getOffhandItem().is(Items.SHIELD) && h.mode.menuClicks == 0,
                    "主动作瞄准时连副手换料也让位，不抢走盾牌");
            check((int) lighting.snapshot(h.player).get("observed_cells") == 2
                    && !(boolean) lighting.snapshot(h.player).get("coverage_verified"), "让位时仍记录经过的暗格");
            h.nextTick();
            tick(h, lighting, true);
            check(h.player.getOffhandItem().is(Items.TORCH) && h.player.getMainHandItem().is(Items.IRON_PICKAXE),
                    "暗处先准备副手，不选择主手火把");
            h.nextTick(); tick(h, lighting, true);
            // 补光先按正常转头速度转向灯位，对准后才出手；夹具按真实渲染帧推进这段转向。
            check(waitForNextPlacement(h, lighting, 60, 2) != null, "补光应当在转向预算内出手");
            check(h.blockUses() == 1 && h.mode.usedHand == InteractionHand.OFF_HAND, "副手原生出手一次: " + lighting.snapshot(h.player));
            var placer = (OffhandTorchPlacer) ActorControlTestHarness.field(AutomaticLighting.class, "placer").get(lighting);
            var target = placer.target();
            h.player.getOffhandItem().shrink(1); h.set(target.pos(), target.desiredState());
            for (int i = 0; i < 6; i++) { h.nextTick(); tick(h, lighting, true); }
            check(h.blockUses() == 1 && ((List<?>) lighting.snapshot(h.player).get("placements")).isEmpty(),
                    "客户端预测和扣料先到时等服务器，不抢放第二支");
            h.level.acknowledgedSequence = h.level.blockSequence;
            for (int i = 0; i < 3; i++) { h.nextTick(); tick(h, lighting, true); }
            // 服务器确认了火把但光照仍低时，仅报告缺口；不能站在同一格疯狂撒灯。
            for (int i = 0; i < 30; i++) { h.nextTick(); tick(h, lighting, true); }
            check(h.blockUses() == 1 && !(boolean) lighting.snapshot(h.player).get("coverage_verified"), "动作成功不能冒充覆盖成功");
            h.level.blockLight = 8;
            for (int i = 0; i < 10; i++) { h.nextTick(); tick(h, lighting, true); }
            var status = lighting.snapshot(h.player);
            check((boolean) status.get("coverage_verified") && h.blockUses() == 1 && h.player.getOffhandItem().getCount() == 15,
                    "实测达标后不再消耗");
            check(h.player.position().equals(new Vec3(4.5, 1, 4.5)), "助手没有移动或绕路");
            h.position(new Vec3(8.5, 1, 4.5)); h.level.blockLight = 0;
            h.nextTick(); tick(h, lighting, false);
            check(h.blockUses() == 1, "主任务独占时让位");
            // 走够两格后补光会挑新灯位并平滑转向它；每刻喂两帧推进转向，直到它真的再次出手。
            var placed = waitForNextPlacement(h, lighting, 90, 2);
            check(placed != null && h.blockUses() == 2, "走进下一片暗处才再次放置");
            // 照实机补上客户端预测的落点与副手扣料，服务器才能确认这一支。
            h.player.getOffhandItem().shrink(1); h.set(placed.pos(), placed.desiredState());
            h.level.acknowledgedSequence = h.level.blockSequence;
            for (int i = 0; i < 6; i++) { h.nextTick(); tick(h, lighting, true); }
            lighting.configure(h.player, false, 8, List.of());
            for (int i = 0; i < 4; i++) { h.nextTick(); tick(h, lighting, true); }
            status = lighting.snapshot(h.player);
            check(h.blockUses() == 2 && ((List<?>) status.get("placements")).size() == 2, "关闭仍结算已提交的那支灯");
            check(!(boolean) status.get("enabled") && status.get("state").equals("disabled"), "旧回执结清后仍保持关闭");
            var projected = SemanticResultView.data(Map.of("automatic_lighting", status));
            check(projected.get("automatic_lighting").equals(status), "任务投影保留全部暗格和灯位事实");
            h.level.lightAvailable = false;
            check(!(boolean) lighting.snapshot(h.player).get("coverage_verified"), "缺少光照读数不能声称安全");
        } finally { lighting.reset(); }
        try (var h = new InteractionWorldTestHarness()) {
            prepareBody(h);
            lighting.configure(h.player, true, 8, List.of());
            for (int i = 0; i < 8; i++) { tick(h, lighting, true); h.nextTick(); }
            check(h.blockUses() == 0 && lighting.snapshot(h.player).get("state").equals("missing_torches"),
                    "缺料不绕路、不造物，只保留缺火把状态");
            h.level.lightAvailable = false;
            tick(h, lighting, true);
            check(lighting.snapshot(h.player).get("state").equals("observation_unavailable"),
                    "辅助观察异常只记录问题，不抛出异常中断主任务");
        } finally { lighting.reset(); }
        System.out.println("AutomaticLightingTest: passed");
    }

    static void prepareBody(InteractionWorldTestHarness h) throws Exception {
        h.position(new Vec3(4.5, 1, 4.5));
        ActorControlTestHarness.field(Entity.class, "fluidHeight").set(h.player, new Object2DoubleOpenHashMap<>());
        ActorControlTestHarness.field(Entity.class, "dimensions").set(h.player, EntityType.PLAYER.getDimensions());
        h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
    }

    private static void tick(InteractionWorldTestHarness h, AutomaticLighting lighting, boolean allowed) {
        var context = ClientRuntime.requireContext(h.player);
        // 实际身体边界每刻先处理服务器同步，再轮到随行助手；夹具也执行同一顺序。
        ((DefaultMenuPort) context.menus()).advance(context);
        ((DefaultNativeActionPort) context.actions()).advance(context);
        context.body().applyMovement(new BodyControlPort.Movement(1, 0, false, false, true), context.tickRevision());
        lighting.tick(context, allowed);
    }

    /**
     * 推进补光直到再递上一支火把，返回这次提交的灯位；一直没有出手则返回 null。
     * 每刻喂若干渲染帧，等价于真实客户端在游戏刻之间跑的那几帧：补光必须先平滑转到灯位，测试不能靠瞬转绕过这段等待。
     * 落点方块与副手扣料由调用方按实际提交的那一支注入，避免这里和用例正文各扣一次。
     */
    private static BuildTaskRecord.Target waitForNextPlacement(InteractionWorldTestHarness h, AutomaticLighting lighting,
                                                               int ticks, int framesPerTick) throws Exception {
        var placer = (OffhandTorchPlacer) ActorControlTestHarness.field(AutomaticLighting.class, "placer").get(lighting);
        int before = h.blockUses();
        for (int tick = 0; tick < ticks; tick++) {
            h.nextTick();
            tick(h, lighting, true);
            h.renderFrames(framesPerTick);
            if (h.blockUses() > before) return placer.target();
        }
        return null;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
