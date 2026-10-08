// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import java.util.Objects;

/**
 * 问题：任务做不下去的原因（一条游戏里的事实），以及怎样能继续。
 *
 * <p>例："没有床；做床还缺 2 块羊毛，附近没有羊"，建议是"带一张床来，或者给我羊毛"。
 * 没有把握的建议宁可不写（{@code suggestion} 为 null）：不编造建议，也不教 LLM 换个参数重试。
 *
 * @param kind       问题种类，一组小而稳定的取值
 * @param message    卡在哪：用游戏里的话说清楚
 * @param suggestion 怎样能继续；不确定时为 null
 */
public record Problem(Kind kind, String message, String suggestion) {

    /** 问题种类。新增种类前先确认现有种类确实表达不了，并同步更新对外接口文档。 */
    public enum Kind {
        /** 参数在游戏里立不住：物品或方块 ID 不存在、标签下一件注册物品都没有。这类问题不进世界就能发现。 */
        INVALID_PARAMETER,
        /** 缺东西：材料、食物、床、够格的工具（例如镐的等级不够，挖了不掉落），而且自己弄不到。 */
        NEED_ITEM,
        /** 要做的事超出了这次任务的许可，需要 LLM 同意，例如要拆玩家盖的墙才能过去。 */
        NEED_APPROVAL,
        /** 到不了：试过的站位和路线都不通。 */
        UNREACHABLE,
        /** 查无此物：观察编号或名字对不上任何东西，与"曾经见过、后来没了"（TARGET_GONE）分开。 */
        NOT_FOUND,
        /** 目标对象没了：方块被拆、实体走远或消失。 */
        TARGET_GONE,
        /** 查过的范围里没有找到要找的东西；查了多大范围随问题写明，与 TARGET_GONE（原来有、现在没了）分开。 */
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
        /** 参数给得不对：缺了必填的、取值超了范围、几个参数凑不到一起；不进游戏就能发现。 */
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
}
