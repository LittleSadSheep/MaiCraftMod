// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.ArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.inventory.DropCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.DropItemsTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 从只有直线巷道的实际任务入口回放：先原生挖好侧袋，再整份丢弃；开不出位置时原物保留。 */
public final class DiscardPocketTaskTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean blocked : new boolean[]{false, true}) try (var world = new InteractionWorldTestHarness()) {
            world.enableCraftingTransactions(); world.position(new Vec3(8.5, 1, 8.5)); world.h.minecraft.screen = null;
            world.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            world.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 40));
            DiscardSitePlanTest.corridor(world, blocked);
            var nativeDrops = world.h.simulateItemDrops(); var removed = new ArrayList<BlockPos>();
            world.mode.breaking = cell -> {
                check(cell.getX() != 8, "discard excavation never cuts the original passage");
                if (!world.level.getBlockState(cell).isAir()) { removed.add(cell); world.set(cell, Blocks.AIR.defaultBlockState()); }
            };
            var record = new DropItemsTaskRecord("side-pocket", 2000, Items.COBBLESTONE, 40, "cobblestone");
            var task = new DropCompanionTask(world.player, record); task.start(world.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 450 && !state.isTerminal(); tick++) {
                world.nextTick(); MenuVisibility.rendered(world.h.minecraft.screen);
                state = task.tick(world.player); DiscardFireTest.align(world);
                if ((boolean) ActorControlTestHarness.field(DropCompanionTask.class, "siteReady").get(task)
                        && nativeDrops.isEmpty() && world.h.minecraft.screen == null) world.h.minecraft.screen = world.h.inventoryScreen();
                if (!nativeDrops.isEmpty()) check(removed.size() == 8, "all eight side-pocket blocks are observed removed before any toss");
            }
            var result = task.result(state).toJson();
            if (blocked) check(state == TaskState.FAILED && nativeDrops.isEmpty()
                    && PlayerInv.count(world.inventory, Items.COBBLESTONE) == 40, "unbreakable walls retain all items without trapping navigation");
            else check(state == TaskState.SUCCESS && removed.size() == 8 && nativeDrops.size() == 1
                    && nativeDrops.getFirst().getCount() == 40, "native side preparation precedes one complete batch: " + result);
        } finally { DiscardedItems.observe(null); }
        System.out.println("DiscardPocketTaskTest: native excavation before tossing and blocked-site retention passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
