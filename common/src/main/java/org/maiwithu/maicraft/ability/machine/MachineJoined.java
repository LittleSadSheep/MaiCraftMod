// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 接网核对：两端在不在同一张网上、接上了转不转得动，纯函数。
 *
 * <p>两端在请求的那种网上的编号一样才算接上；接上了还要看目标机器自己的读数——
 * 转速为零、过载、离线都算没在转。接上了但没在转不是接线的失败：线路是通的，
 * 读数如实写进结果，设计对不对留给 LLM 判断。
 */
final class MachineJoined {

    /** 一次核对的结论：接没接上、在不在转、一句给 LLM 看的说明。 */
    record Verdict(boolean joined, boolean running, String note) {

        Verdict {
            note = note == null ? "" : note;
        }
    }

    private MachineJoined() {
    }

    /**
     * @param targetNetwork 目标那格在这种网上的编号；不在这种网上给空
     * @param sourceNetwork 来源那格在这种网上的编号；不在这种网上给空
     * @param targetState   目标机器此刻的运行读数
     */
    static Verdict judge(Optional<String> targetNetwork, Optional<String> sourceNetwork, MachineState targetState) {
        Objects.requireNonNull(targetState, "targetState");
        if (targetNetwork.isEmpty()) {
            return new Verdict(false, false, "目标不在这种网上");
        }
        if (sourceNetwork.isEmpty()) {
            return new Verdict(false, false, "要接去的那一格不在这种网上");
        }
        if (!targetNetwork.get().equals(sourceNetwork.get())) {
            return new Verdict(false, false,
                    "目标在网 " + targetNetwork.get() + "，来源在网 " + sourceNetwork.get() + "，还不是同一张");
        }
        return joined(targetState);
    }

    // 同一张网了：转没转看目标机器自己的读数，有转速的看转速，过载与离线算没在转，读不出的不硬说。
    private static Verdict joined(MachineState state) {
        return switch (state.activity()) {
            case OVERLOADED -> new Verdict(true, false, "已经同一张网，但机器过载了");
            case OFFLINE -> new Verdict(true, false, "已经同一张网，但机器离线");
            case RUNNING -> new Verdict(true, true, "已经接着，机器在转");
            case IDLE -> state.speed() != null && state.speed() == 0
                    ? new Verdict(true, false, "已经同一张网，但转速为 0")
                    : new Verdict(true, true, "已经接着，机器停着等活");
            case UNKNOWN -> new Verdict(true, true, "已经同一张网；机器读数读不到，转没转要自己看");
        };
    }
}
