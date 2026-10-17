// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * 读机械动力链条输送机的模组读写接缝：一台链条输送机连着哪几台（相对位置）、连一条要几根锁链，
 * 以及服务器配置的最长连多远、每台最多连几条。
 *
 * <p>连链条是手持锁链先右键一台、再右键另一台：第一下只记在客户端，第二下才发给服务器，
 * 服务器扣锁链、在两台上都记下这条连接。只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateChainConveyors {

    /** 这一格链条输送机已经连着的那几台，按相对这一格的位置给；不是链条输送机、没加载给空。 */
    Optional<Set<BlockPos>> connections(ClientLevel level, BlockPos at);

    /** 连一条这么长的链（终点减起点）要几根锁链；按 Create 自己的算法，最少一根。 */
    int chainCost(BlockPos delta);

    /** 服务器配置里一条链最长几格（两台的距离必须小于它）。 */
    int maxLength();

    /** 服务器配置里一台链条输送机最多连几条。 */
    int maxConnections();
}
