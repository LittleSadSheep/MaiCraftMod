// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * 原生安装段：只能用模组自己的方式成型的一组格，例如 Create 两端轴之间的一段传送带、两个链轮之间的链条输送机连接。
 * 施工引擎不放也不清这些格（计划格标成由机器放），由认领这种安装段的机器类型来装、来核对。
 * 机器蓝图里写的是相对锚点的位置，落地后换成这里的世界坐标。
 *
 * @param kind  安装段的种类，例如 create:belt、create:chain_conveyor；由机器类型认
 * @param cells 这一段占的格，世界坐标，按从一端到另一端的顺序
 * @param roles 每一格在这一段里是干什么的，和 cells 一一对应（机械臂的取放点写 take / deposit）；
 *              用不着的安装段为空表
 */
public record Installation(String kind, List<BlockPos> cells, List<String> roles) {

    public Installation {
        Objects.requireNonNull(kind, "kind");
        cells = cells.stream().map(BlockPos::immutable).toList();
        roles = List.copyOf(roles);
        if (cells.isEmpty()) throw new IllegalArgumentException("安装段至少要有一格：" + kind);
        if (!roles.isEmpty() && roles.size() != cells.size()) {
            throw new IllegalArgumentException("安装段的 roles 要和格一一对应：" + kind);
        }
    }

    /** 不分角色的安装段（传送带、链条）。 */
    public Installation(String kind, List<BlockPos> cells) {
        this(kind, cells, List.of());
    }
}
