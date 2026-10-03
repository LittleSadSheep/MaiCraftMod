// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import org.maiwithu.maicraft.core.task.collect.CollectItemsApproach;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.intent.SemanticResultView;
import com.google.gson.Gson;

/** 一格矿洞后的物品需要补挖脚位和头顶；候选、开路授权与原生拾取完成分开验证。 */
public final class PickupClearanceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(4.5, 1, 8.5));
            for (int x = 7; x <= 10; x++) for (int y = 1; y <= 4; y++) for (int z = 6; z <= 10; z++)
                h.set(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
            BlockPos source = new BlockPos(8, 2, 8), stance = new BlockPos(7, 1, 8);
            h.set(source, Blocks.AIR.defaultBlockState());
            var drop = ItemEntityReceiptsTest.item(h, 571, new Vec3(8.8, 2, 8.5), new ItemStack(Items.RAW_IRON));
            check(!CollectItemsApproach.goal(h.player, List.of(drop)).goal().isAt(stance), "现有洞口不足以站人");
            check(CollectItemsApproach.goal(h.player, List.of(drop), true).goal().isAt(stance), "授权后可把需补挖的接触站位交给真实寻路");
            NavigationSafetyContext.withProtectedArea(List.of(stance.above()), List.of(), () -> {
                check(!CollectItemsApproach.goal(h.player, List.of(drop), true).goal().isAt(stance), "拾取不能拆明确保护的顶棚"); return null;
            });
            h.set(stance.above(), Blocks.CHEST.defaultBlockState());
            check(!CollectItemsApproach.goal(h.player, List.of(drop), true).goal().isAt(stance), "容器不成为补挖目标");
            h.set(stance.above(), Blocks.STONE.defaultBlockState()); h.set(stance.below(), Blocks.AIR.defaultBlockState());
            check(!CollectItemsApproach.goal(h.player, List.of(drop), true).goal().isAt(stance), "不能把无底洞当已有支撑的补挖站位");
            h.set(stance.below(), Blocks.STONE.defaultBlockState());
            var record = new CollectItemsTaskRecord("pickup-through-hole", 1000, Set.of(Items.RAW_IRON), 16,
                    "raw iron", Set.of(drop.getUUID()), null, true);
            var task = new CollectItemsCompanionTask(h.player, record); task.start(h.player);
            check(task.tick(h.player) == TaskState.RUNNING, "同一 collect_items 任务开始接近");
            var goal = (GoalCompiler.Compiled) call(task, "targetGoal");
            check(goal.goal().isAt(stance), "任务入口实际使用可补挖站位，而非只锁定掉落物所在格");
            var nav = (PlayerNav) ActorControlTestHarness.field(AbstractCompanionTask.class, "nav").get(task);
            var transport = ActorControlTestHarness.field(PlayerNav.class, "navigator").get(nav);
            var context = (PlayerNav.ContextProvider) ActorControlTestHarness.field(transport.getClass(), "policy").get(transport);
            var recordedTerrain = (TerrainBill) ActorControlTestHarness.field(transport.getClass(), "journey").get(transport);
            check(context.permit() == TerrainPermit.TERRAFORM, "原生导航收到开路许可");
            // 回放服务器先后同步脚位、头顶被挖开；只清一格仍不可通行，齐全后无需另发旅行任务。
            recordedTerrain.addBreak(stance, Blocks.STONE.defaultBlockState());
            h.set(stance, Blocks.AIR.defaultBlockState());
            check(!CollectItemsApproach.goal(h.player, List.of(drop)).goal().isAt(stance), "头顶未通不能误判已到达");
            h.set(stance.above(), Blocks.AIR.defaultBlockState());
            recordedTerrain.addBreak(stance.above(), Blocks.STONE.defaultBlockState());
            check(CollectItemsApproach.goal(h.player, List.of(drop)).goal().isAt(stance), "补齐后成为真实接触站位");
            h.position(Vec3.atBottomCenterOf(stance));
            check(NativePickupReceipt.insideVanillaTouchEnvelope(h.player, drop), "邻格即可接触物品，物品实体不会阻塞站立");
            check(task.tick(h.player) == TaskState.RUNNING && record.getCollected() == 0, "接近到位仍须等实际入包");
            h.level.entities.remove(drop.getId()); h.inventory.setItem(0, new ItemStack(Items.RAW_IRON));
            check(task.tick(h.player) == TaskState.RUNNING && task.tick(h.player) == TaskState.SUCCESS,
                    "同一拾取任务在掉落消失和背包同步后完成");
            var result = SemanticResultView.result(task.result(TaskState.SUCCESS));
            var navigation = new Gson().toJsonTree(result.data()).getAsJsonObject().getAsJsonObject("pickup_navigation");
            check(navigation.get("may_alter_terrain").getAsBoolean()
                    && navigation.getAsJsonObject("confirmed_terrain_changes").getAsJsonObject("broken").getAsJsonArray("minecraft:stone").size() == 2,
                    "默认回执保留本次拾取的开路授权及已经确认的脚位和头顶破坏");
        }
        System.out.println("PickupClearanceTest: authorized headroom repair and native delivery passed");
    }
    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
