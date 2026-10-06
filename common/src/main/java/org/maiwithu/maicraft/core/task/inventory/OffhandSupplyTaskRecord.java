// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 一次副手换位：把副手中 requested 物品所在的那一叠原样换进一个空主格，供主背包口径的动作取用。 */
public final class OffhandSupplyTaskRecord extends TaskRecord {
    static { TaskFactory.register(OffhandSupplyTaskRecord.class, OffhandSupplyTask::new); }
    public final List<ResourceLocation> items;
    public final int amount;

    public OffhandSupplyTaskRecord(String id, long deadline, List<ResourceLocation> items, int amount) {
        super("offhand_supply", id, deadline);
        this.items = List.copyOf(items);
        this.amount = amount;
        if (amount < 1 || amount > 2304 || items.isEmpty() || items.size() > 256)
            throw new IllegalArgumentException("invalid bounded offhand request");
    }
    @Override public String describe() { return "offhand_supply"; }
}
