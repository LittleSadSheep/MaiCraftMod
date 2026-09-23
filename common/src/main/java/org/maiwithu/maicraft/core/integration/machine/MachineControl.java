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
     * 内部解析后的请求。操作此机器的权限由语义调用方持有；扫描结果或控制器距离较近，都不能证明已获授权或线路连接正确。
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

    /** 强制注册任务，但不安装第二套调度器，也不公开具体坐标。 */
    public static void install() {
        TaskFactory.register(MachineControlTaskRecord.class, MachineControlTask::new);
    }

    public static MachineControlTaskRecord task(String callId, long deadlineGameTime, Request request) {
        install();
        return new MachineControlTaskRecord(callId, deadlineGameTime, request);
    }

    /** 多个开关中绝不猜测哪个控制目标输出。 */
    static BlockPos selectControl(List<BlockPos> candidates, BlockPos namedControl) {
        if (namedControl != null) return candidates.contains(namedControl) ? namedControl.immutable() : null;
        return candidates.size() == 1 ? candidates.getFirst().immutable() : null;
    }
}
