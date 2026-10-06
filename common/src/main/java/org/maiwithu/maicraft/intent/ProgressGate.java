// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * 进度事件的门卫：记分牌变了才有话可说，说了还要过地板间隔。
 * 词汇契约（done/total、remaining/initial、phase）与触发规则的权威文档在
 * docs/architecture/07-attention.md；一个标准键都没有的观察无话可说——
 * 保持沉默，绝不编造 "still working" 之类的填充话。
 */
final class ProgressGate {
    /** null = 这一刻不发布；keylessWarn=true 时调用方应记日志提醒开发者。 */
    record Decision(String message, boolean keylessWarn) {}

    // body 是身体安全键：值含当前位置，滞水随浪况浮沉不断变化签名，受胁状态按地板间隔持续可见。
    // planning_seconds 是静默窗心跳键：只在零推进的段里单调增长，让「还在等」按地板间隔持续可见。
    private static final List<String> SCOREBOARD_KEYS =
            List.of("done", "total", "phase", "remaining", "initial", "calc", "body", "planning_seconds");
    private static final long FLOOR_TICKS = 40;
    static final long KEYLESS_WARN_TICKS = 2400;

    private final Map<UUID, Long> lastPublishedAt = new HashMap<>();
    private final Map<UUID, String> lastSignature = new HashMap<>();
    private final Map<UUID, Map<String, Object>> pending = new HashMap<>();
    private final Map<UUID, Long> keylessSince = new HashMap<>();
    private final Map<UUID, Boolean> keylessWarned = new HashMap<>();

    synchronized Decision consider(UUID taskId, Map<String, Object> observation, long gameTime) {
        String signature = signature(observation);
        if (signature.isEmpty()) {
            pending.remove(taskId);
            keylessSince.putIfAbsent(taskId, gameTime);
            boolean overdue = gameTime - keylessSince.get(taskId) >= KEYLESS_WARN_TICKS;
            boolean firstWarn = keylessWarned.put(taskId, true) == null;
            if (overdue && firstWarn) return new Decision(null, true);
            return null;
        }
        keylessSince.remove(taskId);
        if (!signature.equals(lastSignature.get(taskId))) {
            // 变化先进待发区；地板窗口内的后续变化覆盖旧待发，只留最新值。
            pending.put(taskId, observation);
            lastSignature.put(taskId, signature);
        }
        Map<String, Object> ready = pending.get(taskId);
        if (ready == null) return null;
        Long publishedAt = this.lastPublishedAt.get(taskId);
        // 不能用 MIN_VALUE 当"从未发布"哨兵：gameTime - MIN_VALUE 会回绕成负数，首个事件永远过不了地板。
        if (publishedAt != null && gameTime - publishedAt < FLOOR_TICKS) {
            return null;
        }
        pending.remove(taskId);
        this.lastPublishedAt.put(taskId, gameTime);
        return new Decision(render(ready), false);
    }

    /** 终态后清掉该任务的全部记账，让它的下一次进度立刻可发。 */
    synchronized void forget(UUID taskId) {
        lastPublishedAt.remove(taskId);
        lastSignature.remove(taskId);
        pending.remove(taskId);
        keylessSince.remove(taskId);
        keylessWarned.remove(taskId);
    }

    // 签名只由标准键组成：专有字段（来源数、批次）变化不触发发布，但随事件 data 原样携带。
    private static String signature(Map<String, Object> observation) {
        StringJoiner joiner = new StringJoiner(";");
        for (String key : SCOREBOARD_KEYS) {
            Object value = observation.get(key);
            if (value != null) joiner.add(key + "=" + value);
        }
        return joiner.toString();
    }

    private static String render(Map<String, Object> observation) {
        StringJoiner parts = new StringJoiner(" · ");
        Object done = observation.get("done");
        Object total = observation.get("total");
        if (done != null && total != null) parts.add(done + "/" + total);
        Object remaining = observation.get("remaining");
        if (remaining != null) {
            Object initial = observation.get("initial");
            parts.add(initial == null ? "剩余 " + remaining + " 格"
                    : "剩余 " + remaining + "/" + initial + " 格");
        }
        if (observation.get("phase") != null) parts.add(String.valueOf(observation.get("phase")));
        // 规划心跳：搜索尝试次数只在规划期增长，让「还在算」的停滞每过地板间隔仍有一条事件可发。
        Object calc = observation.get("calc");
        if (calc != null) parts.add("calc " + calc);
        Object planningSeconds = observation.get("planning_seconds");
        if (planningSeconds != null) parts.add("planning " + planningSeconds + "s");
        Object body = observation.get("body");
        if (body != null) parts.add(String.valueOf(body));
        return parts.toString();
    }
}
