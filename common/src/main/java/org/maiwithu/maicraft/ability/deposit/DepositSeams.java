// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 存东西能力的执行接缝。任务只经这些窄接口拿动作、读位置，游戏对象留在生产实现里，任务能离线测试。
 * 可选的接缝没接（null）时相应环节按"这条路还没通"处理。
 */
final class DepositSeams {

    private DepositSeams() {}

    /** 打开一只容器：走过去、点开、等内容同步完；之后的搬运与关闭经它交出的那一份界面。 */
    interface OpensMenus {
        MenuOpening open(BlockPos at, Permissions permissions);
    }

    /** 挖开压住容器盖子的方块；挖不了时给空。 */
    interface DigsLid {
        Optional<Action> dig(BlockPos lidCell);
    }

    /** 背包物品标签判断：一个物品 ID 在不在一个标签里；接缝没接上时永远为假。 */
    interface ReadsItemTags {
        boolean taggedIn(String itemId, String tagId);
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
