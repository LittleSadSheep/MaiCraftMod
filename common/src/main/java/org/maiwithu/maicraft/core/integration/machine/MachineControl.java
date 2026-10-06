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

    /**
     * 选杆结果：position 为空表示没选出来；basis 说明是怎么选中的，nearby 是点名格附近或整个范围内的候选拉杆，
     * 选不出来时原样回报给调用方，让它改用目标拉杆的坐标。
     */
    public record Selection(BlockPos position, String basis, List<BlockPos> nearby) {}

    /** 点名格旁边多远以内的拉杆算“挂在这块告示牌/地标旁边”的那根。 */
    static final int NAMED_CELL_NEIGHBOUR_RANGE = 2;

    /**
     * 多个开关中绝不猜测哪个控制目标输出。点名格本身就是拉杆时只认它；点名格是告示牌或地标所在格、
     * 本身不是拉杆时，取它两格内唯一的一根，并在回执里说明是按“点名格旁边唯一拉杆”选的；
     * 两格内有多根或一根都没有就不选。没有点名时整个范围内必须恰好一根。
     */
    static Selection selectControl(List<BlockPos> candidates, BlockPos namedControl) {
        if (namedControl == null) return candidates.size() == 1
                ? new Selection(candidates.getFirst().immutable(), "only_lever_in_region", List.of())
                : new Selection(null, null, List.copyOf(candidates));
        if (candidates.contains(namedControl)) return new Selection(namedControl.immutable(), "named_lever", List.of());
        List<BlockPos> near = candidates.stream().filter(position -> Math.max(Math.abs(position.getX() - namedControl.getX()),
                Math.max(Math.abs(position.getY() - namedControl.getY()), Math.abs(position.getZ() - namedControl.getZ())))
                <= NAMED_CELL_NEIGHBOUR_RANGE).map(BlockPos::immutable).toList();
        return near.size() == 1 ? new Selection(near.getFirst(), "unique_lever_near_named_cell", List.of())
                : new Selection(null, null, near);
    }
}
