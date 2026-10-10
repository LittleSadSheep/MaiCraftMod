// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import java.util.List;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;

/**
 * 施工引擎的协作服务：启动时把玩家行为模型与接缝实现从这里递进来，测试用替身。
 * 可空的是可选接缝：没接上时相应环节按"这条路没通"如实处理，不冒充做过。
 *
 * @param site       工地读数
 * @param close      靠近（站位与走到）
 * @param placements 放置预测
 * @param clicks     出手
 * @param digs       挖掉一格；没接上时要清的格记"清不了"
 * @param needs      缺的材料去拿；没接上时缺什么就建到哪
 * @param hand       备手
 * @param guards     许可
 * @param ledger     记账
 * @param travel       走到没加载的那一片；没接上时没加载的格记"未知"
 * @param accelerators 联动的挖掘加速（连锁挖）；没有就逐格挖
 * @param dismantlers  联动的拆卸工具（扳手拆机器件）；没有就照原来的挖
 */
public record ConstructionServices(
        ConstructionSeams.ReadsSite site,
        BringsPlayerClose close,
        ConstructionSeams.PlansPlacement placements,
        ConstructionSeams.Clicks clicks,
        DigsBlocks digs,
        ItemNeeds needs,
        ConstructionSeams.HoldsItem hand,
        ConstructionSeams.Guards guards,
        ConstructionSeams.Ledger ledger,
        ConstructionSeams.Travels travel,
        List<BreakAccelerator> accelerators,
        List<DismantleTool> dismantlers) {

    public ConstructionServices {
        accelerators = accelerators == null ? List.of() : List.copyOf(accelerators);
        dismantlers = dismantlers == null ? List.of() : List.copyOf(dismantlers);
        if (site == null) throw new IllegalArgumentException("工地读数不能为空");
        if (close == null) throw new IllegalArgumentException("靠近不能为空");
        if (placements == null) throw new IllegalArgumentException("放置预测不能为空");
        if (clicks == null) throw new IllegalArgumentException("出手不能为空");
        if (hand == null) throw new IllegalArgumentException("备手不能为空");
        if (guards == null) throw new IllegalArgumentException("许可不能为空");
        if (ledger == null) throw new IllegalArgumentException("记账不能为空");
    }

    /** 没有联动的拆方块办法：清障一律逐格挖。 */
    public ConstructionServices(ConstructionSeams.ReadsSite site, BringsPlayerClose close, ConstructionSeams.PlansPlacement placements,
            ConstructionSeams.Clicks clicks, DigsBlocks digs, ItemNeeds needs, ConstructionSeams.HoldsItem hand,
            ConstructionSeams.Guards guards, ConstructionSeams.Ledger ledger, ConstructionSeams.Travels travel) {
        this(site, close, placements, clicks, digs, needs, hand, guards, ledger, travel, List.of(), List.of());
    }
}
