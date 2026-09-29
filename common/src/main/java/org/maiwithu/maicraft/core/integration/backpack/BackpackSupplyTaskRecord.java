// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 一次本人随身背包事务：观察、按候选物品取到指定总数，或存入已批准的余料清单。 */
public final class BackpackSupplyTaskRecord extends TaskRecord {
    public enum Operation { OBSERVE, WITHDRAW, DEPOSIT }
    static { TaskFactory.register(BackpackSupplyTaskRecord.class, BackpackSupplyTask::new); }
    public final Operation operation;
    public final int backpackSlot, amount;
    public final BackpackCarriers.Carrier carrier;
    public final List<ResourceLocation> items;
    public final Map<ResourceLocation, Integer> deposits;

    public BackpackSupplyTaskRecord(String id, long deadline, int slot, Operation operation,
            List<ResourceLocation> items, int amount, Map<ResourceLocation, Integer> deposits) {
        this(id, deadline, BackpackCarriers.Carrier.vanilla(slot), operation, items, amount, deposits);
    }
    /** 随身穿戴包携带原生库存地址；兼容字段backpackSlot只对主背包和副手有意义。 */
    public BackpackSupplyTaskRecord(String id, long deadline, BackpackCarriers.Carrier carrier, Operation operation,
            List<ResourceLocation> items, int amount, Map<ResourceLocation, Integer> deposits) {
        super("backpack_supply", id, deadline);
        this.operation = Objects.requireNonNull(operation); this.carrier = Objects.requireNonNull(carrier); this.backpackSlot = carrier.vanillaSlot();
        this.items = List.copyOf(items); this.amount = amount; this.deposits = Map.copyOf(deposits);
        // 数量上限与随身主背包一致；存入清单逐项批准，观察不携带隐藏的取物需求。
        if (amount < 0 || amount > 2304 || items.size() > 256 || deposits.size() > 36
                || deposits.values().stream().anyMatch(count -> count < 1 || count > 2304)
                || operation == Operation.OBSERVE && (amount != 0 || !items.isEmpty() || !deposits.isEmpty())
                || operation == Operation.WITHDRAW && (items.isEmpty() || amount < 1 || !deposits.isEmpty())
                || operation == Operation.DEPOSIT && (!items.isEmpty() || amount != 0 || deposits.isEmpty()))
            throw new IllegalArgumentException("invalid bounded backpack request");
    }
    @Override public String describe() { return "backpack " + operation.name().toLowerCase(Locale.ROOT); }
}
