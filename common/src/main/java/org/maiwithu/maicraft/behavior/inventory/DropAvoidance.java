// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 丢弃落点避让：本会话内记住自己丢过东西的落点，寻路绕开，免得走路时把自己丢出去的东西捡回来。
 *
 * <p>原地丢出的物品约两秒拾取冷却后就能被自己捡回；抛得再远，走过落点也一样。
 * 登记的是方块格，半径内的相邻格也算踩进去——掉落实体会被吸向靠近的角色。
 * 只在会话内存着，换世界即清空；寻路查询经 {@link #shouldAvoid} 把这些格当避让格。
 */
public final class DropAvoidance {

    /** 一个落点周围几格内都算"踩进丢弃堆"：实体吸附有半格以上的余量，宁可多让一格。 */
    public static final int AVOID_RADIUS = 2;

    /** 每个落点记多少刻：拾取冷却只有两秒，半天没人捡的丢弃堆不再是"自己的丢弃堆"。 */
    public static final long KEEP_TICKS = 20L * 60 * 10;

    private final List<DroppedAt> landings = new ArrayList<>();

    /** 登记一个丢弃落点；同一格重复丢不重复记。 */
    public void register(WorldPosition position, long gameTick) {
        for (DroppedAt landing : landings) {
            if (landing.position().equals(position)) {
                landings.remove(landing);
                break;
            }
        }
        landings.add(new DroppedAt(position, gameTick));
    }

    /** 这一格是不是最近自己丢过东西的落点附近；过期或不同维度的落点不算。 */
    public boolean shouldAvoid(WorldPosition position, long gameTick) {
        landings.removeIf(landing -> gameTick - landing.tick() > KEEP_TICKS);
        for (DroppedAt landing : landings) {
            if (!sameDimension(landing.position(), position)) continue;
            if (Math.abs(landing.position().x() - position.x()) <= AVOID_RADIUS
                    && Math.abs(landing.position().y() - position.y()) <= AVOID_RADIUS
                    && Math.abs(landing.position().z() - position.z()) <= AVOID_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** 目前登记的全部落点，给寻路查询批量使用。 */
    public List<WorldPosition> landings() {
        return List.copyOf(landings.stream().map(DroppedAt::position).toList());
    }

    private static boolean sameDimension(WorldPosition a, WorldPosition b) {
        return a.dimension() == null || b.dimension() == null || a.dimension().equals(b.dimension());
    }

    private record DroppedAt(WorldPosition position, long tick) {}
}
