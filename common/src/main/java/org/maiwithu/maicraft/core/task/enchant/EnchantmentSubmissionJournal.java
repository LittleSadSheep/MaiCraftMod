// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.util.UUID;
import java.util.concurrent.Executor;
import org.maiwithu.maicraft.core.task.base.NativeConsumptionJournal;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 兼容原附魔日志调用与测试；实际存储共用原生消费日志，仍使用enchant-submissions和原操作编号。 */
public final class EnchantmentSubmissionJournal extends NativeConsumptionJournal {
    public EnchantmentSubmissionJournal(StateIdentity identity, UUID operationId) { super(identity, operationId, "enchant"); }
    EnchantmentSubmissionJournal(StateIdentity identity, UUID operationId, Executor executor) { super(identity, operationId, "enchant", executor); }
    EnchantmentSubmissionJournal(StateIdentity identity, UUID operationId, Executor executor, MarkerWriter writer) {
        super(identity, operationId, "enchant", executor, writer);
    }
}
