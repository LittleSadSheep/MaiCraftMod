// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.Objects;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Scheduler record for one semantic AE2 supply request. */
public final class Ae2SupplyTaskRecord extends TaskRecord {
    static {
        TaskFactory.register(Ae2SupplyTaskRecord.class, Ae2SupplyTask::new);
    }

    public final Ae2ResourceSupply.Request request;

    public Ae2SupplyTaskRecord(
            String toolCallId, long deadlineGameTime, Ae2ResourceSupply.Request request) {
        super("ae2_supply", toolCallId, deadlineGameTime);
        this.request = Objects.requireNonNull(request, "request");
    }

    @Override
    public String describe() {
        return "ae2_supply " + request.totalCount() + " item(s) in "
                + request.groups().size() + " group(s)";
    }
}
