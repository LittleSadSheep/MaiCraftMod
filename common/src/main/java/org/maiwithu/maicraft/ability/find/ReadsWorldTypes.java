// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

/**
 * 读游戏类型目录的只读接缝：一个方块或实体的 ID（或标签）在这个游戏里有没有注册。
 * 数据来自游戏自己的注册表（含模组注册）；找东西的参数校验用它，写岔了的 ID
 * 开局一次报全，不进世界才碰壁。实现在本包的读端，测试用替身。
 */
public interface ReadsWorldTypes {

    /** 这个方块 ID 注册过，或这个标签下至少挂着一件注册方块。 */
    boolean blockTypeExists(String blockIdOrTag);

    /** 这个实体类型 ID 注册过（含模组）。 */
    boolean entityTypeExists(String entityTypeId);
}
