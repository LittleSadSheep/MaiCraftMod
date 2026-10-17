// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow.spi;

import java.util.Optional;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 同乘：跟着的人坐上一辆载具时，这辆上有没有给我的空座、怎么坐上去。
 *
 * <p>原版的船与骆驼由跟随自己认；机械动力的列车这类模组载具由联动实现。跟随挨个问，第一个给出动作的就用它。
 * 坐没坐上以角色真的成了这辆的乘客为准；上不去（座满了、走不过去、点了没反应）由动作以问题失败，写明原因。
 */
public interface RideAlong {

    /**
     * 他坐着的这辆上有给我的空座：给一个"走过去坐上"的动作；不是这种载具、或没有空座时为空。
     *
     * @param vehicle     他坐着的那辆（他直接坐着的那个实体，列车是那节车厢）
     * @param permissions 这次跟随的许可：走过去时动不动地形按它
     */
    Optional<Action> board(PlayerContext context, Entity vehicle, Permissions permissions);
}
