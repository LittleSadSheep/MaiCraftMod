// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** Human-owned controls must not block cancellation or let an old review cancel newer work. */
public final class BuildPreviewCancellationTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        TaskRecord root = new Record(), child = new Record(), replacement = new Record();
        var session = new PreviewSession(child.publicId(), "minecraft:overworld", "review",
                Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()));
        List<String> cancelled = new ArrayList<>();
        check(!BuildPreviewGate.cancelReviewedTask(session, child, root, root, cancelled::add),
                "waiting review cannot cancel its root");
        session.cancel();
        check(!BuildPreviewGate.cancelReviewedTask(session, child, root, replacement, cancelled::add),
                "replacing the active task invalidates old cancellation authority");
        check(!BuildPreviewGate.cancelReviewedTask(session, replacement, root, root, cancelled::add),
                "a review cannot cancel on behalf of another owner");
        check(BuildPreviewGate.cancelReviewedTask(session, child, root, root, cancelled::add)
                && cancelled.equals(List.of(root.publicId())), "the captured root is cancelled without input authority");
        root.setState(TaskState.CANCELLED);
        check(!BuildPreviewGate.cancelReviewedTask(session, child, root, root, cancelled::add),
                "terminal roots are never cancelled twice");
        System.out.println("BuildPreviewCancellationTest: passed");
    }

    private static final class Record extends TaskRecord {
        Record() { super("preview_test", "test", 100); }
        @Override public String describe() { return "preview test"; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
