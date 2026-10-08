// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

/**
 * 读物品注册表的只读接缝：一个物品 ID 在不在这个游戏里、一个标签下有没有注册物品。
 * 数据来自游戏自己的注册表（含模组注册），实现在游戏接口层；拿东西的参数校验用它，
 * 不用进世界、不安排角色动作就能把写岔了的物品 ID 拦下来。
 */
public interface ReadsItemRegistry {

    /** 这个物品 ID 注册过（含模组）；AIR 与写岔了都算没有。 */
    boolean itemExists(String itemId);

    /** 这个标签下至少挂着一件注册物品；一个都没挂的标签要不了东西。 */
    boolean tagHasItems(String tagId);
}
