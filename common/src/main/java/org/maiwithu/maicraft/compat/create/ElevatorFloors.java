// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 电梯楼层的判断，纯函数：角色在哪一层、要去哪一层（楼层名、top、bottom、up、down、按目的地高度）、
 * 楼层表怎么说给 LLM 听、哪些按钮按下去能把电梯叫到某层。报价与坐电梯的那一段共用。
 */
final class ElevatorFloors {

    /** 离轿厢这么远（水平）以内才算在这台电梯的这一层。 */
    static final int CALL_RADIUS = 32;

    private ElevatorFloors() {}

    /** 角色此刻在这台电梯的哪一层：在轿厢里看轿厢停在哪层（在动就说不清）；在外面看脚下高度对得上哪层、离轿厢不远。 */
    static Optional<CreateElevators.Floor> floorOf(CreateElevators.Elevator elevator, BlockPos feet) {
        if (elevator.carriesPlayer()) {
            if (!elevator.arrived()) return Optional.empty();
            return elevator.floors().stream().filter(floor -> floor.contactY() == elevator.currentContactY()).findFirst();
        }
        double dx = elevator.cabin().getCenter().x - (feet.getX() + 0.5);
        double dz = elevator.cabin().getCenter().z - (feet.getZ() + 0.5);
        if (Math.sqrt(dx * dx + dz * dz) > CALL_RADIUS) return Optional.empty();
        return elevator.floors().stream().filter(floor -> Math.abs(floor.landingY() - feet.getY()) <= 1)
                .min(Comparator.comparingInt(floor -> Math.abs(floor.landingY() - feet.getY())));
    }

    /** 要去的那一层，或楼层说错了的说明。 */
    record Target(CreateElevators.Floor floor, String problem) {}

    /** 去哪层：给了 floor 按它（楼层名、top、bottom、up、down），没给按目的地高度挑最近的一层。 */
    static Target target(CreateElevators.Elevator elevator, CreateElevators.Floor here, String option, BlockPos to) {
        List<CreateElevators.Floor> floors = elevator.floors();
        int index = floors.indexOf(here);
        if (option == null) {
            if (to == null) return new Target(null, "没说去哪一层：给 floor（" + table(floors) + "）");
            return new Target(floors.stream().min(Comparator.comparingInt(floor -> Math.abs(floor.landingY() - to.getY())))
                    .orElseThrow(), null);
        }
        String wanted = option.trim();
        switch (wanted.toLowerCase(Locale.ROOT)) {
            case "top" -> {
                return new Target(floors.getLast(), null);
            }
            case "bottom" -> {
                return new Target(floors.getFirst(), null);
            }
            case "up" -> {
                return index + 1 < floors.size() ? new Target(floors.get(index + 1), null)
                        : new Target(null, "已经在顶层（" + describe(here) + "），没有上一层；这台电梯有：" + table(floors));
            }
            case "down" -> {
                return index > 0 ? new Target(floors.get(index - 1), null)
                        : new Target(null, "已经在底层（" + describe(here) + "），没有下一层；这台电梯有：" + table(floors));
            }
            default -> {
                for (CreateElevators.Floor floor : floors) {
                    if (floor.name().equalsIgnoreCase(wanted) || floor.description().equalsIgnoreCase(wanted)) {
                        return new Target(floor, null);
                    }
                }
                return new Target(null, "这台电梯没有叫「" + wanted + "」的一层；有：" + table(floors)
                        + "，也可以写 top / bottom / up / down");
            }
        }
    }

    /** 楼层表的一句话：1F（触点 y=64）、2F（触点 y=70）……从低到高。 */
    static String table(List<CreateElevators.Floor> floors) {
        return floors.stream().map(floor -> describe(floor) + "（触点 y=" + floor.contactY() + "）")
                .collect(Collectors.joining("、"));
    }

    static String describe(CreateElevators.Floor floor) {
        if (!floor.name().isBlank()) return floor.name();
        if (!floor.description().isBlank()) return floor.description();
        return "y=" + floor.contactY() + " 那层";
    }

    /**
     * 按下去能让触点通电（把电梯叫到这一层）的按钮：紧挨触点的，或贴在紧挨触点的方块上的
     * （按钮让贴着的方块强充能，那个方块再给触点通电）。
     */
    static List<BlockPos> callButtons(BlockGetter level, BlockPos contact) {
        List<BlockPos> buttons = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos neighbor = contact.relative(side);
            BlockState next = level.getBlockState(neighbor);
            if (next.getBlock() instanceof ButtonBlock && !buttons.contains(neighbor)) buttons.add(neighbor);
            // 隔一格的按钮只有贴在能导红石的实心方块上才算：按钮让它强充能，它再给触点通电。
            if (!next.isRedstoneConductor(level, neighbor)) continue;
            for (Direction around : Direction.values()) {
                BlockPos candidate = neighbor.relative(around);
                if (candidate.equals(contact) || buttons.contains(candidate)) continue;
                BlockState state = level.getBlockState(candidate);
                if (state.getBlock() instanceof ButtonBlock && attachedTo(state, candidate).equals(neighbor)) {
                    buttons.add(candidate);
                }
            }
        }
        return buttons;
    }

    // 按钮贴着的那一格：贴天花板的在上面一格，贴地的在下面一格，贴墙的在它朝向的反方向。
    private static BlockPos attachedTo(BlockState button, BlockPos at) {
        return switch (button.getValue(BlockStateProperties.ATTACH_FACE)) {
            case CEILING -> at.above();
            case FLOOR -> at.below();
            case WALL -> at.relative(button.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        };
    }

}
