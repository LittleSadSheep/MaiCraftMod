// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * 落实下来的用东西目标：一格方块，或一只实体。
 *
 * <p>观察编号点名的实体在交互前一刻才取回对象（实体会动）；取回之前先记着它解析出的位置与类型。
 */
final class ResolvedTarget {

    /** 方块目标的格子；实体目标取它的中心所在格，供找不回实体时到近旁再找。 */
    final BlockPos block;
    /** 实体对象；还没取回时为 null。 */
    Entity entity;
    /** 目标的类型注册 ID，进结果与许可判断。 */
    final String typeId;
    /** 给日志与结果的一句话。 */
    final String describe;
    /** 观察编号点名的实体还没取回对象时，记着它的编号。 */
    final String entitySeenId;

    private ResolvedTarget(BlockPos block, Entity entity, String typeId, String describe, String entitySeenId) {
        this.block = block;
        this.entity = entity;
        this.typeId = typeId;
        this.describe = describe;
        this.entitySeenId = entitySeenId;
    }

    /** 一格方块目标。 */
    static ResolvedTarget block(BlockPos at, String typeId, String describe) {
        return new ResolvedTarget(at, null, typeId, describe, null);
    }

    /** 一只实体目标，对象已经在手。 */
    static ResolvedTarget entity(Entity entity, String typeId) {
        return new ResolvedTarget(BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()),
                entity, typeId, typeId + "（编号 " + entity.getId() + "）", null);
    }

    /** 观察编号落实的目标：e 开头是实体（先记位置，对象交互前取回），b 开头是方块或设施。 */
    static ResolvedTarget of(String seenId, ResolvesSeen.Resolved resolved, String blockFilter) {
        BlockPos at = resolved.blockPos();
        if (seenId.startsWith("e")) {
            String typeId = blockFilter != null ? blockFilter : resolved.typeId();
            return new ResolvedTarget(at, null, typeId, resolved.typeId() + " " + at.toShortString(), seenId);
        }
        return new ResolvedTarget(at, null, blockFilter != null ? blockFilter : resolved.typeId(),
                resolved.typeId() + " " + at.toShortString(), null);
    }

    BlockPos block() {
        return block;
    }

    Entity entity() {
        return entity;
    }

    String typeId() {
        return typeId;
    }

    String describe() {
        return describe;
    }

    String entitySeenId() {
        return entitySeenId;
    }
}
