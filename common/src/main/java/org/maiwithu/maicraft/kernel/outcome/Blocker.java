// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.outcome;

import java.util.Objects;

/**
 * 卡点：任务做不下去的原因（一条游戏事实），以及什么能解除它。
 *
 * <p>例："没有床；做床还缺 2 块羊毛，附近没有羊"，解除办法是"带一张床，或提供羊毛"。
 * 没有把握的指路宁可不写（{@code unblock} 为 null），回执不得编造建议，也不教 LLM 换个开关重提。
 *
 * @param kind    卡点类别，小而稳定的一组（见 docs/design/03 的 M8）
 * @param fact    卡在哪：用游戏里的话描述的事实
 * @param unblock 什么能解除它；不确定时为 null
 */
public record Blocker(Kind kind, String fact, String unblock) {

    /** 卡点类别。新增类别前先确认现有类别确实表达不了，并同步更新 docs/design/07。 */
    public enum Kind {
        /** 缺物品：材料、食物、床……且自己弄不到。 */
        NEED_ITEM,
        /** 缺合适的工具：例如镐的等级不够，挖了也不掉落。 */
        NEED_TOOL,
        /** 需要超出当前授权的同意：例如要拆玩家盖的墙。 */
        NEED_CONSENT,
        /** 到不了：试过的工位与路线都不通。 */
        UNREACHABLE,
        /** 目标没了：方块被拆、实体走远或消失。 */
        TARGET_GONE,
        /** 危险：继续下去会死或大概率重伤。 */
        DANGER,
        /** 原版或模组明确拒绝了这个操作，附上它给的原因。 */
        NATIVE_REFUSED,
        /** 时间不对：例如白天不能睡，附上还要等多久。 */
        TIME_WINDOW,
        /** 世界规则不允许：例如下界、末地用床会爆炸。 */
        WORLD_RULE,
        /** 背包满，而且腾地方需要动到贵重物品。 */
        INVENTORY_FULL,
        /** 原地打转：换过办法仍长时间没有真实进展。 */
        STALLED,
        /** 当前没有支持这件事的实现，或需要的模组没装。 */
        UNSUPPORTED,
        /** 程序错误；不是游戏里发生的事，应当修代码。 */
        INTERNAL
    }

    public Blocker {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fact, "fact");
    }

    /** 带解除办法的卡点。 */
    public static Blocker of(Kind kind, String fact, String unblock) {
        return new Blocker(kind, fact, unblock);
    }

    /** 不知道怎样解除时只写事实。 */
    public static Blocker of(Kind kind, String fact) {
        return new Blocker(kind, fact, null);
    }
}
