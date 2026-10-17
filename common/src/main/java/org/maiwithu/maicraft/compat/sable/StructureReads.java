// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 读物理结构的那几样：附近有哪些结构、地块里一格是什么、逐格读的结果。Sable 与 Simulated 两个联动入口都有，
 * 上下船这一段、驾驶前走到驾驶位都只认这几样，不管是哪个入口交来的（各自经自己的入口包一层）。
 */
interface StructureReads {

    /** 和一个区域沾边的物理结构。 */
    List<SableStructures.Structure> structures(ClientLevel level, Player viewer, AABB region);

    /** 结构地块里一格的方块；没加载、结构不在为 null。 */
    BlockState block(ClientLevel level, String id, BlockPos local);

    /** 一个结构的逐格读结果（读一次记两百刻）。 */
    StructureGeometry.Scan scan(ClientLevel level, SableStructures.Structure structure);
}
