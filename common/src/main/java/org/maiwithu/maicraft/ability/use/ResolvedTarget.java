// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * 落实下来的用东西目标：一格方块，或一只实体。
 *
 * <p>实体只记游戏实体编号，不攥着实体对象：实体会走动、会被卸载，每次用到时按编号找回当刻的它，
 * 找不回就是不在了，不会对着一只过期的对象瞄准，也不会认错成旁边同种的另一只。
 *
 * @param cell     方块目标的格子；实体目标取落实时它所在的格（给日志与"在哪里"用）
 * @param entityId 实体目标的游戏实体编号；方块目标为 null
 * @param typeId   方块或实体此刻的类型注册 ID（读现场得到，不是参数里的筛选）
 * @param describe 给日志与结果的一句话
 */
record ResolvedTarget(BlockPos cell, Integer entityId, String typeId, String describe) {

    ResolvedTarget {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(describe, "describe");
    }

    /** 一格方块目标。 */
    static ResolvedTarget block(BlockPos cell, String typeId, String describe) {
        return new ResolvedTarget(cell.immutable(), null, typeId, describe);
    }

    /** 一只实体目标。 */
    static ResolvedTarget entity(int entityId, BlockPos cell, String typeId) {
        return new ResolvedTarget(cell.immutable(), entityId, typeId, typeId + "（实体编号 " + entityId + "）");
    }

    /** 是不是实体目标。 */
    boolean isEntity() {
        return entityId != null;
    }

    /** 换成同一种流体的另一格（空桶点到流动的水，改舀附近的源格）。 */
    ResolvedTarget movedTo(BlockPos other, String why) {
        return new ResolvedTarget(other.immutable(), null, typeId, typeId + " " + other.toShortString() + "（" + why + "）");
    }
}
