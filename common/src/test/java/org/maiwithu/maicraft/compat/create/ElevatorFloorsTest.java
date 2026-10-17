// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 电梯楼层：在哪层、去哪层（楼层名、top、bottom、up、down、按高度）、楼层说错了、呼梯按钮。 */
class ElevatorFloorsTest {

    private static final CreateElevators.Floor F1 = new CreateElevators.Floor(64, 64, "1F", "大厅");
    private static final CreateElevators.Floor F2 = new CreateElevators.Floor(72, 72, "2F", "");
    private static final CreateElevators.Floor F3 = new CreateElevators.Floor(80, 80, "3F", "屋顶");
    static final AABB CABIN = new AABB(10, 63, 10, 13, 67, 13);

    @BeforeAll
    static void 引导方块注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static CreateElevators.Elevator elevator(boolean carries, boolean arrived, int current) {
        return new CreateElevators.Elevator(7, "create:cabin", List.of(F1, F2, F3), current, current, arrived, CABIN,
                List.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 11), new BlockPos(12, 64, 12)),
                List.of(new BlockPos(12, 65, 10)), 14, 11, carries);
    }

    @Test
    void 站在外面按脚下高度认在哪层_离得太远不算() {
        CreateElevators.Elevator cabin = elevator(false, true, 80);
        assertEquals(Optional.of(F2), ElevatorFloors.floorOf(cabin, new BlockPos(15, 72, 11)));
        assertEquals(Optional.empty(), ElevatorFloors.floorOf(cabin, new BlockPos(60, 72, 11)));
    }

    @Test
    void 在轿厢里看轿厢停在哪层_在动就说不清() {
        assertEquals(Optional.of(F3), ElevatorFloors.floorOf(elevator(true, true, 80), new BlockPos(11, 80, 11)));
        assertEquals(Optional.empty(), ElevatorFloors.floorOf(elevator(true, false, 76), new BlockPos(11, 76, 11)));
    }

    @Test
    void 去哪层_楼层名与上下顶底() {
        CreateElevators.Elevator cabin = elevator(false, true, 64);
        assertEquals(F3, ElevatorFloors.target(cabin, F1, "top", null).floor());
        assertEquals(F1, ElevatorFloors.target(cabin, F2, "bottom", null).floor());
        assertEquals(F2, ElevatorFloors.target(cabin, F1, "up", null).floor());
        assertEquals(F1, ElevatorFloors.target(cabin, F2, "down", null).floor());
        assertEquals(F3, ElevatorFloors.target(cabin, F1, "屋顶", null).floor());
        assertEquals(F2, ElevatorFloors.target(cabin, F1, "2f", null).floor());
    }

    @Test
    void 没给楼层按目的地高度挑最近的一层() {
        assertEquals(F3, ElevatorFloors.target(elevator(false, true, 64), F1, null, new BlockPos(0, 82, 0)).floor());
    }

    @Test
    void 楼层说错了带上实际的楼层表() {
        ElevatorFloors.Target wrong = ElevatorFloors.target(elevator(false, true, 64), F1, "5F", null);
        assertNull(wrong.floor());
        assertTrue(wrong.problem().contains("1F") && wrong.problem().contains("3F"), wrong.problem());
        assertTrue(ElevatorFloors.target(elevator(false, true, 80), F3, "up", null).problem().contains("顶层"));
    }

    @Test
    void 呼梯按钮_紧挨触点的与贴在紧挨触点方块上的都算() {
        Grid grid = new Grid();
        BlockPos contact = new BlockPos(14, 64, 11);
        grid.put(contact.east(), Blocks.STONE.defaultBlockState());
        // 贴在触点东边那块石头东面的按钮：按下去石头强充能，再给触点通电。
        grid.put(contact.east(2), button(AttachFace.WALL, Direction.EAST));
        // 紧挨触点上面的按钮（贴地按在触点顶上）。
        grid.put(contact.above(), button(AttachFace.FLOOR, Direction.NORTH));
        // 隔着空气的按钮不算。
        grid.put(contact.west(2), button(AttachFace.WALL, Direction.WEST));
        List<BlockPos> buttons = ElevatorFloors.callButtons(grid, contact);
        assertTrue(buttons.contains(contact.east(2)), buttons.toString());
        assertTrue(buttons.contains(contact.above()), buttons.toString());
        assertEquals(2, buttons.size(), buttons.toString());
    }

    private static BlockState button(AttachFace face, Direction facing) {
        return Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, face).setValue(ButtonBlock.FACING, facing);
    }

    /** 小片世界替身：按坐标记方块，没记的当空气。 */
    static final class Grid implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinBuildHeight() {
            return -64;
        }
    }
}
