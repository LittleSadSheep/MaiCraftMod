// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;

/** 生产子任务与父任务共用导航、序列化请求和原生子任务槽位。 */
public interface ProductionWork {
    boolean approach(BlockPos position);
    /** 只读证据可使用服务器提供的有界观察范围，不会操作机器。 */
    default boolean observe(BlockPos position) { return approach(position); }
    /** 有界组调用方提供每个位置，以遵守原生单目标距离限制。 */
    default boolean observe(List<BlockPos> positions) { return observe(positions.getFirst()); }
    default void stopMovement() {}
    default boolean showMachineMenu(BlockPos position, boolean required) { return true; }
    default boolean closeMachineMenu() { return true; }
    default Map<String, Long> processingProgress() { return Map.of(); }
    /** 仍在等待时返回 null；结算结果只消费一次；修改结果未知时抛出异常，且不重放操作。 */
    JsonObject request(String operation, JsonObject arguments, boolean mutating);
    TaskState advanceChild(Task task);
    void extendDeadlineTo(long gameTick);
    default void initialSupply(String source, ProductionManifest.Resource resource,
                               long credited, JsonObject snapshot) {}
    default void confirmedSupply(String source, ProductionManifest.Resource resource,
                                 String requestId, JsonObject result) {}
}
