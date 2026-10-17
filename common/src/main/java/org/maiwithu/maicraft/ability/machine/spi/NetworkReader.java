// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Optional;

import net.minecraft.core.BlockPos;

/**
 * 一种网络的读法：应力网络、ME 网络、能量网络、流体与物品管网……
 * 机器能力只认这个接口：这一格属于哪张网、那张网怎样；接网络时两端在不在同一张网也靠它判断。
 *
 * <p>客户端能读的直接读（Create 把网络的应力容量、当前应力同步到每个成员的方块实体）；
 * 只能在服务端读的经服务端链路问一个只读操作，服务器没装对应读数时 summary 如实说读不到。
 * 只用 Minecraft 与 Java 的类型；实现由联动入口经登记表交来，进世界时带着联动能用的玩家行为建。
 */
public interface NetworkReader {

    /** 读的是哪种网络：machine_connect 的 network 参数可选值就是登记了的这些。 */
    NetworkKind kind();

    /** 这一格属于哪张网（网络编号，在这次读取里认得出同一张网就行）；不属于任何网、格子没加载给空。 */
    Optional<String> membership(BlockPos at);

    /** 那张网现在怎样；读不到时 readable 为假并写明原因。 */
    NetworkSummary summary(String networkId);

    /**
     * 那张网的读数还在路上：问了服务端、还没回答。看机器时问过一遍汇总后等它一会儿再交付，
     * 等不到就照实交"读不到"；客户端直接读得到的网永远不在路上。
     */
    default boolean summaryPending(String networkId) {
        return false;
    }

    /** 这种网怎么用方块接线：接网络时线路规划按它铺；不会接线的网给空，接网络时如实说铺不了。 */
    default Optional<LineMaterials> lineMaterials() {
        return Optional.empty();
    }
}
