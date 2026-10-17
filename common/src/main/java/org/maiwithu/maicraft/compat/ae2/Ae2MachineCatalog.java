// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;

/**
 * 应用能源2里认得的设备：每一种的编号、中文名、在 ME 网里干什么、由哪几种方块组成、挂不挂在 ME 网上。
 * 编号就是主方块的注册 ID。压印器、充能器这类不接 ME 网的单机也列进来，看机器时认得出是什么。
 */
final class Ae2MachineCatalog {

    /**
     * 一种设备。
     *
     * @param id     编号，主方块的注册 ID
     * @param name   给 LLM 看的中文名
     * @param role   在机器里干什么
     * @param onGrid 挂不挂在 ME 网上（能接线缆、占频道、读得到网络汇总）
     * @param blocks 由哪几种方块组成（注册 ID）
     */
    record Kind(String id, String name, MachineRole role, boolean onGrid, List<String> blocks) {
        Kind {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
            blocks = List.copyOf(blocks);
        }
    }

    /** 线缆格（中间线缆、六面装部件）的方块 ID：装部件、接线缆都认它。 */
    static final String CABLE_BUS = "ae2:cable_bus";

    private Ae2MachineCatalog() {
    }

    /** 认得的全部设备。 */
    static List<Kind> kinds() {
        return List.of(
                kind("controller", "ME 控制器", MachineRole.NETWORK_CORE, true),
                kind("cable_bus", "ME 线缆与部件", MachineRole.CABLE, true),
                kind("drive", "ME 驱动器", MachineRole.STORAGE, true),
                kind("chest", "ME 箱子", MachineRole.STORAGE, true),
                kind("interface", "ME 接口", MachineRole.LOGISTICS, true),
                kind("pattern_provider", "ME 样板供应器", MachineRole.LOGISTICS, true),
                kind("io_port", "ME IO 端口", MachineRole.LOGISTICS, true),
                kind("energy_acceptor", "能源接收器", MachineRole.POWER_SOURCE, true),
                kind("crystal_resonance_generator", "水晶共振发生器", MachineRole.POWER_SOURCE, true),
                kind("vibration_chamber", "振动室", MachineRole.POWER_SOURCE, true),
                kind("energy_cell", "能源元件", MachineRole.STORAGE, true,
                        "energy_cell", "dense_energy_cell", "creative_energy_cell"),
                kind("crafting_unit", "合成 CPU", MachineRole.PROCESSING, true,
                        "crafting_unit", "crafting_accelerator", "1k_crafting_storage", "4k_crafting_storage",
                        "16k_crafting_storage", "64k_crafting_storage", "256k_crafting_storage", "crafting_monitor"),
                kind("molecular_assembler", "分子组装室", MachineRole.PROCESSING, true),
                kind("wireless_access_point", "ME 无线访问点", MachineRole.CABLE, true),
                kind("quantum_ring", "ME 量子环", MachineRole.CABLE, true, "quantum_ring", "quantum_link"),
                kind("inscriber", "压印器", MachineRole.PROCESSING, false),
                kind("charger", "充能器", MachineRole.PROCESSING, false),
                kind("condenser", "物质聚合器", MachineRole.PROCESSING, false));
    }

    private static Kind kind(String id, String name, MachineRole role, boolean onGrid, String... blocks) {
        List<String> ids = (blocks.length == 0 ? List.of(id) : List.of(blocks)).stream()
                .map(block -> "ae2:" + block).toList();
        return new Kind("ae2:" + id, name, role, onGrid, ids);
    }
}
