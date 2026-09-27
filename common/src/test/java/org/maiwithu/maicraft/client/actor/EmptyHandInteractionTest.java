// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 战斗遗留的斧头不能被放上工件台；收到取货回执之前，也不能为了空手而移走刚进入主手的产物。 */
public final class EmptyHandInteractionTest {
    private static final BlockPos AT = new BlockPos(8, 1, 5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(8.5, 1, 8.5)); h.set(AT, Blocks.STONE.defaultBlockState());
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_AXE)); h.inventory.selected = 0;
            var r = new InteractAtTaskRecord("empty-hand-block", 1000, MouseButton.RIGHT, AT, 0, null).withEmptyHand();
            var task = new InteractAtCompanionTask(h.player, r);
            var act = InteractAtCompanionTask.class.getDeclaredMethod("act"); act.setAccessible(true);
            h.mode.beforeBlockUse = () -> check(h.player.getMainHandItem().isEmpty(), "the actual native click must use an empty main hand");
            for (int tick = 0; tick < 100 && h.blockUses() == 0; tick++) { act.invoke(task); next(h); }
            check(h.blockUses() == 1 && h.inventory.getItem(0).is(Items.DIAMOND_AXE), "the axe remains carried while the target receives one native use");
            // 注入服务器返还的工件；任务必须先确认刚才的点击，不能切走或停车这份产物来制造新的空手。
            h.inventory.setItem(h.inventory.selected, new ItemStack(Items.IRON_INGOT, 12));
            TaskState status = TaskState.RUNNING;
            for (int tick = 0; tick < 30 && status == TaskState.RUNNING; tick++) { next(h); status = (TaskState) act.invoke(task); }
            check(status == TaskState.SUCCESS && h.blockUses() == 1 && h.itemUses() == 0
                    && h.player.getMainHandItem().is(Items.IRON_INGOT) && h.player.getMainHandItem().getCount() == 12,
                    "collecting output settles the pending use without storing the output or retrying an item use");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_AXE)); h.inventory.selected = 0;
            var task = new InteractAtCompanionTask(h.player,
                    new InteractAtTaskRecord("resumed-hand", 1000, MouseButton.RIGHT, AT, 0, null).withEmptyHand());
            var prepare = InteractAtCompanionTask.class.getDeclaredMethod("prepareEmptyHand"); prepare.setAccessible(true);
            for (int pass = 0; pass < 2; pass++) {
                // 第二轮模拟自动反击重新选中斧头；恢复原任务仍必须重新选择真实空格。
                h.inventory.selected = 0; Object status = TaskState.RUNNING;
                for (int tick = 0; tick < 10 && status != null; tick++) { status = prepare.invoke(task); h.nextTick(); }
                check(status == null && h.player.getMainHandItem().isEmpty() && h.inventory.getItem(0).is(Items.DIAMOND_AXE),
                        "an intervening combat tool selection cannot survive empty-hand preparation");
            }
            check(h.blockUses() == 0 && h.itemUses() == 0, "hand preparation alone never uses or discards the tool");
        }
        // 显式材料请求与空手互斥，不能因为通用默认值把投料动作改成取料。
        try { new InteractAtTaskRecord("named-item", 1000, MouseButton.RIGHT, AT, 0, Items.IRON_INGOT).withEmptyHand();
            throw new AssertionError("named material cannot become an empty-hand request"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("EmptyHandInteractionTest: passed");
    }
    private static void next(InteractionWorldTestHarness h) throws Exception {
        // 无头夹具仍推进正常转头，不能直接把镜头瞬移到目标来绕过实际瞄准。
        ActorControlTestHarness.field(DefaultBodyControlPort.class, "lastLookUpdateNanos").setLong(h.h.body, System.nanoTime() - 50_000_000L);
        h.h.body.endTick(h.h.context); h.nextTick();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
