// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookStatus;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;

/**
 * 联动模组交来的任务书操作，登记表给它包的一层：模组停用后任务书如实说用不了并写明原因，条目一律读不到，
 * 发送一律答"没发出去"；读写时碰到模组接口对不上也在这里收住，quest 能力按"不支持"如实结束。
 */
public final class CompatQuestBookOperations implements QuestBookOperations {

    private final CompatModule module;
    private final QuestBookOperations operations;

    public CompatQuestBookOperations(CompatModule module, QuestBookOperations operations) {
        this.module = Objects.requireNonNull(module, "module");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override public QuestBookStatus status() {
        if (!module.active()) return QuestBookStatus.unusable(disabled());
        try {
            return operations.status();
        } catch (ModApiMismatch broken) {
            return QuestBookStatus.unusable(broken.getMessage());
        }
    }

    @Override public Optional<QuestView> quest(String questId) {
        if (!module.active()) return Optional.empty();
        try {
            return operations.quest(questId);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public boolean submit(String questId, String requirementId) {
        return send(() -> operations.submit(questId, requirementId));
    }

    @Override public boolean confirm(String questId, String requirementId) {
        return send(() -> operations.confirm(questId, requirementId));
    }

    @Override public boolean claim(String questId, String rewardId, String choice) {
        return send(() -> operations.claim(questId, rewardId, choice));
    }

    // 发送：停用了或对不上时就是没发出去，能力按"没发出去"如实结束，不补发。
    private boolean send(BooleanSupplier sending) {
        if (!module.active()) return false;
        try {
            return sending.getAsBoolean();
        } catch (ModApiMismatch broken) {
            return false;
        }
    }

    private String disabled() {
        return module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明");
    }
}
