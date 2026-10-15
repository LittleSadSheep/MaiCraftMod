// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
 * @param origin     这条记忆怎么得来的（远望看到 / 到访核实 / 亲手用过）
 * @param recordedAt 什么时候记下的
 */
public record MemoryRecord(MemoryKind kind, WorldPosition position, String blockType,
        List<String> contents, MemoryOrigin origin, Instant recordedAt) {

    /** 产地线索按片认的空间格：64 格见方、32 格高，同一格里同样内容的线索算同一片。 */
    private static final int SITE_AREA_WIDTH = 64;
    private static final int SITE_AREA_HEIGHT = 32;

    /**
     * 两个位置是不是同一格：坐标一样，维度一样或有一边没写。没写维度的位置是"当时所在的维度"——
     * 看见一只箱子时记的位置不带维度，开箱后记的带维度，说的是同一只箱子，不能当成两只。
     */
    public static boolean sameSpot(WorldPosition first, WorldPosition second) {
        return first.x() == second.x() && first.y() == second.y() && first.z() == second.z()
                && (first.dimension() == null || second.dimension() == null
                        || first.dimension().equals(second.dimension()));
    }

    /**
     * 两条记忆说的是不是同一个东西。容器、工作站、机器、临时方块要找回那一格，同种类同一格才算同一个；
     * 产地线索只给大概位置，同样内容、同一维度、落在同一个 64×64×32 空间格里就算同一片——
     * 角色边走边看，同一片树林的中心每次挪一两格，不能挪一下就多记一条"这里有木头"。
     */
    public boolean sameThing(MemoryRecord other) {
        if (kind != other.kind) return false;
        if (kind != MemoryKind.SITE) return sameSpot(position, other.position);
        return Objects.equals(contents, other.contents) && sameArea(position, other.position);
    }

    // 同一个产地空间格：按格向下取整分块，负坐标也落在对的格里；维度的认法与 sameSpot 一样。
    private static boolean sameArea(WorldPosition first, WorldPosition second) {
        return Math.floorDiv(first.x(), SITE_AREA_WIDTH) == Math.floorDiv(second.x(), SITE_AREA_WIDTH)
                && Math.floorDiv(first.y(), SITE_AREA_HEIGHT) == Math.floorDiv(second.y(), SITE_AREA_HEIGHT)
                && Math.floorDiv(first.z(), SITE_AREA_WIDTH) == Math.floorDiv(second.z(), SITE_AREA_WIDTH)
                && (first.dimension() == null || second.dimension() == null
                        || first.dimension().equals(second.dimension()));
    }

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
     * 把一次新的观察合并进这条旧记忆，得到更新后的记忆；同一个东西只留这一条（怎样算同一个见 {@link #sameThing}）。
     *
     * <p>合并规则（触发条件：同一个东西又记了一条）：
     * 记录时刻取新的；来源取更强的（远望到了升级成到访，到访与亲手用过也不会退回去）；
     * 这次确认过的内容覆盖旧的，这次没确认过（contents 为 null）就保留旧内容——
     * 路过又看了一眼不算打开过，不能把当初开箱看到的清单抹掉。
     * 同一片产地落在不同格时，到访核实的落点比远望的大概位置准，来源更强才换成新位置；
     * 同样是远望就留原来的位置，坐标不随角色走动来回跳。
     */
    public MemoryRecord mergedWith(MemoryRecord newer) {
        if (!sameThing(newer)) {
            throw new IllegalArgumentException("只有同一个东西的记忆才能合并");
        }
        WorldPosition spot;
        if (sameSpot(position, newer.position)) {
            // 两条里有一条没写维度时，合并后留下写了维度的那个说法。
            spot = newer.position.dimension() != null ? newer.position : position;
        } else {
            spot = MemoryOrigin.stronger(origin, newer.origin) != origin ? newer.position : position;
        }
        return new MemoryRecord(kind, spot,
                newer.blockType != null ? newer.blockType : blockType,
                newer.contents != null ? newer.contents : contents,
                MemoryOrigin.stronger(origin, newer.origin),
                newer.recordedAt);
    }
}
