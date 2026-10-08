/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记录寻路计算中因坠落风险被拒绝的下降。被拒的下降不会进入任何路线，执行侧无法观察，
 * 这份日志让接近回执能回答"这条路线避开了哪些会摔的深落"；"预测无伤、实际坠伤"类缺口
 * 的实机排查也以这里的记录为对照起点。
 */
public final class DescentAdmissionLog {

    /** 样本上限：A* 会反复评估同一批候选下降，日志只保留判定依据，不保留全量。 */
    private static final int MAX_SAMPLES = 8;
    /** 去重柱上限：超过后新落柱不再记样本，总数计数继续累计。 */
    private static final int MAX_TRACKED_COLUMNS = 256;

    private static final List<String> SAMPLES = new ArrayList<>();
    private static final LongOpenHashSet SEEN_COLUMNS = new LongOpenHashSet();
    private static int rejected;

    private DescentAdmissionLog() {}

    /** 一次寻路请求开始前清空；同一时刻只有一个导航持有身体，全局单例不区分请求方。 */
    public static synchronized void beginSearch() {
        SAMPLES.clear();
        SEEN_COLUMNS.clear();
        rejected = 0;
    }

    /**
     * 记录一次被拒的下降；搜索工作线程调用。同一落柱只保留一条样本，
     * 样本或去重空间满后仅累计总数。
     */
    public static synchronized void rejected(long destPacked, int fallHeight, String reason) {
        rejected++;
        if (SAMPLES.size() >= MAX_SAMPLES || SEEN_COLUMNS.size() >= MAX_TRACKED_COLUMNS) return;
        if (!SEEN_COLUMNS.add(destPacked)) return;
        BlockPos dest = BlockPos.of(destPacked);
        SAMPLES.add(dest.getX() + "," + dest.getY() + "," + dest.getZ() + " drop=" + fallHeight + " " + reason);
    }

    /** 诊断读取：本轮寻路的拒绝总数与保留样本。 */
    public static synchronized Map<String, Object> snapshot() {
        return Map.of("rejected_total", rejected, "samples", List.copyOf(SAMPLES));
    }
}
