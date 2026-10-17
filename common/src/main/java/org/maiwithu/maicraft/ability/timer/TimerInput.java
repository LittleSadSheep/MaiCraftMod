// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.timer;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次计时操作的任务输入：设一个、按编号取消一个，或者看还挂着哪些。不可变；参数在能力的决定阶段已经核对过。
 *
 * @param operation    设、取消还是看
 * @param afterSeconds 设的时候过多少秒叫；别的操作为 0
 * @param note         设的时候到点带回的备注；别的操作为 null
 * @param timerId      取消哪个计时；别的操作为 0
 */
record TimerInput(Operation operation, int afterSeconds, String note, int timerId) implements TaskInput {

    /** 设一个计时、取消一个、看还挂着哪些。 */
    enum Operation { SET, CANCEL, LIST }

    TimerInput {
        if (operation == null) throw new IllegalArgumentException("要写清是设、取消还是看");
        if (operation == Operation.SET && (afterSeconds <= 0 || note == null || note.isBlank())) {
            throw new IllegalArgumentException("设计时要有正的秒数和备注");
        }
        if (operation == Operation.CANCEL && timerId <= 0) throw new IllegalArgumentException("取消要有计时编号");
    }

    static TimerInput set(int afterSeconds, String note) {
        return new TimerInput(Operation.SET, afterSeconds, note, 0);
    }

    static TimerInput cancel(int timerId) {
        return new TimerInput(Operation.CANCEL, 0, null, timerId);
    }

    static TimerInput list() {
        return new TimerInput(Operation.LIST, 0, null, 0);
    }

    @Override public String describe() {
        return switch (operation) {
            case SET -> afterSeconds + " 秒后叫我：" + note;
            case CANCEL -> "取消计时 " + timerId;
            case LIST -> "看还挂着哪些计时";
        };
    }
}
