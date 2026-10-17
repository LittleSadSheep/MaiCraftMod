// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 途中的一条发现：跑图路上看见或到过的东西，进探索结果也进世界记忆。
 *
 * @param kind     发现的种类（群系、地形要素、结构线索、方块留意、实体留意）
 * @param targetId 是什么：群系、方块、实体或结构的注册 ID，地形要素是它的名字（lava_pool）
 * @param position 在哪（带维度）
 * @param origin   到访还是远望：身体到过并核实，或只看得见没到跟前（一律过视线闸）
 */
public record ExploreFinding(Kind kind, String targetId, WorldPosition position, MemoryOrigin origin) {

    /** 发现的种类。 */
    public enum Kind {
        /** 群系：走到里面或望见的一片。 */
        BIOME,
        /** 地形要素：岩浆湖这类成片的地貌。 */
        TERRAIN,
        /** 结构线索：证据组合符合，只按线索记，不冒充到过。 */
        STRUCTURE_CLUE,
        /** 方块留意：留意清单上的方块（或标签）露头了。 */
        BLOCK,
        /** 实体留意：留意清单上的实体类型看得见了。 */
        ENTITY
    }

    /** 同一条发现的合并键：群系按 ID 合并（群系是一片，望见几处也是同一片）；
     * 其余按"什么 + 在哪"合并，两处岩浆湖、两只箱子是两条发现。 */
    public String mergeKey() {
        return kind == Kind.BIOME
                ? kind + ":" + targetId
                : kind + ":" + targetId + ":" + position.x() + "," + position.y() + "," + position.z();
    }
}
