// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

/**
 * 已知容器：角色记得的一个箱子或别的容器——在哪、叫什么。
 *
 * <p>记忆可能过时，真正开箱存取时以现场为准；这里只是"记得有这么个地方"。
 *
 * @param name 容器的名字或地标名，写进结果让 LLM 看得懂，例如"家门口的箱子"
 * @param x    容器方块的横坐标
 * @param y    容器方块的纵坐标
 * @param z    容器方块的竖坐标
 */
public record KnownContainer(String name, double x, double y, double z) {

    public KnownContainer {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("容器的名字不能为空");
    }
}
