// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.interaction.SignEditor;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 用东西能力的执行接缝。这些窄接口都由启动时创建并登记接上；接缝没接（传 null）时，
 * 能力按"这条路还没通"处理，不假装做过。
 */
final class UseSeams {

    private UseSeams() {}

    /** 手上拿什么：选到主手，或腾出空手；身上没有要拿的东西时按缺物品失败。缺的自己去拿是拿到物品模型的事。 */
    interface PreparesHand {
        /** 把给定物品选到主手；item 为 null 表示要空手。做不成时给问题（NEED_ITEM 或腾不出手）。 */
        Optional<Problem> hold(String item);
    }

    /** 告示牌编辑界面：右键提交后界面的读取入口；界面没开着给空。 */
    interface ReadsSignEditor {
        Optional<SignEditor> current();
    }

    /** 顺手捡起：交互掉在地上的东西（剪下来的羊毛），附近几格限时捡；没有可捡的给空。 */
    interface GathersDrops {
        Optional<Action> nearby();
    }

    /** 游戏的拒绝与不作为：最近的动作栏提示语，以及服务端说这次右键没被任何东西处理。 */
    interface ReadsGameRefusal {
        Optional<String> latestMessage();
        boolean interactionWasUnhandled();
    }

    /** 走到没加载的坐标（出行轨）：给一个能逐刻推进的走到动作；给不出（没接上）时为空。 */
    interface TravelsTo {
        Optional<Action> position(WorldPosition position);
    }
}
