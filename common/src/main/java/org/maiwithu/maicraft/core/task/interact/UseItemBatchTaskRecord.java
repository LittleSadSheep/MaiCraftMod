// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.interact;

import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.task.TaskRecord;

/** 一次提交 1～64 件新增产物；工具、可选副手原料与产物必须是不同物品类型，避免把选物自身计成加工产量。 */
public final class UseItemBatchTaskRecord extends TaskRecord {
    public final Item tool, ingredient, output;
    public final int count;

    public UseItemBatchTaskRecord(String callId, long deadline, Item tool, Item ingredient, Item output, int count) {
        super("use_item_batch", callId, deadline);
        if (tool == null || output == null || count < 1 || count > 64 || tool == output || ingredient == output || tool == ingredient)
            throw new IllegalArgumentException("batch item use needs distinct tool/material/output and 1..64 new outputs");
        this.tool = tool; this.ingredient = ingredient; this.output = output; this.count = count;
    }
}
