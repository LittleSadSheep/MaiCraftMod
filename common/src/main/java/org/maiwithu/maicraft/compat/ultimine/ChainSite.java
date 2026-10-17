// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ultimine;

import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 连锁挖要读的现场：一批里每一格是什么样、手里的工具、现在第几刻。生产实现读当刻的客户端世界，测试用替身。
 */
interface ChainSite {

    /** 一格此刻的样子；没加载为空。 */
    Optional<Cell> cell(PlayerContext context, BlockPos pos);

    /** 主手拿着的工具。 */
    Tool tool(PlayerContext context);

    /** 客户端第几刻：等 FTB 处理松键、让观众看清轮廓都按刻数。 */
    long tick(PlayerContext context);

    /** 角色本人：问 FTB 准不准连锁要用；不在世界里为 null。 */
    LocalPlayer player(PlayerContext context);

    /**
     * 一格的样子。
     *
     * @param air         空的
     * @param fluid       水、岩浆，或泡着流体的方块
     * @param facility    箱子、熔炉、机器这类带方块实体的：连锁挖不拆
     * @param unbreakable 挖不动（基岩这类）
     * @param harvestable 手上的工具挖得下来（不需要合用工具的方块都算挖得下来）
     */
    record Cell(boolean air, boolean fluid, boolean facility, boolean unbreakable, boolean harvestable) {}

    /**
     * 手里的工具。
     *
     * @param identity      是哪一件：物品与附魔这些，不算耐久（挖的时候耐久一直在掉）
     * @param durabilityLeft 还剩多少耐久；不掉耐久的东西为 Integer.MAX_VALUE
     */
    record Tool(String identity, int durabilityLeft) {}
}
