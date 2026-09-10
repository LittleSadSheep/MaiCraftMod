// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskFactory;

/**
 * 当前控制能力是把已有机器的某个原版拉杆设置为开或关，提供请求检查和任务创建。没有在这里解释机器配方或自动选择多个开关。
 */
public final class MachineControl {
    private MachineControl() {}

    /**
     * Internally resolved request. The semantic caller owns permission to operate this machine;
     * neither a scan nor a control's proximity establishes that permission or its wiring.
     */
    public record Request(
            String dimension,
            BlockPos center,
            int radius,
            String structuralFingerprint,
            boolean desiredPowered,
            BlockPos controlPosition) {
        public Request {
            var region = new MachineSnapshots.Region(dimension, center, radius, structuralFingerprint);
            center = region.center();
            if (controlPosition != null) controlPosition = region.requirePosition(controlPosition);
        }
    }

    /** Force registration without installing another scheduler or exposing concrete coordinates. */
    public static void install() {
        TaskFactory.register(MachineControlTaskRecord.class, MachineControlTask::new);
    }

    public static MachineControlTaskRecord task(String callId, long deadlineGameTime, Request request) {
        install();
        return new MachineControlTaskRecord(callId, deadlineGameTime, request);
    }

    /** Selection never guesses which of several switches controls the intended output. */
    static BlockPos selectControl(List<BlockPos> candidates, BlockPos namedControl) {
        if (namedControl != null) return candidates.contains(namedControl) ? namedControl.immutable() : null;
        return candidates.size() == 1 ? candidates.getFirst().immutable() : null;
    }
}
