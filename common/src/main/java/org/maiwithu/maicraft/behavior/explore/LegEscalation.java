// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.List;

/**
 * 换向升级：读最近走行段的成败，给出继续、换出发点、换方位还是受阻的纯判断。
 *
 * <p>触发看最近几段的观察窗，不看严格连续——偶发到达清零连续失败，会掩盖出发点
 * 地形本身不可行。先换出发点（垂直于当前方位横移，沿崖壁、水线这类条带地形横移
 * 比迎着同一面障碍换角度更容易找到出口），再顺时针轮换扇形（八个方位试遍）；
 * 全向跑图没有可轮换的方位，只剩换出发点。都试过才承认受阻。
 */
public final class LegEscalation {

    /** 观察窗：最近这么多个走行段（成败都算）。 */
    public static final int WINDOW = 5;
    /** 窗内失败满这个数就升级。 */
    public static final int FAILURES_TO_ESCALATE = 4;
    /** 换出发点至多几次。 */
    public static final int MAX_ORIGIN_SHIFTS = 2;
    /** 扇形顺时针 45° 轮换至多几次（八个方位试遍）。 */
    public static final int MAX_ROTATIONS = 7;
    /** 换出发点横移多远（格）。 */
    public static final int ORIGIN_SHIFT_DISTANCE = 32;

    private LegEscalation() {}

    /** 升级的去向。 */
    public enum Level {
        /** 继续按现在的扇形与航点走。 */
        CARRY_ON,
        /** 换出发点：垂直于当前方位 ±90°、约 32 格。 */
        SHIFT_ORIGIN,
        /** 扇形顺时针轮换 45°。 */
        ROTATE_SECTOR,
        /** 受阻：换过出发点、轮换过方位，还是走不出去。 */
        BLOCKED
    }

    /**
     * 读最近走行段的成败给出升级去向。
     *
     * @param recentOutcomes 最近走行段的成败，先走的在前（true 成功）
     * @param rotatable      扇形有没有可轮换的方位（全向跑图没有）
     * @param originShifts   已经换过几次出发点
     * @param rotations      已经轮换过几次方位
     */
    public static Level decide(List<Boolean> recentOutcomes, boolean rotatable,
            int originShifts, int rotations) {
        int window = Math.min(WINDOW, recentOutcomes.size());
        if (window == 0) {
            return Level.CARRY_ON;
        }
        long failures = recentOutcomes.subList(recentOutcomes.size() - window, recentOutcomes.size())
                .stream().filter(succeeded -> !succeeded).count();
        if (failures < FAILURES_TO_ESCALATE) {
            return Level.CARRY_ON;
        }
        if (originShifts < MAX_ORIGIN_SHIFTS) {
            return Level.SHIFT_ORIGIN;
        }
        if (rotatable && rotations < MAX_ROTATIONS) {
            return Level.ROTATE_SECTOR;
        }
        return Level.BLOCKED;
    }

    /**
     * 换出发点这一段自身失败时直接走下一级：不在坏出发点周围再烧一整窗。
     * 下一级与 {@link #decide} 的后半段同一套：还有换出发点的份额就再换一次，
     * 否则轮换方位，都没有就受阻。
     */
    public static Level nextAfterFailedShift(boolean rotatable, int originShifts, int rotations) {
        if (originShifts < MAX_ORIGIN_SHIFTS) {
            return Level.SHIFT_ORIGIN;
        }
        if (rotatable && rotations < MAX_ROTATIONS) {
            return Level.ROTATE_SECTOR;
        }
        return Level.BLOCKED;
    }
}
