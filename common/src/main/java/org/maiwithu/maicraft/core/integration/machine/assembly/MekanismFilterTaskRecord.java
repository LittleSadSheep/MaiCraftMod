// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Internally compiled sorter output contract; it does not expose raw filters or menu clicks. */
public final class MekanismFilterTaskRecord extends TaskRecord {
    static { TaskFactory.register(MekanismFilterTaskRecord.class, MekanismFilterTask::new); }
    public final BlockPos target;
    public final ResourceLocation itemId;
    public MekanismFilterTaskRecord(String callId, long deadline, BlockPos target, String itemId) {
        super("machine_configure_sorter_filter", callId, deadline);
        this.target = Objects.requireNonNull(target).immutable();
        this.itemId = ResourceLocation.parse(itemId);
    }
    @Override public String describe() { return "configure sorter to extract only " + itemId; }
}
