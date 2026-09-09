// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskRecord;

// 保存一次按观察结果存入或取出的要求：菜单编号、条目编号、物品种类与数量。
public final class MachineMenuTransferTaskRecord extends TaskRecord {
    public final String receiptId;
    public final boolean deposit;
    public final int entryIndex;
    public final ResourceLocation itemId;
    public final int count;
    public MachineMenuTransferTaskRecord(String callId, long deadline, String receiptId,
            String operation, int entryIndex, ResourceLocation itemId, int count) {
        super("machine_menu_transfer", callId, deadline);
        this.receiptId = Objects.requireNonNull(receiptId, "menu receipt");
        // 先检查编号和操作格式，数量限制为 1..64；对应菜单和条目现在是否仍相同，要由执行器继续核对。
        if (!MachineMenuPolicy.validReceiptId(receiptId)) throw new IllegalArgumentException("invalid observed menu receipt");
        if (!"deposit".equals(operation) && !"withdraw".equals(operation)) {
            throw new IllegalArgumentException("operation must be deposit or withdraw");
        }
        if (!MachineMenuPolicy.validTransferBounds(entryIndex, count)) {
            throw new IllegalArgumentException("invalid observed machine entry or count (1..64)");
        }
        this.deposit = "deposit".equals(operation);
        this.entryIndex = entryIndex;
        this.itemId = Objects.requireNonNull(itemId, "item ID");
        this.count = count;
    }
    @Override public String describe() {
        return (deposit ? "deposit " : "withdraw ") + count + " " + itemId + " against an observed machine entry";
    }
}
