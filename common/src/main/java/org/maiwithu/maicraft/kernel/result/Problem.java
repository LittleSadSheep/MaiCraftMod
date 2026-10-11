// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import java.util.Objects;

/**
 * 问题：任务做不下去的原因（一条游戏里的事实），以及怎样能继续。
 *
 * <p>例："没有床；做床还缺 2 块羊毛，附近没有羊"，建议是"带一张床来，或者给我羊毛"。
 * 没有把握的建议宁可不写（{@code suggestion} 为 null）：不编造建议，也不教 LLM 换个参数重试。
 *
 * <p>说明的写法是先一句结论，展开的部分用"；"接在后面。上一层转述这次失败时只引结论句（{@link #headline()}），
 * 展开的经过由这一层自己交代一次，不把整段说明一层层套进上一层的说明里。
 *
 * @param kind       问题种类，一组小而稳定的取值
 * @param message    卡在哪：用游戏里的话说清楚，先结论后展开
 * @param suggestion 怎样能继续；不确定时为 null
 */
public record Problem(Kind kind, String message, String suggestion) {

    /** 问题种类。新增种类前先确认现有种类确实表达不了，并同步更新对外接口文档。 */
    public enum Kind {
        /**
         * 参数不对，不进世界就能发现：物品或方块 ID 不存在、标签下一件注册物品都没有，
         * 或者缺了必填的、取值超了范围、几个参数凑不到一起。
         */
        INVALID_PARAMETER,
        /** 缺东西：材料、食物、床、够格的工具（例如镐的等级不够，挖了不掉落），而且自己弄不到。 */
        NEED_ITEM,
        /** 要做的事超出了这次任务的许可，需要 LLM 同意，例如要拆玩家盖的墙才能过去。 */
        NEED_APPROVAL,
        /** 到不了：试过的站位和路线都不通。 */
        UNREACHABLE,
        /**
         * 没找到：观察编号或名字对不上任何东西，或者查过的范围里没有要找的（查了多大范围随问题写明）。
         * 与"曾经见过、后来没了"（TARGET_GONE）分开：没找到要换地方找，没了要重新观察。
         */
        NOT_FOUND,
        /** 目标对象没了：方块被拆、实体走远或消失。 */
        TARGET_GONE,
        /** 危险：继续下去会死，或大概率重伤。 */
        DANGER,
        /** 游戏或模组拒绝了这次交互（包括服务器的领地保护、权限不够），附上游戏给的原因。 */
        REFUSED_BY_GAME,
        /** 时间不对：例如白天不能睡，附上还要等多久。 */
        WRONG_TIME,
        /** 在这里按游戏规则就做不到：例如下界、末地用床会爆炸。 */
        NOT_POSSIBLE_HERE,
        /** 背包满了，而且腾地方需要动到贵重物品。 */
        INVENTORY_FULL,
        /** 卡住了：换过办法仍然长时间没有真实进展，或者超过了这件事最多能做的时长。 */
        STUCK,
        /** 目前还不支持这件事，或者需要的模组没装。 */
        UNSUPPORTED,
        /** 程序出错；不是游戏里发生的事，应当修代码。 */
        INTERNAL_ERROR
    }

    public Problem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
    }

    /** 带建议的问题。 */
    public static Problem of(Kind kind, String message, String suggestion) {
        return new Problem(kind, message, suggestion);
    }

    /** 不知道怎样能继续时只写事实。 */
    public static Problem of(Kind kind, String message) {
        return new Problem(kind, message, null);
    }

    /**
     * 结论句：说明里第一个不在括号里的"；"之前的那一句，没有这样的分号就是整段。
     * 任务失败的一句话结论、上一层转述下一层的失败都只用它；展开的原因留在这条问题自己的说明里。
     * 括号里的分号属于括号里的补充（"试过自己做（缺羊毛；附近没有羊）"），不从那里截断。
     */
    public String headline() {
        int depth = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '（' || c == '(') depth++;
            else if ((c == '）' || c == ')') && depth > 0) depth--;
            else if (c == '；' && depth == 0) return message.substring(0, i);
        }
        return message;
    }
}
