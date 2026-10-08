// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.inventory.SpotsContainers;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;

/**
 * 存东西能力的协作服务：启动时创建并登记从构造函数递进来。
 *
 * <p>找容器、靠近、交互、界面读数缺一样都存不进去，不许为 null；
 * 挖盖子、整堆快速移动、标签判断、世界记忆是可选接缝，没接上时按接缝处理。
 *
 * @param spots      找容器的接缝（现场扫描与世界记忆合并）
 * @param close      靠近的组装
 * @param interactions 交互动作入口（点开容器）
 * @param menus      打开界面的分侧读数
 * @param quickMoves 界面里的整堆快速移动
 * @param digs       挖开压住盖子的天然方块
 * @param tags       物品标签判断
 * @param memory     世界记忆；存完把容器内容记下来
 */
record DepositServices(
        SpotsContainers spots,
        BringsPlayerClose close,
        Interactions interactions,
        MenuContent menus,
        DepositSeams.QuickMoves quickMoves,
        DepositSeams.DigsLid digs,
        DepositSeams.ReadsItemTags tags,
        WorldMemory memory) {

    DepositServices {
        if (spots == null) throw new IllegalArgumentException("找容器的接缝不能为空");
        if (close == null) throw new IllegalArgumentException("靠近的组装不能为空");
        if (interactions == null) throw new IllegalArgumentException("交互动作入口不能为空");
        if (menus == null) throw new IllegalArgumentException("界面读数不能为空");
        if (quickMoves == null) throw new IllegalArgumentException("整堆快速移动不能为空");
    }
}
