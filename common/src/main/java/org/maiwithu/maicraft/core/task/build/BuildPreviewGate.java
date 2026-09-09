// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.client.preview.PreviewPart;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;

/** 一整份建筑只让玩家确认一次；分批取材料、分批施工时复用这次决定，不每批都重新弹预览。 */
public final class BuildPreviewGate {
    private static TaskRecord waiting;
    private static TaskRecord reviewOwner, reviewRoot;
    private static final Map<TaskRecord, Decision> resolved = new IdentityHashMap<>();
    private BuildPreviewGate() {}

    public static Decision await(TaskRecord owner, BuildTaskRecord plan) {
        // 上层已经负责整份蓝图的预览时，小批次不再另开预览。
        if (plan.previewManaged()) return Decision.DISABLED;
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        if (PreviewController.enabled() && (PreviewController.current() == null
                || PreviewController.current().designOnly()))
            plan.targets.forEach(target -> cells.put(target.pos(), target.desiredState()));
        return await(owner, owner.describe(), cells);
    }

    public static Decision await(TaskRecord owner, String title, Map<BlockPos, BlockState> cells) {
        return await(owner, title, cells, List.of());
    }

    public static Decision await(TaskRecord owner, String title, Map<BlockPos, BlockState> cells,
                                  List<PreviewPart> parts) {
        // 先读已有取消／确认结果；第一次等待时同时记住施工任务和它外面的总任务，取消才能停对对象。
        PreviewSession session = PreviewController.current();
        if (session != null && session.owner().equals(owner.publicId())
                && session.decision() == Decision.CANCELLED) return Decision.CANCELLED;
        // Dev toggled on during an already-started construction never suspends an in-flight receipt.
        if (resolved.containsKey(owner)) return resolved.get(owner);
        var level = Minecraft.getInstance().level;
        if (level == null) return Decision.CANCELLED;
        Decision decision = PreviewController.request(owner.publicId(),
                level.dimension().location().toString(), title, cells, parts);
        session = PreviewController.current();
        if (decision == Decision.WAITING && session != null && session.owner().equals(owner.publicId())) {
            waiting = owner;
            if (reviewOwner != owner) {
                reviewOwner = owner;
                reviewRoot = CompanionTickDispatcher.current();
            }
        } else {
            if (waiting == owner) waiting = null;
            if (decision == Decision.CONFIRMED || decision == Decision.DISABLED) resolved.put(owner, decision);
        }
        return decision;
    }

    /** 玩家看预览时施工不推进，给施工单和总任务各延后一刻，避免看图期间超时。 */
    public static void freezeWaitingDeadline() {
        if (waiting != null && !waiting.getState().isTerminal())
            waiting.extendDeadlineTo(waiting.getDeadlineGameTime() + 1);
        if (waiting != null && reviewRoot != null && reviewRoot != waiting && !reviewRoot.getState().isTerminal())
            reviewRoot.extendDeadlineTo(reviewRoot.getDeadlineGameTime() + 1);
    }

    /** 即使玩家按 F8 接管了输入，预览中的取消仍要处理，不能等自动控制恢复后才取消。 */
    public static void settleCancellation() {
        if (reviewOwner == null) return;
        TaskRecord owner = reviewOwner;
        TaskRecord active = CompanionTickDispatcher.current();
        PreviewSession session = PreviewController.current();
        if (session == null || active != reviewRoot || owner.getState().isTerminal()) {
            release(owner);
            return;
        }
        if (cancelReviewedTask(session, owner, reviewRoot, active, CompanionTickDispatcher::cancel))
            release(owner);
    }

    static boolean cancelReviewedTask(PreviewSession session, TaskRecord owner, TaskRecord expectedRoot,
                                       TaskRecord activeRoot, Consumer<String> cancel) {
        // 只取消当时展示这份预览的总任务；用户已经换了任务时，旧预览不能取消新任务。
        if (session == null || session.decision() != Decision.CANCELLED
                || !session.owner().equals(owner.publicId()) || expectedRoot == null
                || activeRoot != expectedRoot || activeRoot.getState().isTerminal()
                || !activeRoot.publicId().equals(expectedRoot.publicId())) return false;
        cancel.accept(expectedRoot.publicId());
        return true;
    }

    public static void release(TaskRecord owner) {
        // 施工结束后移走它的预览和决定记录，下一个建筑才能重新确认。
        PreviewController.release(owner.publicId());
        resolved.remove(owner);
        if (waiting == owner) waiting = null;
        if (reviewOwner == owner) { reviewOwner = null; reviewRoot = null; }
    }
}
