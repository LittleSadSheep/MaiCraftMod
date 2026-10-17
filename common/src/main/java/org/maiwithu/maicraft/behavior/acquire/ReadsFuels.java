// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

/**
 * 读燃料表的只读接缝：一件东西能烧多久。数据按原版燃料表（含模组注册的燃料），
 * 实现在游戏接口层；烧几件东西要备多少燃料，按这里的数值算。
 */
public interface ReadsFuels {

    /** 这件东西燃烧的总刻数；烧不起来的东西返回 0。原版一块煤烧 1600 刻。 */
    int burnTicks(String itemId);
}
