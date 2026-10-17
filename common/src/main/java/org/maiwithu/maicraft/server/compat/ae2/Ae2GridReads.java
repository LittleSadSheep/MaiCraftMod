// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 在服务端读一张 ME 网络的模组读写接缝：某一格所在那张网的控制器、频道、能量、存储总量、合成 CPU。
 * 这些只在服务端的网格服务里有，客户端读不到。只回答网络级的汇总，不列存了哪些东西（不透视）。
 * 只用 Minecraft 与 Java 的类型；直接调 AE2 网格服务的读写端在 NeoForge 模块里，测试里用替身。
 */
public interface Ae2GridReads {

    /** 这一格所在那张网的汇总；这一格不在任何 ME 网上（不是 AE2 设备、没接网）给空。 */
    Optional<GridSummary> summarize(ServerLevel level, BlockPos at);

    /**
     * 一张 ME 网络的汇总。
     *
     * @param gridKey       这张网的编号：同一张网在这次服务器运行里问几次都一样
     * @param nodes         网上有几个节点（设备、线缆、部件）
     * @param controller    控制器的情况：none 没有、online 正常、conflict 摆法不对（几个控制器形状不合规）
     * @param booting       网络正在重新分配频道（刚接上、刚改过）
     * @param channelMode   频道模式：default、x2、x3、x4、infinite
     * @param channelsUsed  这张网用了几个频道
     * @param adHocLimit    没有控制器时这张网最多几个频道
     * @param adHocProblem  没有控制器的网出的问题：too_many_channels 频道超了、nested_p2p_tunnel；没有为空字符串
     * @param powered       网络有没有电
     * @param energyStored  存着多少 AE 能量
     * @param energyMax     最多能存多少
     * @param storedTypes   网络存储里有几种东西
     * @param storedAmount  网络存储里一共多少件（流体按 AE2 的计量）
     * @param craftingCpus  有几个合成 CPU
     * @param craftingBusy  其中几个正忙
     */
    record GridSummary(String gridKey, int nodes, String controller, boolean booting, String channelMode,
                       int channelsUsed, int adHocLimit, String adHocProblem, boolean powered, double energyStored,
                       double energyMax, int storedTypes, long storedAmount, int craftingCpus, int craftingBusy) {

        public GridSummary {
            Objects.requireNonNull(gridKey, "gridKey");
            Objects.requireNonNull(controller, "controller");
            Objects.requireNonNull(channelMode, "channelMode");
            adHocProblem = adHocProblem == null ? "" : adHocProblem;
        }
    }
}
