// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.scan;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchCompanionTask;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 原生碰撞射线覆盖关门屋内目标，以及近处隐藏方块不能遮蔽后续可见候选的分页回归。 */
public final class ObservationVisibilityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        closedDoorBlocksContainersAndEntities();
        hiddenBatchDoesNotHideVisibleBlock();
    }

    private static void closedDoorBlocksContainersAndEntities() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos chest = new BlockPos(7, 1, 3), door = new BlockPos(4, 1, 3);
            h.set(chest, Blocks.CHEST.defaultBlockState());
            // 原生掉落物构造需要世界随机源，夹具补齐该事实后使用真实实体包围盒。
            var random = Level.class.getDeclaredField("random"); random.setAccessible(true); random.set(h.level, RandomSource.create(1));
            var item = new ItemEntity(h.level, 6.5, 1.5, 3.5, new ItemStack(Items.IRON_INGOT));
            // 先建整面墙、留两格门洞；关门不可见，原生门轴转开后才可调查屋内箱子和掉落物。
            for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++)
                h.set(new BlockPos(4, y, z), Blocks.STONE.defaultBlockState());
            var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
            h.set(door, state); h.set(door.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            check(!ObservationVisibility.block(h.player, chest), "closed door conceals chest");
            check(!ObservationVisibility.entity(h.player, item), "closed door conceals item identity");
            state = state.setValue(DoorBlock.OPEN, true);
            h.set(door, state); h.set(door.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            check(ObservationVisibility.block(h.player, chest), "open doorway exposes chest");
            check(ObservationVisibility.entity(h.player, item), "open doorway exposes item");
            check(!ObservationVisibility.block(h.player, new BlockPos(20, 1, 3)), "unloaded target is unknown");
            check(h.blockUses() == 0 && h.itemUses() == 0, "observation performs no native use");
        }
    }

    private static void hiddenBatchDoesNotHideVisibleBlock() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 六十四台近处石切机全在墙后；索引第一批排除后仍须继续找左侧露出的那一台。
            for (int z = 0; z < 10; z++) for (int y = 1; y < 5; y++)
                h.set(new BlockPos(2, y, z), Blocks.STONE.defaultBlockState());
            for (int x = 3; x < 11; x++) for (int z = 0; z < 8; z++)
                h.set(new BlockPos(x, 1, z), Blocks.STONECUTTER.defaultBlockState());
            h.set(new BlockPos(0, 1, 15), Blocks.STONECUTTER.defaultBlockState());
            var task = new SemanticBlockSearchCompanionTask(h.player,
                    new SemanticBlockSearchTaskRecord("visible-after-hidden", 1000, List.of(Blocks.STONECUTTER), 1, 16));
            task.start(h.player); TaskState terminal = TaskState.RUNNING;
            for (int tick = 0; tick < 128 && terminal == TaskState.RUNNING; tick++) {
                h.nextTick(); terminal = task.tick(h.player);
            }
            check(terminal == TaskState.SUCCESS, "visible target after a hidden result window is found");
            var result = task.result(terminal);
            check(result.data().get("observed_acceptable_count").equals(1), "wall-hidden machines are never reported");
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
