// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 寻死要做的动作：走过去、踏进危险、跟着怪站着、站进格子、把东西拿到手上、低头点火或倒岩浆。
 * 生产实现组合走到、直接按键、换手与瞄准交互；测试给动作替身。
 */
interface SuicideMoves {

    /** 走到这一格站着（按这次任务的许可动地形）。 */
    Action walkTo(BlockPos stand, Permissions permissions);

    /**
     * 从站位踏进危险，一直不结束：岩浆走进去停住、悬崖迈出去开始往下掉就松开前进、仙人掌一直贴着推。
     * 寻路会绕开岩浆和悬崖，这一步直接按键走，不经寻路。
     */
    Action stepInto(DeathSpot spot);

    /** 跟着这只怪走到 1.5 格内站住，一直不结束；被墙挡住就跳，不打、不举盾。 */
    Action keepNear(int mobId, String mobType, Permissions permissions);

    /** 直接按键走进这一格站到正中（格里已经有火时也照走），到了就结束。 */
    Action standIn(BlockPos cell);

    /** 把这种东西拿到主手的动作；已经在主手时为空。 */
    Optional<Action> hold(String itemId);

    /** 低头对准脚下方块的顶面右键一次：点火或倒岩浆，等游戏确认这一格出现了火或岩浆。 */
    Action makeHazard(WayToDie way, BlockPos cell);
}
