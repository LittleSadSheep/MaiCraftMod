// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import net.minecraft.core.BlockPos;

/**
 * machine_run 的一次请求，交给机器类型看要不要自己从头做到尾（见 {@link MachineType#fullRun}）。
 *
 * @param item       要做出什么；不给为 null
 * @param count      要做几件
 * @param file       蓝图文件名：游戏目录 schematics 下的 .nbt（和 build 的 file 同一个目录）；不给为 null
 * @param anchor     蓝图原点落在哪一格；不给为 null
 * @param rotation   蓝图绕原点转 0 / 90 / 180 / 270 度
 * @param maxSeconds 最多等多久（秒）
 */
public record RunRequest(String item, int count, String file, BlockPos anchor, int rotation, int maxSeconds) {

    public RunRequest {
        anchor = anchor == null ? null : anchor.immutable();
    }
}
