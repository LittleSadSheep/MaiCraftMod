// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Internal coordinates come from the machine layout compiler, never an LLM click script. */
public final class AePartTaskRecord extends TaskRecord {
    static { TaskFactory.register(AePartTaskRecord.class, AePartTask::new); }
    public final BlockPos target;
    public final MachineInstallation.PartSpec part;
    public AePartTaskRecord(String callId, long deadline, BlockPos target, String itemId, Direction side) {
        super("machine_install_ae_part", callId, deadline);
        this.target = Objects.requireNonNull(target).immutable();
        this.part = MachineInstallation.aePart(itemId, side);
    }
    @Override public String describe() { return "install AE2 part " + part.itemId(); }
}
