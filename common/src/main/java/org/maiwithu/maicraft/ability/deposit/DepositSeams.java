// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Optional;

import net.minecraft.core.BlockPos;

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

    /** 目标对象落在哪：观察编号此刻的位置、记得的地点、角色脚下。 */
    interface FindsPlaces {
        /** 观察编号对应的东西此刻在哪；编号失效给空。 */
        Optional<WorldPosition> seen(String seenId);

        /** 记得的地点在哪；没记过给空。 */
        Optional<WorldPosition> landmark(String name);

        /** 角色脚下那一格；不在世界里为 null。 */
        BlockPos feet();
    }
}
