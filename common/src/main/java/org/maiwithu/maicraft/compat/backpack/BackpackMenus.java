// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.util.List;
import java.util.Optional;

import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 精妙背包的界面读写接缝：点开的界面是不是背包的、主存储装着什么、请游戏开包。
 * 只用 Minecraft 与 Java 的类型描述，不出现模组的类；真正看模组菜单、发模组打开请求的
 * 读写端在 NeoForge 模块里，测试用替身。
 */
public interface BackpackMenus {

    /** 点开的界面是不是精妙背包的（本人顶层背包的那一份）。 */
    boolean isBackpackMenu(AbstractContainerMenu menu);

    /**
     * 主存储的槽位号（升级槽与幽灵槽不算货）。
     * 主存储大到数不过来（带无限格升级的读数对不上）时给空，调用方按"未证实"处理，不装作能取。
     */
    Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu);

    /** 角色背包那一侧的槽位号（取出来的货往那里放）。 */
    List<Integer> playerSlotIds(AbstractContainerMenu menu);

    /** 请游戏打开随身背包：模组自己的打开请求，对应玩家按 B 键的那一下，找身上第一只背包。 */
    void openCarried();

    /**
     * 请游戏打开角色背包里这一格的那只随身背包（格号按原版背包：主背包 0..35、胸甲 38、副手 40），
     * 对应玩家在背包界面里对着那只包按 B 键；别的位置（饰品栏）按不了格，退回找第一只。
     */
    void open(int inventorySlot);
}
