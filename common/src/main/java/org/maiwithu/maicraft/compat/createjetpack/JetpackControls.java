// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;

/**
 * 喷气背包的读写接缝：身上的喷气背包此刻什么样（穿着还是在背包里、开没开、悬停没有、气还能飞多久、飞多快），
 * 以及玩家能做的一件原生操作——按一下开关键（和玩家按 G、H 一样）。只翻译，不判断。
 */
public interface JetpackControls {

    /** 两个开关键。 */
    enum Toggle {
        /** 开关飞行（默认 G）。 */
        ACTIVE,
        /** 开关悬停（默认 H）。 */
        HOVER
    }

    /**
     * 身上的一个喷气背包。
     *
     * @param itemId          物品 ID（create_jetpack:jetpack、create_jetpack:netherite_jetpack）
     * @param inventorySlot   在背包第几格；穿在胸甲槽上为 -1
     * @param active          飞行开着
     * @param hovering        悬停开着
     * @param usable          背罐里的气够不够现在就开
     * @param secondsLeft     开着悬停飞还能飞多少秒（按服务器的耗气设置与优先用的那罐算）
     * @param verticalSpeed   上下的速度（格每刻）
     * @param hoverDescend    悬停时每刻往下飘多少（负数）
     * @param chestItemId     胸甲槽此刻穿着什么；空着为空串
     */
    record Pack(String itemId, int inventorySlot, boolean active, boolean hovering, boolean usable, double secondsLeft,
                double verticalSpeed, double hoverDescend, String chestItemId) {

        /** 穿在身上。 */
        public boolean worn() {
            return inventorySlot < 0;
        }
    }

    /** 身上的喷气背包：先看胸甲槽，再看背包；都没有为空。 */
    Optional<Pack> pack(LocalPlayer player);

    /** 按一下开关键：和玩家按一下 G（飞行）或 H（悬停）发出的一样，开着就关、关着就开。 */
    void press(LocalPlayer player, Toggle toggle);
}
