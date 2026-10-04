// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.Deque;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 原生库存稍后同步工作台或木板时，已满足的中间需求必须收起无用的更深材料分支。 */
public final class AcquisitionPrerequisiteRefreshTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(Items.CRAFTING_TABLE, 1);
        scenario(Items.OAK_PLANKS, 4);
        System.out.println("AcquisitionPrerequisiteRefreshTest: passed");
    }

    @SuppressWarnings("unchecked")
    private static void scenario(Item arriving, int amount) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var hoe = id(Items.WOODEN_HOE); var table = id(Items.CRAFTING_TABLE);
            var planks = id(Items.OAK_PLANKS); var log = id(Items.OAK_LOG);
            var sources = List.of(SemanticAcquireTaskRecord.Source.INVENTORY, SemanticAcquireTaskRecord.Source.CRAFT);
            var record = new SemanticAcquireTaskRecord("late-stock", 1000, List.of(hoe), 1, sources,
                    false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, record); task.onStart();
            var stack = SemanticAcquireCompanionTask.class.getDeclaredField("needs"); stack.setAccessible(true);
            var needs = (Deque<AcquisitionNeed>) stack.get(task);
            var root = needs.peek(); root.committedRecipeIds.add("minecraft:wooden_hoe");
            var station = new AcquisitionNeed(List.of(table), 1, 1, Set.of(hoe, table), Set.of(), Set.of("minecraft:wooden_hoe"), sources);
            var boards = new AcquisitionNeed(List.of(planks), 4, 2, Set.of(hoe, table, planks), Set.of(), Set.of(), sources);
            var timber = new AcquisitionNeed(List.of(log), 1, 3, Set.of(hoe, table, planks, log), Set.of(), Set.of(), sources);
            needs.push(station); needs.push(boards); needs.push(timber);
            // 更早的实际效果仍需向上传递；只收起尚未必要的需求，不能把已经发生过的游戏变化抹掉。
            timber.effectsObserved = true;
            var tick = SemanticAcquireCompanionTask.class.getDeclaredMethod("tickAcquisition"); tick.setAccessible(true);
            tick.invoke(task);
            check(needs.size() == 4, "中间件尚未到包时保留原材料需求");
            h.inventory.setItem(12, new ItemStack(arriving, amount)); h.nextTick();
            tick.invoke(task);
            check(needs.peek() == (arriving == Items.CRAFTING_TABLE ? root : station),
                    "中间件到包后应立即回上层，不再沿原木分支空转");
            check(needs.peek().effectsObserved, "收起旧分支仍保留已完成效果");
            check(h.inventory.countItem(arriving) == amount && h.blockUses() == 0 && h.itemUses() == 0,
                    "库存复查只改变后续需求，不假扣材料或提交新的原生动作");
        }
    }

    private static ResourceLocation id(Item item) { return BuiltInRegistries.ITEM.getKey(item); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
