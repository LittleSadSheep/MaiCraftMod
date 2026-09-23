// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.task.TaskRecord;

/** 原生安装原语：准备格与最终格分开，材料按一次真实安装计数，不把连接器当成每格方块。 */
public interface MachineNativeInstallation {
    Map<BlockPos, BlockState> preparation();
    Map<BlockPos, BlockState> targets();
    Map<ResourceLocation, Integer> materials();
    boolean matches(Level world);
    TaskRecord task(String id, long deadline, List<BlockPos> installation, List<String> protectedLabels);
}
