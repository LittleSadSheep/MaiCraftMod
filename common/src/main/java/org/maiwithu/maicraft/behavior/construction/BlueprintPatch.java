// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * 逐格补丁合并：改一栋建筑、一台机器时，把新给的格并进档案里存着的整份蓝图，纯函数。
 *
 * <p>同一格以补丁为准；补丁没提的旧目标留着；补丁里写的格挪到末尾、按补丁里的先后排——
 * 补丁写了"先岩浆后水"就照补丁先倒岩浆，不沿用旧档案里那一格原来的先后。两份的位置都相对同一个锚点。
 */
public final class BlueprintPatch {

    private BlueprintPatch() {}

    /** 把补丁并进整份蓝图，返回新的一份；两份原样不动。 */
    public static List<PlannedCell> merge(List<PlannedCell> existing, List<PlannedCell> patch) {
        Map<BlockPos, PlannedCell> cells = new LinkedHashMap<>();
        for (PlannedCell cell : existing) cells.put(cell.pos(), cell);
        for (PlannedCell cell : patch) {
            // 先拿掉再放进去：同位旧格原地覆盖会沿用旧档案的先后，补丁写的倒桶顺序就不作数了。
            cells.remove(cell.pos());
            cells.put(cell.pos(), cell);
        }
        return List.copyOf(cells.values());
    }

    /** 两份是不是同一份目标：格数一样、同一位置要的都一样，不看先后。补丁没带来变化时按"开始时已满足"对待。 */
    public static boolean same(List<PlannedCell> left, List<PlannedCell> right) {
        if (left.size() != right.size()) return false;
        Comparator<PlannedCell> byPosition = Comparator.comparingLong(cell -> cell.pos().asLong());
        List<PlannedCell> sortedLeft = new ArrayList<>(left);
        List<PlannedCell> sortedRight = new ArrayList<>(right);
        sortedLeft.sort(byPosition);
        sortedRight.sort(byPosition);
        return sortedLeft.equals(sortedRight);
    }
}
