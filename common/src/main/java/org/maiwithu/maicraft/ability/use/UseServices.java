// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;

/**
 * 用东西能力的协作服务：启动时创建并登记把玩家行为模型与接缝实现从构造函数递进来。
 *
 * <p>可空的字段是可选接缝：交互的顺手捡起、写告示牌、世界记忆，没接上时相应环节按接缝处理，
 * 不冒充做过；靠近、交互、手上准备这三样缺了能力做不了事，不许为 null。
 *
 * @param interactions  交互动作入口（瞄准、提交、逐刻确认）
 * @param close         靠近动作的组装
 * @param hand          手上拿什么的准备
 * @param seen          观察编号解析；没接上时 seen 目标按"不在了"处理
 * @param search        附近搜索；没接上时按类型找最近的目标做不了
 * @param refusal       游戏的拒绝与不作为；没接上时没生效一律按"这样用没有效果"说
 * @param signEditors   告示牌编辑界面；写告示牌要用
 * @param drops         顺手捡起；没接上时掉出的东西留在地上
 * @param travel        走到没加载的坐标；没接上时按到不了说
 * @param menus         打开界面的分侧读数；没接上时打开的容器界面列不出内容
 * @param memory        世界记忆；接着才把看过的容器记下来
 */
record UseServices(
        Interactions interactions,
        BringsPlayerClose close,
        UseSeams.PreparesHand hand,
        ResolvesSeen seen,
        SearchesNearby search,
        UseSeams.ReadsGameRefusal refusal,
        UseSeams.ReadsSignEditor signEditors,
        UseSeams.GathersDrops drops,
        UseSeams.TravelsTo travel,
        MenuContent menus,
        WorldMemory memory) {

    UseServices {
        if (interactions == null) throw new IllegalArgumentException("交互动作入口不能为空");
        if (close == null) throw new IllegalArgumentException("靠近的组装不能为空");
        if (hand == null) throw new IllegalArgumentException("手上准备不能为空");
    }
}
