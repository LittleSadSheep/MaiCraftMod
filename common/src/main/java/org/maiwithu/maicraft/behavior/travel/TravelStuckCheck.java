// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 出行走不动时的收尾判断：长时间没有真实进展，读目的地、许可与脚下位置，
 * 给出带现场的问题——已经走到那一柱只是高度够不着，还是路上被挡住走不下去。
 * 纯函数，离线测试直接喂事实。
 *
 * <p>走到停在崖沿这类场景空转满时限时，收尾要报 UNREACHABLE 并带停在哪、
 * 剩多远、许可动不动土与能继续的建议，而不是一句通用的"原地打转"。
 */
public final class TravelStuckCheck {

    /** 落格取整会带来半格上下的差：判断"已经在目的地那一列"时放宽这一格。 */
    private static final double COLUMN_SLACK = 1.0;

    private TravelStuckCheck() {}

    /**
     * 判这次空转卡在哪；没有身体位置（还在算路）时给 null，收尾沿用进度跟踪的通用说法。
     *
     * @param destination  解析好的目的地（带高度核实标记与容差）
     * @param permit       这次走到被允许动多少地形
     * @param feet         空转时身体所在的方块格
     * @param lastProgress 进度跟踪记下的最后一次真实进展
     * @param idleTicks    自那次进展起又推进了多少刻
     */
    public static Problem judge(TravelDestination destination, TerrainPermit permit, BlockPos feet,
            String lastProgress, long idleTicks) {
        if (destination == null || feet == null) {
            return null;
        }
        // 只给 x/z 的目的地按脚下这层量：卡住的交代里不出现拿占位 y=0 算出来的高低差。
        RemainingDistance remaining = RemainingDistance.measure(feet, destination.aimFrom(feet));
        if (destination.heightConfirmed()
                && remaining.horizontalBlocks() <= destination.radius() + COLUMN_SLACK
                && Math.abs(remaining.verticalBlocks()) > destination.radius() + COLUMN_SLACK) {
            return heightUnreachable(destination, feet, remaining.verticalBlocks(), idleTicks);
        }
        return routeBlocked(destination, permit, feet, remaining, lastProgress, idleTicks);
    }

    /** 高度不可达：那一列已经走到了，目标高度差得远且长时间没有进展——把高度事实写清楚。 */
    private static Problem heightUnreachable(TravelDestination destination, BlockPos feet,
            int verticalGap, long idleTicks) {
        String gap = verticalGap > 0 ? "高 " + verticalGap : "低 " + (-verticalGap);
        return Problem.of(Problem.Kind.UNREACHABLE,
                "目的地要站在 " + destination.describe() + "：已经走到这一列（脚下 y=" + feet.getY() + "），"
                        + "但目标高度比脚下" + gap + " 格，推进了 " + idleTicks + " 刻也找不到能站的格子",
                "这一列的地面在脚下附近（y≈" + feet.getY() + "）：把目标高度改成地面高度，或只给 x/z 再下达");
    }

    /** 路上被挡住走不下去：带上停在哪、剩多远与许可事实，建议按能不能动土分开给。 */
    private static Problem routeBlocked(TravelDestination destination, TerrainPermit permit, BlockPos feet,
            RemainingDistance remaining, String lastProgress, long idleTicks) {
        String permitPhrase = switch (permit.changes()) {
            case NONE -> "这次 change_blocks=none，不能挖垫开路";
            case TEMPORARY -> "这次 change_blocks=temporary，只能垫不能挖";
            case NATURAL -> "这次 change_blocks=natural，允许挖垫";
            case ANY -> "这次 change_blocks=any，不设方块限制";
        };
        // 没有动土许可时，"许可动土就能过"是已验证的路；许可给了还走不过去，就不许诺放宽许可有用。
        String suggestion = permit.mayChangeTerrain()
                ? "换一条路线，或先绕开挡路的地形再试"
                : "许可动土（change_blocks 给 natural 或以上）再走同一路线，或带些泥土、圆石这类垫脚料";
        return Problem.of(Problem.Kind.UNREACHABLE,
                "走到长时间没有新进展：身体停在 (" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ")，"
                        + "离目的地还剩 " + remaining.describe() + "，" + permitPhrase
                        + "；自「" + lastProgress + "」之后又推进了 " + idleTicks + " 刻",
                suggestion);
    }
}
