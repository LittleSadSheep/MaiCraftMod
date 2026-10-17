// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import org.maiwithu.maicraft.game.interaction.PendingInteraction;

/**
 * 交互结论加上现场事实：结论回答"做成没有"，现场回答"当时看到的是什么"。
 * 现场包括目标格提交前后的状态、提交时射线命中了谁，以及确认记录里游戏给的原因，
 * 让看结果的人不用再猜"那一下到底点没点上"。
 *
 * @param verdict 交互结论
 * @param scene   一段写给人和 LLM 看的现场说明
 */
public record InteractionResult(InteractionVerdict verdict, String scene) {

    public InteractionResult {
        if (scene == null || scene.isBlank()) {
            throw new IllegalArgumentException("交互结果必须带现场说明");
        }
    }

    public static InteractionResult applied(String scene) {
        return new InteractionResult(InteractionVerdict.APPLIED, scene);
    }

    public static InteractionResult notApplied(String scene) {
        return new InteractionResult(InteractionVerdict.NOT_APPLIED, scene);
    }

    public static InteractionResult unexpected(String scene) {
        return new InteractionResult(InteractionVerdict.UNEXPECTED, scene);
    }

    public static InteractionResult unconfirmed(String scene) {
        return new InteractionResult(InteractionVerdict.UNCONFIRMED, scene);
    }

    /** 把游戏确认记录的终态翻译成交互结论；确认记录里的原因保留在现场说明里。 */
    public static InteractionResult of(PendingInteraction pending, String scene) {
        return switch (pending.status()) {
            case CONFIRMED_APPLIED -> applied(scene + "；确认记录：" + pending.detail());
            case CONFIRMED_NOT_APPLIED -> notApplied(scene + "；确认记录：" + pending.detail());
            case DIVERGED -> unexpected(scene + "；确认记录：" + pending.detail());
            case PENDING -> unconfirmed(scene + "；确认还没等到结果就结束了");
            case CANCELLED, UNCERTAIN -> unconfirmed(scene + "；确认记录：" + pending.detail());
        };
    }
}
