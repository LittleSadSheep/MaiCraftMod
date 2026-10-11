// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 机械手的两项设置，纯函数：deployer.held 手里拿什么（物品 ID，空手写 empty），deployer.mode 使用还是击打（use / punch）。
 *
 * <p>改手里拿的：手持那样东西（或空着手）右键机械手前端那一截，Create 把两边手里的东西对调，手上那一叠全给它；
 * 改模式：手持扳手右键前端那一截。点在后面四分之三上，交换不认、扳手是转方向，所以两样都只点前端。
 */
final class DeployerSettings {

    static final String HELD = "deployer.held";
    static final String MODE = "deployer.mode";
    static final String EMPTY = "empty";
    static final String WRENCH = "create:wrench";
    /**
     * 只认点在前端这一截的右键：从背面量起超过四分之三格。框从 0.8 格起，留一点余量。
     * 类别：游戏事实（DeployerBlock 的 useItemOn 与 onWrenched）。
     */
    private static final double FRONT_FROM = 0.8;

    private DeployerSettings() {
    }

    /** 两项设置的现值；读不到机械手时没有设置项。 */
    static List<MachineSetting> of(Optional<CreateStations.DeployerReading> reading) {
        if (reading.isEmpty()) return List.of();
        List<MachineSetting> settings = new ArrayList<>();
        settings.add(new MachineSetting(HELD, heldValue(reading.get().held()), List.of(),
                "手里拿什么：写物品 ID，角色手持那样东西右键机械手前端，整叠交给它；写 empty 把它手里的拿回来"));
        settings.add(new MachineSetting(MODE, reading.get().mode().isEmpty() ? "unknown" : reading.get().mode(),
                List.of("use", "punch"), "use 用手里的东西右键前方，punch 左键击打；改它要拿扳手右键机械手前端"));
        return settings;
    }

    /** 手里那件的读数：空手是 empty，不然是物品 ID。 */
    static String heldValue(ItemStack held) {
        return held.isEmpty() ? EMPTY : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
    }

    /** 机械手读数并进机器状态：手里拿着什么、几件，什么模式。 */
    static MachineState withReading(MachineState state, Optional<CreateStations.DeployerReading> reading) {
        if (reading.isEmpty()) return state;
        Map<String, Object> readings = new LinkedHashMap<>(state.readings());
        ItemStack held = reading.get().held();
        readings.put("held", held.isEmpty() ? EMPTY : heldValue(held) + " ×" + held.getCount());
        if (!reading.get().mode().isEmpty()) readings.put("mode", reading.get().mode());
        return new MachineState(state.activity(), state.speed(), state.progress(), state.shown(), readings, state.note());
    }

    /** 机械手前端那一截（世界坐标）：朝向那一面往里 0.2 格厚的一片，点在这里交换东西、扳手换模式。 */
    static AABB front(BlockPos at, Direction facing) {
        AABB cell = new AABB(at);
        double depth = 1.0 - FRONT_FROM;
        return switch (facing) {
            case UP -> cell.setMinY(cell.maxY - depth);
            case DOWN -> cell.setMaxY(cell.minY + depth);
            case EAST -> cell.setMinX(cell.maxX - depth);
            case WEST -> cell.setMaxX(cell.minX + depth);
            case SOUTH -> cell.setMinZ(cell.maxZ - depth);
            case NORTH -> cell.setMaxZ(cell.minZ + depth);
        };
    }
}
