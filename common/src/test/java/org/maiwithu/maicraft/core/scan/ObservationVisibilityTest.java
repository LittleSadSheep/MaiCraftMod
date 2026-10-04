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
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchCompanionTask;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 观察穿过透明材质和真实空隙，但不穿实心墙；发现证据与原生交互命中分别验证。 */
public final class ObservationVisibilityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        transparentScreensExposeTargets();
        partialShapesKeepTheirSolidParts();
        exposedCornersAreVisible();
        closedDoorBlocksContainersAndEntities();
        hiddenBatchDoesNotHideVisibleBlock();
    }

    private static void transparentScreensExposeTargets() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos log = new BlockPos(7, 2, 3);
            h.set(log, Blocks.JUNGLE_LOG.defaultBlockState());
            var random = Level.class.getDeclaredField("random"); random.setAccessible(true); random.set(h.level, RandomSource.create(1));
            var item = new ItemEntity(h.level, 6.5, 2.5, 3.5, new ItemStack(Items.IRON_INGOT));
            // 逐种搭起完整屏障，保证可见来自材质透光，而不是射线恰好绕过玻璃或树叶的边缘。
            for (BlockState screen : List.of(Blocks.GLASS.defaultBlockState(), Blocks.TINTED_GLASS.defaultBlockState(),
                    Blocks.BLUE_STAINED_GLASS.defaultBlockState(),
                    Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true),
                    Blocks.JUNGLE_LEAVES.defaultBlockState(), Blocks.ICE.defaultBlockState(),
                    Blocks.FROSTED_ICE.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(),
                    Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.COPPER_GRATE.defaultBlockState(),
                    Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true))) {
                screen(h, 4, screen);
                check(ObservationVisibility.block(h.player, log), "transparent screen exposes log: " + screen);
                check(ObservationVisibility.entity(h.player, item), "transparent screen exposes drop: " + screen);
                // 透视观察不意味着能隔物挖掘；原生准星仍必须先命中玻璃、树叶或藤蔓本身。
                var hit = h.level.clip(new ClipContext(h.player.getEyePosition(), Vec3.atCenterOf(log),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, h.player));
                check(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().getX() == 4,
                        "native interaction still hits the screen: " + screen);
            }
            screen(h, 3, Blocks.GLASS.defaultBlockState());
            screen(h, 4, Blocks.OAK_LEAVES.defaultBlockState());
            check(ObservationVisibility.block(h.player, log), "multiple transparent layers allow observation");
            // 透明层之后还有实体墙时必须继续检查后面的遮挡，不能遇到第一块玻璃就直接宣布可见。
            screen(h, 5, Blocks.STONE.defaultBlockState());
            check(!ObservationVisibility.block(h.player, log), "stone behind glass still conceals the log");
            check(!ObservationVisibility.entity(h.player, item), "stone behind leaves still conceals the drop");
            screen(h, 5, Blocks.AIR.defaultBlockState());
            check(!ObservationVisibility.point(h.player, new Vec3(20.5, 2.5, 3.5)), "glass does not expose unloaded terrain");
            check(h.blockUses() == 0 && h.itemUses() == 0, "visibility never interacts with the screen");
        }
    }

    private static void partialShapesKeepTheirSolidParts() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos obstacle = new BlockPos(4, 2, 3);
            // 下台阶只允许视线穿过上半格；换成上台阶后反过来，双台阶则封住整格。
            h.set(obstacle, Blocks.STONE_SLAB.defaultBlockState());
            check(horizontalSight(h, 2.75), "lower slab leaves an upper gap");
            check(!horizontalSight(h, 2.25), "lower slab keeps its solid half opaque");
            h.set(obstacle, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            check(horizontalSight(h, 2.25), "upper slab leaves a lower gap");
            check(!horizontalSight(h, 2.75), "upper slab keeps its solid half opaque");
            h.set(obstacle, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
            check(!horizontalSight(h, 2.25) && !horizontalSight(h, 2.75), "double slab conceals both halves");
            // 从楼梯侧面观察时，低踏步上方有真实空隙，高踏步仍遮挡相同高度的射线。
            h.set(obstacle, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
            h.position(new Vec3(4.25, 2.75 - h.player.getEyeHeight(), .5));
            check(ObservationVisibility.point(h.player, new Vec3(4.25, 2.75, 7.5)), "stair side exposes its missing quarter");
            h.position(new Vec3(4.75, 2.75 - h.player.getEyeHeight(), .5));
            check(!ObservationVisibility.point(h.player, new Vec3(4.75, 2.75, 7.5)), "stair solid riser remains opaque");
        }
    }

    private static void exposedCornersAreVisible() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos log = new BlockPos(7, 2, 3);
            h.position(new Vec3(.5, 2.5 - h.player.getEyeHeight(), 3.5));
            h.set(log, Blocks.JUNGLE_LOG.defaultBlockState());
            BlockPos fence = new BlockPos(4, 2, 3);
            h.set(fence, Blocks.OAK_FENCE.defaultBlockState()
                    .setValue(FenceBlock.NORTH, true).setValue(FenceBlock.SOUTH, true));
            // 柱子挡住竖直中心线、横杆挡住水平中心线，但原木下方的两个边角确实露出。
            Vec3 center = Vec3.atCenterOf(log);
            for (Direction face : Direction.values()) {
                Vec3 point = center.add(face.getStepX() * .499, face.getStepY() * .499, face.getStepZ() * .499);
                var hit = h.level.clip(new ClipContext(h.player.getEyePosition(), point,
                        ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, h.player));
                check(hit.getBlockPos().equals(fence), "face center is hidden behind fence: " + face);
            }
            check(ObservationVisibility.block(h.player, log), "exposed corners still reveal the log");
            h.set(new BlockPos(4, 2, 3), Blocks.STONE.defaultBlockState());
            check(!ObservationVisibility.block(h.player, log), "a complete block conceals all target corners");
        }
    }

    // 固定视线高度比较台阶的两半，避免目标边角采样绕开正在验证的实体部分。
    private static boolean horizontalSight(InteractionWorldTestHarness h, double y) throws Exception {
        h.position(new Vec3(.5, y - h.player.getEyeHeight(), 3.5));
        return ObservationVisibility.point(h.player, new Vec3(7.5, y, 3.5));
    }

    private static void screen(InteractionWorldTestHarness h, int x, BlockState state) {
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.set(new BlockPos(x, y, z), state);
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
