// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 固定一条作者选择的传送带，角色只负责实际连接和核对，不重新挑路线或供料来源。 */
public final class CreateBeltInstallTaskRecord extends TaskRecord {
    static { TaskFactory.register(CreateBeltInstallTaskRecord.class, CreateBeltInstallTask::new); }
    public final CreateBeltGeometry.Span span;
    public final Set<BlockPos> pulleys;
    public final List<BlockPos> installation;
    public final List<String> protectedLabels;
    public CreateBeltInstallTaskRecord(String id, long deadline, CreateBeltGeometry.Span span, Set<BlockPos> pulleys,
                                      List<BlockPos> installation, List<String> protectedLabels) {
        super("install_native_belt", id, deadline); this.span = span; this.pulleys = Set.copyOf(pulleys);
        this.installation = List.copyOf(installation); this.protectedLabels = List.copyOf(protectedLabels);
    }
    @Override public String describe() { return "连接传送带 · " + span.cells().size() + " 格"; }
}
