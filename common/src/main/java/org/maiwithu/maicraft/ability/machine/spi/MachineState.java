// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一格机器此刻的运行状态，只读客户端看得见的：转不转、转多快、做到哪了、台面上放着什么。
 * "结构和档案一致"不等于"能转"，机器能力把这一份和结构核对分开交给 LLM。
 *
 * @param activity 在干活、停着、过载、离线，读不到时为 UNKNOWN
 * @param speed    转速（转/分），没有转速的机器为 null
 * @param progress 当前这一份做到几成（0 到 1），读不到或不在加工为 null
 * @param shown    台面、盆里、传送带上画出来的东西（看得见才算）
 * @param readings 其余读数，键是给 LLM 看的名字，值只用 Long、Double、Boolean、String；例如应力影响、能量存量
 * @param note     读不到某些东西时写原因，例如"读不到：服务器没装 MaiCraft 的 AE2 读数"；没有为空字符串
 */
public record MachineState(Activity activity, Double speed, Double progress, List<Shown> shown,
                           Map<String, Object> readings, String note) {

    public MachineState {
        Objects.requireNonNull(activity, "activity");
        shown = List.copyOf(shown);
        readings = Map.copyOf(readings);
        note = note == null ? "" : note;
    }

    /** 读不到状态：联动停用、格子没加载这类，带上原因。 */
    public static MachineState unknown(String note) {
        return new MachineState(Activity.UNKNOWN, null, null, List.of(), Map.of(), note);
    }

    /** 机器在干什么。 */
    public enum Activity {
        RUNNING,
        IDLE,
        OVERLOADED,
        OFFLINE,
        UNKNOWN
    }

    /** 画出来的一样东西：什么、几件。 */
    public record Shown(String itemId, int count) {
        public Shown {
            Objects.requireNonNull(itemId, "itemId");
        }
    }
}
