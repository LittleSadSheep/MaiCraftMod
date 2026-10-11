// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条还成立的提醒：角色身上正在发生、LLM 自己不一定看得到的事（快没氧气了、工具快坏了、黑暗里反复挨打……）。
 * 提醒只说事实和建议，不接管角色、不替 LLM 做决定。
 *
 * @param id                提醒的种类，例如 drowning_imminent
 * @param message           一句话说清现在怎么了、建议做什么
 * @param firstObservedTick 这一回第一次看到的游戏刻
 * @param observedAtTick    最近一次确认还成立的游戏刻
 * @param evidence          凭什么这么说：读到的数值与阈值，原样交给 LLM
 * @param suggestions       可以考虑的做法；提醒本身不会去做
 */
public record Reminder(String id, String message, long firstObservedTick, long observedAtTick,
                       Map<String, Object> evidence, List<String> suggestions) {

    public Reminder {
        // 证据按写入的先后保留：先读到的数值在前，交给 LLM 时一眼看得懂。
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        suggestions = List.copyOf(suggestions);
    }
}
