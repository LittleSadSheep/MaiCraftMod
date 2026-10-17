// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 存东西能力的执行接缝。任务只经这些窄接口拿动作、读位置，游戏对象留在生产实现里，任务能离线测试。
 * 可选的接缝没接（null）时相应环节按"这条路还没通"处理。
 */
final class DepositSeams {

    private DepositSeams() {}

    /** 挖开压住容器盖子的方块：走过去、挖掉、把掉出来的捡进包；挖不了时给空。 */
    interface DigsLid {
        Optional<Action> dig(BlockPos lidCell, Permissions permissions);
    }

    /** 副手：攥着什么，以及把它放回背包的动作。整理背包时副手的东西也一起存。 */
    interface ClearsOffhand {
        /** 副手此刻拿着的那一堆；空手为空。 */
        Optional<BackpackStack> held();

        /** 把副手那一堆换进背包一个空格的动作；背包没空格时给空。 */
        Optional<Action> intoBackpack();
    }

    /** 目标对象落在哪：观察编号此刻的位置、记得的地点、看得见的告示牌、角色脚下。 */
    interface FindsPlaces {
        /** 观察编号对应的东西此刻在哪；编号失效给空。 */
        Optional<WorldPosition> seen(String seenId);

        /** 记得的地点在哪；没记过给空。 */
        Optional<WorldPosition> landmark(String name);

        /** 此刻看得见的告示牌：地点名没记过时按牌子上的字认地方；没接上场景时为空。 */
        default List<SceneSign> signs() {
            return List.of();
        }

        /** 角色脚下那一格；不在世界里为 null。 */
        BlockPos feet();
    }
}
