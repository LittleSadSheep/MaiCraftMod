// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;

/**
 * 通用机械里认得的机器与管道：每一种的编号、中文名、在机器里干什么、由哪几种方块组成（四个等级的同一种算一种）。
 * 编号就是主方块的注册 ID，也是配方查看器里这道工序的工作站物品 ID。没列的、能配侧面的 Mekanism 机器
 * 由"其他 Mekanism 机器"兜住，至少看得出侧面配置。
 */
final class MekanismMachineCatalog {

    /** 一种机器或管道。 */
    record Kind(String id, String name, MachineRole role, List<String> blocks) {
        Kind {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
            blocks = List.copyOf(blocks);
        }
    }

    static final String MOD_ID = "mekanism";

    private MekanismMachineCatalog() {
    }

    /** 认得的全部机器与管道。 */
    static List<Kind> kinds() {
        return List.of(
                tiered("energy_cube", "能量立方", MachineRole.STORAGE, true),
                tiered("universal_cable", "通用线缆", MachineRole.CABLE, false),
                tiered("mechanical_pipe", "机械管道", MachineRole.CABLE, false),
                tiered("pressurized_tube", "加压管道", MachineRole.CABLE, false),
                withExtras(tiered("logistical_transporter", "物流传输管道", MachineRole.LOGISTICS, false),
                        "restrictive_transporter", "diversion_transporter"),
                single("metallurgic_infuser", "冶金灌注机", MachineRole.PROCESSING),
                single("enrichment_chamber", "富集仓", MachineRole.PROCESSING),
                single("crusher", "粉碎机", MachineRole.PROCESSING),
                single("osmium_compressor", "锇压缩机", MachineRole.PROCESSING),
                single("logistical_sorter", "物流分拣器", MachineRole.LOGISTICS));
    }

    private static Kind single(String id, String name, MachineRole role) {
        return new Kind(MOD_ID + ":" + id, name, role, List.of(MOD_ID + ":" + id));
    }

    // 基础、高级、精英、终极四个等级（能量立方还有创造）的同一种算一种，编号用基础那一级。
    private static Kind tiered(String id, String name, MachineRole role, boolean creative) {
        List<String> blocks = new ArrayList<>();
        for (String tier : List.of("basic", "advanced", "elite", "ultimate")) blocks.add(MOD_ID + ":" + tier + "_" + id);
        if (creative) blocks.add(MOD_ID + ":creative_" + id);
        return new Kind(MOD_ID + ":basic_" + id, name, role, blocks);
    }

    private static Kind withExtras(Kind kind, String... extras) {
        List<String> blocks = new ArrayList<>(kind.blocks());
        for (String extra : extras) blocks.add(MOD_ID + ":" + extra);
        return new Kind(kind.id(), kind.name(), kind.role(), blocks);
    }
}
