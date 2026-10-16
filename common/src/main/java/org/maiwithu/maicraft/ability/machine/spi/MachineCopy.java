// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 读现场时一种机器把自己认领的格抄成机器蓝图的写法。传送带、两台链条输送机之间的链、机械臂、ME 线缆与线缆上的部件
 * 都是用模组自己的方式装出来的，照抄方块建不回来（传送带方块没有物品可放），要写成安装段与部件；
 * 照抄方块与属性就建得回来的格用不着这一份。
 *
 * <p>交来的格由这一份全权决定怎么写：写进 {@code blocks} 的照给的状态写成逐格方块（传送带两头写成轴），
 * 不在 {@code blocks} 里的不写进逐格清单——它们由安装段放下或本来就是安装段装出来的样子。
 *
 * @param blocks        要写成逐格方块的格与写进蓝图的方块状态，世界坐标
 * @param installations 抄出来的安装段，世界坐标，按施工时装的先后排
 * @param parts         抄出来的部件，世界坐标
 * @param notes         抄不全、照着建会走样的地方，一句一条
 */
public record MachineCopy(Map<BlockPos, BlockState> blocks, List<Installation> installations, List<PartCell> parts,
                          List<String> notes) {

    public MachineCopy {
        Map<BlockPos, BlockState> copied = new LinkedHashMap<>();
        blocks.forEach((pos, state) -> copied.put(pos.immutable(), state));
        blocks = Collections.unmodifiableMap(copied);
        installations = List.copyOf(installations);
        parts = List.copyOf(parts);
        notes = List.copyOf(notes);
    }
}
