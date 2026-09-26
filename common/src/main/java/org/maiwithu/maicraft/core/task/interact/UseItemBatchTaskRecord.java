// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.interact;

import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.task.TaskRecord;

/** 一次提交所需新增产物量；工具替换和双手原料准备由 Mod 执行，未确认的持用绝不自动重放。 */
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
