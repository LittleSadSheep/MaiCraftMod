// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 已知容器：角色记得的一个箱子或别的容器——在哪、叫什么、是什么方块。
 *
 * <p>记忆可能过时，真正开箱存取时以现场为准；这里只是"记得有这么个地方"。
 * 存完记进世界记忆时位置带维度、种类按这里的方块类型记。
 *
 * @param name        容器的名字或地标名，写进结果让 LLM 看得懂，例如"家门口的箱子"
 * @param at          容器方块所在的格子（带维度）
 * @param blockTypeId 容器方块的注册 ID，例如 minecraft:chest
 */
public record KnownContainer(String name, WorldPosition at, String blockTypeId) {

    public KnownContainer {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("容器的名字不能为空");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(blockTypeId, "blockTypeId");
    }

    /** 容器方块那一格：走过去、点开都对着它。 */
    public BlockPos cell() {
        return new BlockPos(at.x(), at.y(), at.z());
    }
}
