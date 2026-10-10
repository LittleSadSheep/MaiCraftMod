// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 接网络的结果细节：两端落在哪、接没接上、在不在转、那张网的读数。
 * 接上了但没在转（转速 0、过载）不是接线的失败：joined 与 running 分开给，读数原样带出。
 *
 * @param kind       接的网种
 * @param targetCell 目标机器那一格
 * @param sourceCell 接去的那一格；到现场找来源没找到为 null
 * @param joined     两端是不是同一张网
 * @param running    接上的话机器在不在转；没接上为 null
 * @param summary    那张网现在的读数；没接上或读不到时 readable 为假
 * @param note       给 LLM 的一句说明（缺联动、线路 lay 不了的原因），没有为空字符串
 */
record MachineConnectDetails(NetworkKind kind, WorldPosition targetCell, WorldPosition sourceCell,
                             boolean joined, Boolean running, NetworkSummary summary, String note)
        implements ResultDetails {

    MachineConnectDetails {
        note = note == null ? "" : note;
    }
}
