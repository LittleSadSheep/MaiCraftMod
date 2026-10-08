// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 记住区域：角色记住的一块"这是玩家的地盘"，例如亲眼看着别人圈出来的一片农场。
 *
 * <p>保护判断把区域里的东西都当受保护处理；中心与半径是当时记下的，区域里的建筑可能后来又变了，
 * 所以到区域里动手前仍要看现场。
 *
 * @param name         这块地方叫什么，例如"河东的农场"
 * @param center       区域中心
 * @param radiusBlocks 半径，单位方块；从中心算直线距离
 */
public record RememberedRegion(String name, WorldPosition center, int radiusBlocks) {

    public RememberedRegion {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("记住区域的名字不能为空");
        if (center == null) throw new IllegalArgumentException("记住区域的中心不能为空");
        if (radiusBlocks <= 0) throw new IllegalArgumentException("记住区域的半径必须为正：" + radiusBlocks);
    }

    /** 一个位置算不算在这块区域里：维度相配（一方没写就当相配，宁可多算进保护）且到中心的直线距离不超过半径。 */
    public boolean contains(WorldPosition position) {
        if (position == null) return false;
        boolean dimensionKnown = center.dimension() != null && position.dimension() != null;
        boolean sameDimension = !dimensionKnown || center.dimension().equals(position.dimension());
        if (!sameDimension) return false;
        double dx = position.x() - center.x();
        double dy = position.y() - center.y();
        double dz = position.z() - center.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radiusBlocks;
    }
}
