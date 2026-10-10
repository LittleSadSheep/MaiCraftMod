// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 部件：装在宿主方块某一面上的一块东西，例如 AE2 线缆上的终端、输入总线。
 * 施工引擎不放宿主以外的东西，部件由认领它的机器类型手持部件右键那一面装上、再核对。
 * 机器蓝图里写的是相对锚点的位置，落地后换成这里的世界坐标。
 *
 * @param host   宿主方块所在的格（例如线缆那一格），世界坐标
 * @param side   装在宿主的哪一面
 * @param itemId 部件的物品 ID，例如 ae2:terminal
 */
public record PartCell(BlockPos host, Direction side, String itemId) {

    public PartCell {
        host = Objects.requireNonNull(host, "host").immutable();
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(itemId, "itemId");
    }
}
