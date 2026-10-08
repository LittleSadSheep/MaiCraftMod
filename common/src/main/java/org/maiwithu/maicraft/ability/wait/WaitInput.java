// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.wait;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 等待任务的输入：等哪个条件、检查条件之前至少先过多少秒。
 *
 * <p>不可变；等待过程中的经过时刻都在任务对象里。
 *
 * @param condition    等什么
 * @param afterSeconds 检查条件之前至少经过的秒数；elapsed 条件下过了这段时间即完成
 */
record WaitInput(WaitFor condition, long afterSeconds) implements TaskInput {

    /** 检查条件前至少先过的秒数上限：一个小时，再长就该分段等。 */
    static final long MAX_AFTER_SECONDS = 3600;

    WaitInput {
        if (afterSeconds < 0 || afterSeconds > MAX_AFTER_SECONDS) {
            throw new IllegalArgumentException("after_seconds 应在 0.." + MAX_AFTER_SECONDS + "：" + afterSeconds);
        }
    }

    @Override
    public String describe() {
        return afterSeconds > 0
                ? "先等 " + afterSeconds + " 秒，再等到" + conditionDescription()
                : "等到" + conditionDescription();
    }

    private String conditionDescription() {
        return switch (condition) {
            case ELAPSED -> "时间到";
            case DAY -> "天亮";
            case NIGHT -> "天黑";
            case HEALTH_FULL -> "血回满";
            case NOT_HUNGRY -> "不再饥饿";
        };
    }
}
