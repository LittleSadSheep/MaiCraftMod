// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.kernel.result.Change;

/** 测试用的记账口：动作记了什么都留在这里，断言时按变化、没能确认、试过的办法分开看。 */
public final class CollectedRecords implements TaskRecords {

    public final List<Change> changes = new ArrayList<>();
    public final List<Change> unconfirmed = new ArrayList<>();
    public final List<String> attempts = new ArrayList<>();

    @Override public void change(Change change) {
        changes.add(change);
    }

    @Override public void unconfirmed(Change change) {
        unconfirmed.add(change);
    }

    @Override public void attempt(String tried, String whatHappened) {
        attempts.add(tried + "：" + whatHappened);
    }

    /** 没能确认的交互的说明文字，按记下的先后。 */
    public List<String> unconfirmedNotes() {
        return unconfirmed.stream().map(Change::note).toList();
    }
}
