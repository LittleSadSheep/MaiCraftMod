// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/**
 * Mekanism 侧面配置的设置项，纯函数：键写成 side.方向.介质（例如 side.north.item），值是那一面现在的模式
 * （none、input、output、input_output、energy……）。物品、流体、能量三种能改；化学品与热只读——
 * 读得出来，写进运行状态，暂时不接不改。改一下就是手持配置器、调到那种介质的模式、潜行右键那一面，模式循环往后挪一格。
 */
final class SideSettings {

    /** 能改的介质：物品、流体、能量（化学品只读不接）。 */
    static final List<String> CHANGEABLE = List.of("item", "fluid", "energy");

    /** 键拆出来的方向与介质。 */
    record Key(Direction side, String medium) {
    }

    private SideSettings() {
    }

    /** 这台机器能改的每一面每一种介质，一项一个设置。 */
    static List<MachineSetting> of(MekanismReads.SideConfig config) {
        List<MachineSetting> settings = new ArrayList<>();
        for (String medium : CHANGEABLE) {
            Map<Direction, String> sides = config.sides().get(medium);
            if (sides == null) continue;
            List<String> choices = config.choices().getOrDefault(medium, List.of());
            for (Direction side : Direction.values()) {
                settings.add(new MachineSetting(key(side, medium), sides.getOrDefault(side, "none"), choices,
                        side.getName() + " 面的" + mediumName(medium) + "：手持配置器调到这种介质，潜行右键这一面往后换一种"));
            }
        }
        return settings;
    }

    static String key(Direction side, String medium) {
        return "side." + side.getName() + "." + medium;
    }

    /** 拆键：side.方向.介质；写法不对、介质不能改给空。 */
    static Optional<Key> parse(String key) {
        String[] parts = key.split("\\.");
        if (parts.length != 3 || !parts[0].equals("side") || !CHANGEABLE.contains(parts[2])) return Optional.empty();
        Direction side = Direction.byName(parts[1]);
        return side == null ? Optional.empty() : Optional.of(new Key(side, parts[2]));
    }

    /** 改这种介质时配置器要调到的模式。 */
    static String configuratorMode(String medium) {
        return switch (medium) {
            case "item" -> "configurate_items";
            case "fluid" -> "configurate_fluids";
            case "energy" -> "configurate_energy";
            case "chemical" -> "configurate_chemicals";
            default -> "configurate_heat";
        };
    }

    /** 侧面配置换成进出口：每一面每种介质不是 none 的都算一个，模式名写进说明。 */
    static List<ExchangePoint> points(MekanismReads.SideConfig config, BlockPos at) {
        List<ExchangePoint> points = new ArrayList<>();
        config.sides().forEach((medium, sides) -> sides.forEach((side, mode) -> {
            if (mode.equals("none")) return;
            points.add(new ExchangePoint(at, side, mediumOf(medium), flowOf(mode),
                    "侧面配置：" + mediumName(medium) + " " + mode));
        }));
        return points;
    }

    static ExchangePoint.Medium mediumOf(String medium) {
        return switch (medium) {
            case "item" -> ExchangePoint.Medium.ITEM;
            case "fluid" -> ExchangePoint.Medium.FLUID;
            case "energy", "heat" -> ExchangePoint.Medium.ENERGY;
            default -> ExchangePoint.Medium.CHEMICAL;
        };
    }

    // 输出类的模式往外送，输入类往里收，进出都行的两样都有。
    static ExchangePoint.Flow flowOf(String mode) {
        if (Set.of("output", "output_1", "output_2").contains(mode)) return ExchangePoint.Flow.OUTPUT;
        if (Set.of("input", "input_1", "input_2", "energy", "extra").contains(mode)) return ExchangePoint.Flow.INPUT;
        return ExchangePoint.Flow.BOTH;
    }

    static String mediumName(String medium) {
        return switch (medium) {
            case "item" -> "物品";
            case "fluid" -> "流体";
            case "energy" -> "能量";
            case "chemical" -> "化学品";
            default -> "热";
        };
    }
}
