// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.core.task.container.ContainerSplitPlanner.Side;
import org.maiwithu.maicraft.core.task.container.ContainerSplitPlanner.State;
import org.maiwithu.maicraft.core.task.container.ContainerSplitPlanner.Step;

/** 精妙大堆叠点击仍受手中物品上限约束；只规划一份目的格装得下的精确数量，再把余量退回。 */
public final class BackpackSplitPlanner {
    private BackpackSplitPlanner() {}

    public static List<Step> plan(int source, int amount, int returnCapacity, int pickupLimit) {
        if (source < 1 || amount < 1 || amount > source || pickupLimit < 1 || pickupLimit > 1024 || amount > pickupLimit)
            throw new IllegalArgumentException("backpack transfer must fit one native carried stack");
        List<Step> best = null;
        // 原生左键最多取一叠、右键取这一叠的一半；比较退余量与逐个放入，避免点击整包的大数量。
        for (int button : new int[]{0, 1}) {
            int capped = Math.min(source, pickupLimit), take = button == 0 ? capped : (capped + 1) / 2;
            if (take < amount || take > amount && returnCapacity < source - amount) continue;
            var steps = new ArrayList<Step>();
            State current = new State(source, 0, 0);
            State picked = new State(source - take, take, 0);
            steps.add(new Step(Side.SOURCE, button, current, picked)); current = picked;
            int excess = take - amount;
            if (excess < amount) {
                for (int i = 0; i < excess; i++) {
                    State after = new State(current.source() + 1, current.cursor() - 1, 0);
                    steps.add(new Step(Side.SOURCE, 1, current, after)); current = after;
                }
                steps.add(new Step(Side.DESTINATION, 0, current, new State(source - amount, 0, amount)));
            } else {
                for (int i = 0; i < amount; i++) {
                    State after = new State(current.source(), current.cursor() - 1, current.deposited() + 1);
                    steps.add(new Step(Side.DESTINATION, 1, current, after)); current = after;
                }
                if (current.cursor() > 0) steps.add(new Step(Side.SOURCE, 0, current, new State(source - amount, 0, amount)));
            }
            if (best == null || steps.size() < best.size()) best = steps;
        }
        if (best == null) throw new IllegalArgumentException("backpack source cannot receive the unrequested remainder");
        return List.copyOf(best);
    }
}
