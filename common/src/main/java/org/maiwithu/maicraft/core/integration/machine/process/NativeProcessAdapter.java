// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskRecord;

/** 每个原生机制提供自己的配方或菜单语义；观察只读，实际物品与世界操作由其有限任务执行。 */
public interface NativeProcessAdapter {
    String id();
    JsonObject contract();
    void validate(JsonObject parameters);
    boolean available();
    boolean matches(LocalPlayer player, BlockPos position);
    JsonObject inspect(LocalPlayer player, BlockPos position);
    TaskRecord createTask(String callId, long deadline, LocalPlayer player, BlockPos position, JsonObject parameters);
}
