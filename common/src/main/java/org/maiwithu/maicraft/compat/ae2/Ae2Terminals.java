// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * 找 ME 终端的模组读写接缝：角色周围哪一格的哪一面装着 ME 终端，终端面板占着方块里的哪一块。
 * ME 终端不是一整格方块，而是装在线缆方块某一面上的一块面板；同一格的另外几面可能还有别的部件或线缆芯，
 * 所以要点开它得点在面板上。只用 Minecraft 与 Java 的类型描述；真正看模组方块实体的读写端在 NeoForge 模块里，测试里用替身。
 */
public interface Ae2Terminals {

    /**
     * 以 center 为中心、边长 2×radius+1 的方块范围里，已加载区块中装着 ME 终端的那些面。
     * 只读客户端已经有的方块实体，不加载新区块；ME 终端、合成终端、样板编码终端都算。
     */
    List<Terminal> near(ClientLevel level, BlockPos center, int radius);

    /** 这一格的这一面现在还装着 ME 终端没有；格子没加载算没有。到了跟前、点开之前用它核对记忆有没有过时。 */
    boolean stillThere(ClientLevel level, BlockPos block, Direction side);

    /**
     * 装在某一格某一面上的一台 ME 终端。
     *
     * @param block  终端所在的格子（线缆方块那一格）
     * @param side   终端装在这一格的哪一面，面板朝着这一面的反方向
     * @param panel  终端面板占的范围，世界坐标；点开时命中点要落在这里面，不能点到线缆芯
     * @param partId 终端部件的物品 ID，例如 ae2:terminal、ae2:crafting_terminal；给结果与日志看是哪种终端
     */
    record Terminal(BlockPos block, Direction side, AABB panel, String partId) {
        public Terminal {
            block = Objects.requireNonNull(block, "block").immutable();
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(panel, "panel");
            Objects.requireNonNull(partId, "partId");
        }

        /** 给结果与日志看的一句话，例如"ae2:terminal（12,64,-3 的北面）"。 */
        public String describe() {
            return partId + "（" + block.toShortString() + " 的" + sideName(side) + "）";
        }

        private static String sideName(Direction side) {
            return switch (side) {
                case DOWN -> "下面";
                case UP -> "上面";
                case NORTH -> "北面";
                case SOUTH -> "南面";
                case WEST -> "西面";
                case EAST -> "东面";
            };
        }
    }
}
