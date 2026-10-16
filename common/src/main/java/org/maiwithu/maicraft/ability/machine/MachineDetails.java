// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
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
 * @param site          读现场（operation=read_site）抄出来的机器蓝图；只看时为 null
 */
record MachineDetails(List<MachineView> machines, List<UnrecognizedBlock> unrecognized,
                      int unloadedCells, boolean scanComplete, String archive, ArchiveCheck.Result check,
                      String note, SiteRead site) implements ResultDetails {

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

    /**
     * 读现场抄出来的那一台：照着建一台就把 blueprint 原样交给 machine_build，改这一台就让 machine_build 指档案名。
     *
     * @param machine       抄的是这一眼里的哪一台（m#）；指档案读时是和档案有交集的那几台
     * @param archive       档案名：指档案读时是那份档案，不然是这次建的那份
     * @param archived      这次新建了档案；指档案读、名字被占、存不进时为 false，notes 写明
     * @param anchor        锚点：蓝图里 offset 的原点（包围盒最小角，指档案时是档案的锚点）
     * @param size          包围盒东西、上下、南北各几格
     * @param blueprint     machine_build 的 blueprint 正文，可以原样喂回去
     * @param cells         逐格几条（空气不写）
     * @param installations 安装段几段
     * @param parts         部件几个
     * @param unread        包围盒里没加载、读不到的格数：这些格没抄，不按空气算
     * @param complete      包围盒每一格都读到了、机器也没伸出这次看的范围
     * @param notes         抄不全、照着建会走样的地方，一句一条
     */
    record SiteRead(String machine, String archive, boolean archived, BlockPos anchor, List<Integer> size,
                    JsonObject blueprint, int cells, int installations, int parts, int unread, boolean complete,
                    List<String> notes) {

        SiteRead {
            size = List.copyOf(size);
            notes = List.copyOf(notes);
        }
    }

    /** 没有机器类型认领的格，按方块 ID 归并：共几格、前几个位置、缺哪个联动的一句说明。 */
    record UnrecognizedBlock(String blockId, int cells, List<BlockPos> samples, String note) {

        UnrecognizedBlock {
            samples = List.copyOf(samples);
        }
    }
}
