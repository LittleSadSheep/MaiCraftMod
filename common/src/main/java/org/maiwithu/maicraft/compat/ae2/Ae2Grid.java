// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 读 ME 网络设备的模组读写接缝（客户端）：一格是不是 AE2 的网格设备、线缆往哪几面连着、
 * 各面装着什么部件、亮没亮（有电、有频道）、控制器是什么状态。这些都是 AE2 同步给客户端画出来的，看得见。
 * 网络本身的汇总（频道用量、能量、存储）客户端读不到，要经服务端问。
 * 只用 Minecraft 与 Java 的类型；直接看 AE2 方块实体的读写端在 NeoForge 模块里，测试里用替身。
 */
public interface Ae2Grid {

    /** 这一格的设备读数；不是 AE2 的网格设备、没加载给空。 */
    Optional<GridCell> cell(ClientLevel level, BlockPos at);

    /** 这件物品是不是能装到线缆格某一面上的 AE2 部件（终端、总线、面板……）。 */
    boolean partItem(String itemId);

    /** 这件物品是不是 AE2 的线缆（玻璃、包层、智能、致密，各种颜色）。 */
    boolean cableItem(String itemId);

    /**
     * 一格 ME 设备。
     *
     * @param cableBus        是不是线缆格（中间一根线缆、六面可以装部件）
     * @param cableItem       线缆格中间那根线缆的物品 ID；没有线缆（只有部件）或不是线缆格为空
     * @param links           线缆往哪几面连着（AE2 画出来的连接）；不是线缆格为空
     * @param parts           线缆格各面装着的部件
     * @param powered         有没有电：设备、部件亮着；读不出为空
     * @param active          有没有频道（有电又分到了频道，才真的在干活）；读不出为空
     * @param controllerState 控制器方块的状态：offline 没电、online 正常、conflicted 摆法不对；不是控制器为空
     */
    record GridCell(boolean cableBus, Optional<String> cableItem, Set<Direction> links, Map<Direction, PartView> parts,
                    Optional<Boolean> powered, Optional<Boolean> active, Optional<String> controllerState) {
        public GridCell {
            cableItem = Objects.requireNonNull(cableItem, "cableItem");
            links = Set.copyOf(links);
            parts = Map.copyOf(parts);
            powered = Objects.requireNonNull(powered, "powered");
            active = Objects.requireNonNull(active, "active");
            controllerState = Objects.requireNonNull(controllerState, "controllerState");
        }
    }

    /** 装在线缆格某一面的部件：什么、有没有电、有没有频道。 */
    record PartView(String itemId, boolean powered, boolean active) {
        public PartView {
            Objects.requireNonNull(itemId, "itemId");
        }
    }
}
