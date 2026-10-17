// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.game.menu.ScreenOpening;

/**
 * 角色此刻开着的界面：是什么界面、哪一种、认不认得出两侧、容器那一侧有什么、光标上拿着什么、谁开的。
 * 只是看到的事实，不点、不关；角色那一侧就是背包，看背包用观察自己，这里不重复。
 *
 * @param kind           哪一种界面
 * @param menuType       菜单类型的注册 ID（不是方块 ID）；角色自己的背包与不是容器的界面为 null
 * @param title          画面上的标题（箱子改过名就是改的名）；只有菜单对象、画面上没有界面时为 null
 * @param unrecognized   认不出两侧怎么分的原因；认得出、或不是容器界面时为 null
 * @param contents       容器那一侧的东西；读不出来时为 null，原因在 contentsUnread
 * @param contentsUnread 为什么没列容器那一侧（认不出、刚打开还没同步、不是容器界面）；列了时为 null
 * @param cursor         光标上拿着的东西；空着为 null
 * @param openedBy       谁开的
 */
public record OpenScreen(Kind kind, String menuType, String title, String unrecognized, Contents contents,
                         String contentsUnread, ItemCount cursor, ScreenOpening.OpenedBy openedBy) {

    public OpenScreen {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(openedBy, "openedBy");
        if ((contents == null) == (contentsUnread == null)) {
            throw new IllegalArgumentException("容器那一侧要么列出来，要么写明为什么没列");
        }
    }

    /** 哪一种界面：按菜单类型认，原版之外的按模组界面算。 */
    public enum Kind {
        /** 只存东西的容器：箱子、木桶、末影箱、潜影盒、发射器、投掷器、漏斗。 */
        STORAGE,
        /** 工作台。 */
        CRAFTING_TABLE,
        /** 熔炉一族：熔炉、高炉、烟熏炉。 */
        FURNACE,
        /** 切石机。 */
        STONECUTTER,
        /** 附魔台。 */
        ENCHANTING_TABLE,
        /** 和村民或流浪商人交易。 */
        TRADE,
        /** 别的原版界面：铁砧、砂轮、酿造台、信标、织布机……认得出是原版，但不替你读两侧。 */
        OTHER_VANILLA,
        /** 模组的界面；认不认得出两侧看有没有联动模组的证明。 */
        MOD,
        /** 角色自己的背包界面。 */
        INVENTORY,
        /** 不是容器界面：聊天框、暂停菜单、告示牌编辑、死亡界面这类。 */
        OTHER_SCREEN
    }

    /** 一样东西、几件：按物品合并，不按格子列。 */
    public record ItemCount(String item, int count) {
        public ItemCount {
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * 容器那一侧的东西。
     *
     * @param items      按物品合并、按界面里的先后排；数量多也全列
     * @param slots      容器那一侧一共几格
     * @param emptySlots 其中空着几格
     */
    public record Contents(List<ItemCount> items, int slots, int emptySlots) {
        public Contents {
            items = List.copyOf(items);
        }
    }
}
