// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 在一台现成的 Create 置物台上加工一叠原料：手持原料右键放上 -> 等台上原料被现场机器加工掉 -> 空手右键收回。
 * 置物台位置、原料和最长等待时间由机器操作入口解析；放料、等待与收取由 DepotStationProcessTask 执行。
 */
public final class DepotStationProcessTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "station_process";
    public final BlockPos station;
    public final Item input;
    public final int maxWaitTicks;

    public DepotStationProcessTaskRecord(String callId, long deadlineGameTime, BlockPos station, Item input, int maxWaitTicks) {
        super(TOOL_NAME, callId, deadlineGameTime);
        this.station = Objects.requireNonNull(station, "station").immutable();
        this.input = Objects.requireNonNull(input, "input");
        this.maxWaitTicks = maxWaitTicks;
    }

    /** 任务工厂登记幂等；每次创建任务单前调用，不另装调度器。 */
    public static void install() {
        TaskFactory.register(DepotStationProcessTaskRecord.class, DepotStationProcessTask::new);
    }

    @Override public String describe() {
        return "process " + BuiltInRegistries.ITEM.getKey(input) + " on the existing depot at "
                + station.getX() + "," + station.getY() + "," + station.getZ();
    }
}
