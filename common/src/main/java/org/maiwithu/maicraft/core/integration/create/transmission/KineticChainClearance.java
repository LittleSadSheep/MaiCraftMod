// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import net.minecraft.core.BlockPos;

/** 链条端点必须已加载；新传动轮的放置约束由方块规划核对，不把显示链条当成待清空的方块隧道。 */
final class KineticChainClearance {
    private KineticChainClearance() {}

    static boolean clear(KineticGeometryWork work, List<BlockPos> wheels) {
        // 中间的墙、水和已有机器不被挂链操作修改；原生长度、坡度和连接数继续由各自的实际接口核对。
        return wheels.stream().allMatch(work.terrain::loaded);
    }
}
