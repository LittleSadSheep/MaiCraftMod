// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 机器查看的结果细节：看到哪些机器（每台的组成、进出口、运行状态、挂着的网络）、
 * 哪些格看着像机器但没有机器类型认领、有几格没加载。
 *
 * <p>机器的编号 m1、m2 只在这一次结果里有效：指某一台机器用它的组成格（观察编号或坐标），
 * 建过档的用档案名；有格落在所看档案里的那台机器会带上档案名。
 *
 * @param machines     分好台的机器，编号从 1 起
 * @param unrecognized 有方块实体、看着像机器、但没有机器类型认领的格，按方块 ID 归并
 * @param unloadedCells 范围内没加载的格数：这些格不知道是什么，不按空气算
 * @param scanComplete  范围扫完了没有；没扫完时下面列的只是已经看到的部分
 * @param archive       看的是哪份档案；没指档案为 null
 * @param check         和档案的整机比对：每种结论几格、没对上的每一格、多出来的格、安装段与部件；没指档案为 null。
 *                      结构一致不等于能转，转不转看 machines 里各台的 running
 * @param note          给 LLM 的一句说明，没有为空字符串
 */
record MachineDetails(List<MachineView> machines, List<UnrecognizedBlock> unrecognized,
                      int unloadedCells, boolean scanComplete, String archive, ArchiveCheck.Result check,
                      String note) implements ResultDetails {

    MachineDetails {
        machines = List.copyOf(machines);
        unrecognized = List.copyOf(unrecognized);
        note = note == null ? "" : note;
    }

    /** 一台机器：编号（m1、m2……）、组成、进出口、运行状态、挂着的网络。 */
    record MachineView(String id, String name, List<RoleGroup> types, List<ExchangePoint> ports,
                       List<TypedState> running, List<NetworkView> networks) {

        MachineView {
            types = List.copyOf(types);
            ports = List.copyOf(ports);
            running = List.copyOf(running);
            networks = List.copyOf(networks);
        }
    }

    /** 按角色归并的组成：这一台里干什么的一格有哪些（方块 ID 加坐标）。 */
    record RoleGroup(String role, List<String> blocks) {

        RoleGroup {
            blocks = List.copyOf(blocks);
        }
    }

    /** 一格机器方块的读数：哪种机器、哪一格、现在怎样。 */
    record TypedState(String typeId, BlockPos at, MachineState state) {
    }

    /** 机器挂着的一张网：种类、网络编号、那张网现在的读数。 */
    record NetworkView(NetworkKind kind, String networkId, NetworkSummary summary) {
    }

    /** 没有机器类型认领的格，按方块 ID 归并：共几格、前几个位置、缺哪个联动的一句说明。 */
    record UnrecognizedBlock(String blockId, int cells, List<BlockPos> samples, String note) {

        UnrecognizedBlock {
            samples = List.copyOf(samples);
        }
    }
}
