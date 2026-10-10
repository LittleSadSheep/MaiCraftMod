// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Map;
import java.util.Objects;

/**
 * 一张网现在怎样：应力网络给容量、当前应力、成员数、转速；ME 网络给控制器状态、频道用量、能量是否在线、
 * 存储总量；能量网络给存量与上限。只回答网络级的汇总，不列箱子与驱动器里的东西（不透视）。
 *
 * @param networkId 网络编号，同 membership 给的
 * @param readable  读到了没有
 * @param readings  读数，键是给 LLM 看的名字，值只用 Long、Double、Boolean、String；读不到时为空
 * @param note      读不到的原因，例如"读不到：服务器没装 MaiCraft 的 AE2 读数"；读到时可以为空字符串
 */
public record NetworkSummary(String networkId, boolean readable, Map<String, Object> readings, String note) {

    public NetworkSummary {
        Objects.requireNonNull(networkId, "networkId");
        readings = Map.copyOf(readings);
        note = note == null ? "" : note;
    }

    /** 读不到这张网：带上原因。 */
    public static NetworkSummary unreadable(String networkId, String note) {
        return new NetworkSummary(networkId, false, Map.of(), note);
    }
}
