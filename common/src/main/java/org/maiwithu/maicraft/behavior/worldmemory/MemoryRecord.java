// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 一条记忆记录：角色记得的一件世界事实，带种类、位置、内容、记录时刻与来源。
 *
 * <p>这是记忆不是事实：记录时刻越久越可能过时，用之前必须到现场核对。
 * 位置和方块类型是当时亲眼确认过的；内容只在真正打开看过之后才有。
 *
 * @param kind       记的是哪类东西（容器、工作站、产地）
 * @param position   当时它在哪里
 * @param blockType  方块类型，例如 minecraft:chest；产地线索没有对应方块，为 null
 * @param contents   打开时看到的东西（物品 ID 列表）；null 表示从没确认过里面有什么，
 *                   空列表表示确认过就是空的。两者不能混：没开过的箱子不等于空箱子
 * @param origin     这条记忆怎么得来的（亲眼看到 / 亲手用过）
 * @param recordedAt 什么时候记下的
 */
public record MemoryRecord(MemoryKind kind, WorldPosition position, String blockType,
        List<String> contents, MemoryOrigin origin, Instant recordedAt) {

    public MemoryRecord {
        if (kind == null || position == null || origin == null || recordedAt == null) {
            throw new IllegalArgumentException("记忆记录缺不了种类、位置、来源和记录时刻");
        }
        contents = contents == null ? null : List.copyOf(contents);
    }

    /** 箱子里有什么，开了才算数：开过（或确认过是空的）才返回真。 */
    public boolean openedBefore() {
        return contents != null;
    }

    /**
     * 记忆会变旧：距离现在超过给定的时限就算旧记忆。
     *
     * <p>旧记忆不是错的记忆，只是核对之前不能全信；查询方拿它安排"先去哪"，到场后仍要核对。
     */
    public boolean olderThan(Instant now, Duration limit) {
        return Duration.between(recordedAt, now).compareTo(limit) > 0;
    }

    /**
     * 把一次新的观察合并进这条旧记忆，得到更新后的记忆；同一位置同一类东西只留这一条。
     *
     * <p>合并规则（触发条件：同一个位置、同一个种类又记了一条）：
     * 记录时刻取新的；来源取更强的（亲手用过不会退回亲眼看到）；
     * 这次确认过的内容覆盖旧的，这次没确认过（contents 为 null）就保留旧内容——
     * 路过又看了一眼不算打开过，不能把当初开箱看到的清单抹掉。
     */
    public MemoryRecord mergedWith(MemoryRecord newer) {
        if (kind != newer.kind || !position.equals(newer.position)) {
            throw new IllegalArgumentException("只有同一个位置、同一个种类的记忆才能合并");
        }
        return new MemoryRecord(kind, position,
                newer.blockType != null ? newer.blockType : blockType,
                newer.contents != null ? newer.contents : contents,
                MemoryOrigin.stronger(origin, newer.origin),
                newer.recordedAt);
    }
}
