// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // 已有结构可以声明只补装缺项；现场材料和修改范围须与这次实际原生动作一致。
    default Map<ResourceLocation, Integer> materials(Level world) { return materials(); }
    default boolean reusesPreparation(Level world) { return false; }
    default Set<BlockPos> mutationPositions(Level world) { return targets().keySet(); }
    boolean matches(Level world);
    TaskRecord task(String id, long deadline, List<BlockPos> installation, List<String> protectedLabels);
}
