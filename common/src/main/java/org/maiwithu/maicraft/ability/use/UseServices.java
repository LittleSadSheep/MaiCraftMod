// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;

/**
 * 用东西能力的协作服务：启动时创建并登记把玩家行为模型与接缝实现从构造函数递进来。
 *
 * <p>可空的字段是可选接缝：没接上时相应环节按接缝缺失如实处理，不冒充做过；
 * 读世界、组装交互、靠近、手上准备这四样缺了能力做不了事，不许为 null。
 *
 * @param world        这一刻的世界事实
 * @param interactions 交互的组装（冻结现场、按手势取确认条件）
 * @param close        靠近动作的组装
 * @param hand         手上拿什么的准备
 * @param needs        身上没有要用的东西时去拿一件；没接上时按缺物品结束
 * @param seen         观察编号解析；没接上时 seen 目标按"不在了"处理
 * @param search       附近搜索；没接上时按类型找最近的目标做不了
 * @param refusal      动作栏的提示语；没接上时没生效一律按"这样用没有效果"说
 * @param signEditors  告示牌编辑界面；写告示牌要用
 * @param drops        顺手捡起；没接上时掉出的东西留在地上
 * @param travel       走到没加载的坐标；没接上时按到不了说
 * @param menus        看看点开的界面；没接上时点开的界面列不出内容，也由它关上
 * @param memory       世界记忆；记得的地点从这里查，看过的容器记进去
 */
record UseServices(
        UseSeams.ReadsWorld world,
        UseSeams.BuildsInteraction interactions,
        BringsPlayerClose close,
        UseSeams.PreparesHand hand,
        ItemNeeds needs,
        ResolvesSeen seen,
        SearchesNearby search,
        UseSeams.ReadsGameRefusal refusal,
        UseSeams.ReadsSignEditor signEditors,
        PicksUpDrops drops,
        UseSeams.TravelsTo travel,
        UseSeams.LooksInMenus menus,
        WorldMemory memory) {

    UseServices {
        if (world == null) throw new IllegalArgumentException("世界读数不能为空");
        if (interactions == null) throw new IllegalArgumentException("交互的组装不能为空");
        if (close == null) throw new IllegalArgumentException("靠近的组装不能为空");
        if (hand == null) throw new IllegalArgumentException("手上准备不能为空");
    }
}
