// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.Objects;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/**
 * 保存这一趟供料的请求和截止时间；首次加载本类时还会登记如何创建对应执行任务。
 */
public final class Ae2SupplyTaskRecord extends TaskRecord {
    static {
        TaskFactory.register(Ae2SupplyTaskRecord.class, Ae2SupplyTask::new);
    }

    public final Ae2ResourceSupply.Request request;
    public final Predicate<BlockPos> depositAccess;

    public Ae2SupplyTaskRecord(
            String toolCallId, long deadlineGameTime, Ae2ResourceSupply.Request request) {
        this(toolCallId, deadlineGameTime, request, position -> true);
    }

    /** 仅存入固定终端使用此范围／保护约束；旧供料和网络准备行为保持原样。 */
    public Ae2SupplyTaskRecord(String toolCallId, long deadlineGameTime, Ae2ResourceSupply.Request request,
                              Predicate<BlockPos> depositAccess) {
        super("ae2_supply", toolCallId, deadlineGameTime);
        this.request = Objects.requireNonNull(request, "request");
        this.depositAccess = Objects.requireNonNull(depositAccess, "depositAccess");
    }

    @Override
    public String describe() {
        if (request.operation() == Ae2ResourceSupply.Operation.OBSERVE) return "ae2_wireless_stock_observation";
        return (request.operation() == Ae2ResourceSupply.Operation.DEPOSIT ? "ae2_deposit " : "ae2_supply ") + request.totalCount() + " item(s) in "
                + request.groups().size() + " group(s)";
    }
}
