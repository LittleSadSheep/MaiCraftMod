// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.quests;

import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestActionRequest;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;

/** 提交物品或领奖前沿用父任务的持久操作编号，暂停和重启都不能把同一步变成第二次消费。 */
public final class QuestActionTaskRecord extends NativeSubmissionTaskRecord {
    static { TaskFactory.register(QuestActionTaskRecord.class, QuestActionTask::new); }
    final FtbQuestActionRequest request;
    public QuestActionTaskRecord(String callId, FtbQuestActionRequest request) {
        super("quest_action", callId, NO_DEADLINE, "ftb-quest"); this.request = request;
    }
}
