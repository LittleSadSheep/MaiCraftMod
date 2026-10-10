// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest.spi;

import java.util.Objects;

/**
 * 任务书现在能不能用。
 *
 * @param usable 能用：装了、同步到了、这个服务器有任务书、队伍没被锁
 * @param reason 不能用的原因，例如"服务器还没同步任务书""这个服务器没有任务书"；能用时为空字符串
 */
public record QuestBookStatus(boolean usable, String reason) {

    public QuestBookStatus {
        reason = Objects.requireNonNullElse(reason, "");
    }

    /** 能用。 */
    public static QuestBookStatus ready() {
        return new QuestBookStatus(true, "");
    }

    /** 不能用，带上原因。 */
    public static QuestBookStatus unusable(String reason) {
        return new QuestBookStatus(false, reason);
    }
}
