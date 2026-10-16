// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.Objects;

/**
 * 一条任务事件：目标开始、向 LLM 提问、暂停、解除暂停、sequence 的一步结束、目标结束，
 * 或者角色自己处理不了的生存需求。
 *
 * <p>事件只说发生了什么。问题的选项、结果里的全部变化，由 LLM 按编号去查目标运行；
 * 事件被挤出事件流也不会丢掉这些事实。
 *
 * @param cursor    在事件流里的序号，从 1 开始递增
 * @param kind      发生了什么
 * @param goalRunId 相关的目标运行编号；和目标无关的事件为 -1。sequence 里某一步的事件记在整个 sequence 的编号上，
 *                  LLM 只认识自己下达的那个编号
 * @param message   一句话，例如问题的原文、结果的一句话结论
 * @param status    目标或某一步结束时的结果状态；其余事件为 null
 * @param selfBrief 目标或某一步结束那一刻角色的一行自身摘要（在哪、血、饱食、氧气、主手、开着的界面）；
 *                  其余事件、或结束时读不到角色时为 null
 */
public record TaskEvent(long cursor, Kind kind, long goalRunId, String message, TaskResult.Status status,
                        String selfBrief) {

    /** 发生了什么。 */
    public enum Kind {
        /** 目标开始推进。 */
        STARTED,
        /** 向 LLM 提问了，回答之前这个目标不往下做。 */
        ASKED,
        /** 目标暂停：被要求暂停，或者重启后恢复为暂停。 */
        PAUSED,
        /** 解除暂停，接着推进。 */
        RESUMED,
        /** sequence 里的一步结束了。 */
        STEP_FINISHED,
        /** 目标结束，完整结果按编号查。 */
        FINISHED,
        /** 临时任务开始：生存需求自己派活（自卫、回洞、退开）顶上，主任务让路。 */
        TEMPORARY_TASK_STARTED,
        /** 临时任务结束：威胁解除或条件恢复，主任务接着做。 */
        TEMPORARY_TASK_FINISHED,
        /** 角色自己处理不了的生存需求，例如饿了、身上没吃的、也弄不到。 */
        NEED_UNHANDLED,
        /** 角色死了：血量见底进了死亡流程，循环停摆只报这一条，等重生。 */
        CHARACTER_DIED,
        /** 死亡恢复决策已经执行：重生或观战的请求发出去了，或任务已取消；做了什么在消息里。 */
        DEATH_RECOVERY_APPLIED,
        /** 在用的工具或盔甲耐久打空消灭了：坏的是哪一件、在哪个槽位、满耐久多少在消息里。 */
        GEAR_BROKEN,
        /** 角色身上有事要 LLM 知道（快溺水、装备快坏、黑暗里反复挨打……），或这条提醒撤下了；证据在 observe(self) 的 reminders。 */
        REMINDER,
        /** 探索途中的发现：看见什么、在哪，一句话在消息里；同一条发现只发一条，完整的清单在探索结果里。 */
        DISCOVERED,
        /**
         * 刚才挨了一阵打：同一个来源（攻击者或伤害类型）连着挨的算一段，不分开始结束，一段一条；
         * 谁、什么伤、几下、掉了多少、还剩多少在消息里，没收到伤害包的写明只看到血掉了。
         */
        HURT,
        /** 换了维度：从哪到哪、落在哪在消息里（走门过去、重生到另一个维度都算）。 */
        DIMENSION_CHANGED,
        /**
         * 跟随的状况变了：跟不过去停下等、跟丢了在找、又跟上了、跟着过门、上下他那辆载具、开始与结束帮他打；
         * 发生了什么、他在哪、我在哪在消息里。角色不因这条停下等回答，照常跟着。
         */
        FOLLOW_STATUS,
        /**
         * 天色变了：天黑（到了能睡的时间）、天亮（过了能睡的时间）、开始下雨、雨停，只在变的那一刻发一条；
         * 此刻的时间与天气在消息里。没有昼夜的维度不发。
         */
        SKY_CHANGED
    }

    /** 不附自身摘要的事件：开始、提问、暂停、生存需求这些都是这一种。 */
    public TaskEvent(long cursor, Kind kind, long goalRunId, String message, TaskResult.Status status) {
        this(cursor, kind, goalRunId, message, status, null);
    }

    public TaskEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        if (cursor < 1) throw new IllegalArgumentException("事件序号从 1 开始：" + cursor);
    }
}
