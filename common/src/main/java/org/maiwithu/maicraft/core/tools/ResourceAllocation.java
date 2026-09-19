// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.BiPredicate;

/** 为原生配方分配有限库存；重叠标签或重复原料不能把同一份材料同时算给两个输入。 */
public final class ResourceAllocation {
    private ResourceAllocation() {}

    /** 返回每种库存分给各项原料的数量；无法完整供齐时返回空值，调用者不得降低批数后偷偷开工。 */
    public static long[][] allocate(long[] supply, long[] demand, BiPredicate<Integer, Integer> accepts) {
        if (supply.length > 256 || demand.length > 128) throw new IllegalArgumentException("recipe_allocation_budget");
        int resources = 1, ingredients = resources + supply.length, sink = ingredients + demand.length;
        long[][] residual = new long[sink + 1][sink + 1]; long total = 0;
        for (int r = 0; r < supply.length; r++) {
            if (supply[r] < 0) throw new IllegalArgumentException("negative_recipe_supply");
            residual[0][resources + r] = supply[r];
        }
        for (int i = 0; i < demand.length; i++) {
            if (demand[i] < 0) throw new IllegalArgumentException("negative_recipe_demand");
            total = Math.addExact(total, demand[i]); residual[ingredients + i][sink] = demand[i];
            for (int r = 0; r < supply.length; r++) if (accepts.test(r, i))
                residual[resources + r][ingredients + i] = demand[i];
        }
        long supplied = 0;
        while (supplied < total) {
            int[] previous = new int[sink + 1]; Arrays.fill(previous, -1); previous[0] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(0);
            while (!queue.isEmpty() && previous[sink] < 0) {
                int at = queue.remove();
                for (int next = 0; next <= sink; next++) if (previous[next] < 0 && residual[at][next] > 0) {
                    previous[next] = at; queue.add(next);
                }
            }
            if (previous[sink] < 0) return null;
            // 可以退回之前的标签分配再换另一种材料；因此不会因先拿走通用材料而饿死后面的专用输入。
            long sent = total - supplied;
            for (int at = sink; at != 0; at = previous[at]) sent = Math.min(sent, residual[previous[at]][at]);
            for (int at = sink; at != 0; at = previous[at]) {
                residual[previous[at]][at] -= sent; residual[at][previous[at]] += sent;
            }
            supplied += sent;
        }
        long[][] result = new long[supply.length][demand.length];
        for (int r = 0; r < supply.length; r++) for (int i = 0; i < demand.length; i++)
            result[r][i] = residual[ingredients + i][resources + r];
        return result;
    }
}
