// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次发话的任务输入：要发到游戏聊天的那句话。不可变；发没发出去、确认了没有都在任务里。
 * 以 / 开头的游戏命令进不来这里：能力在决定阶段就把命令禁令执行完，不会创建本输入。
 *
 * @param message 要发到游戏聊天的一句话，对全体玩家可见
 */
record ChatInput(String message) implements TaskInput {

    ChatInput {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("要说的话不能为空");
    }

    @Override
    public String describe() {
        return "对全体玩家说：" + message;
    }
}
