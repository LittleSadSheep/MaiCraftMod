// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Simulated（物理组装器、无线红石打字机、舵轮这些载具部件）的读写接缝：玩家能做的原生操作。只翻译，不判断。
 */
public interface SimulatedParts {

    /**
     * 拉一次物理组装器的杆：和玩家按住右键把杆拉到底、松手时发的一样；组装器在世界里就组装、在结构上就拆回。
     *
     * @param at 组装器所在格：在世界里是世界坐标，在结构上是结构地块里的坐标
     */
    void pullAssembler(BlockPos at);

    /** 结构上一台无线红石打字机绑了频率的键（GLFW 键码）；那一格不是无线红石打字机、地块没加载为 null。 */
    Set<Integer> typewriterKeys(ClientLevel level, String structureId, BlockPos local);

    /**
     * 结构上那台无线红石打字机每个绑了频率的键：往哪一对频率发，给的收端候选里哪几格收这一对。
     * 机械动力的无线红石链路只算切到接收的。那一格不是无线红石打字机、地块没加载为 null。
     *
     * @param receivers 结构上的收端候选（地块坐标）
     */
    List<KeyLink> typewriterLinks(ClientLevel level, String structureId, BlockPos local, List<BlockPos> receivers);

    /**
     * 一个键的频率与收它的收端。
     *
     * @param key       键码
     * @param first     第一格频率物品的 ID（空着为 minecraft:air）
     * @param second    第二格频率物品的 ID
     * @param receivers 收这一对频率的收端所在格（地块坐标）
     */
    record KeyLink(int key, String first, String second, List<BlockPos> receivers) {
        public KeyLink {
            receivers = List.copyOf(receivers);
        }
    }

    /** 结构上那台无线红石打字机此刻是不是这个玩家在用（客户端看到的）。 */
    boolean typewriterUsedBy(ClientLevel level, String structureId, BlockPos local, UUID player);

    /** 按下或松开无线红石打字机的一个键：和真人接管无线红石打字机后按键盘时发的一样（按住不重发，松开发一次）。 */
    void typewriterKey(BlockPos local, int key, boolean press);

    /** 交还打字机：和真人接管时按 Esc 一样，客户端先放下，再告诉服务器断开。 */
    void typewriterDisconnect(ClientLevel level, String structureId, BlockPos local);
}
