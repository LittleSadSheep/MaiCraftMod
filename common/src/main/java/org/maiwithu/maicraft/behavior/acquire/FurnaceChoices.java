// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 这次烧东西能用的几台同种炉子：记得的、离角色近的在前，第一台就是挑中的那台。点开一台发现里面在烧别人的东西
 * （或换过去的那台走不到、点不开），不动它，换下一台；连第一台最多试三台，都没用上时逐台说清在哪、为什么。
 *
 * <p>只排先后、记原因，不碰界面：什么时候算"没用上"由照看炉子的动作判断。
 */
final class FurnaceChoices {

    /** 连第一台最多试几台：再多就是满世界找空炉子，不如交回去换别的路（就地摆一台新的）。 */
    static final int MAX_TRIED = 3;

    private final List<WorldPosition> stations;
    /** 前面几台为什么没用上，一台一句，按试的先后。 */
    private final List<String> skipped = new ArrayList<>();
    private int index;

    /** @param stations 记得的同种炉子，近的在前；只取前 {@link #MAX_TRIED} 台 */
    FurnaceChoices(List<WorldPosition> stations) {
        if (stations == null || stations.isEmpty()) throw new IllegalArgumentException("至少要有一台炉子");
        this.stations = List.copyOf(stations.subList(0, Math.min(MAX_TRIED, stations.size())));
    }

    /** 眼下用的这一台。 */
    WorldPosition current() {
        return stations.get(index);
    }

    /** 眼下这台是不是第一台：只有换过去的那几台走不到、点不开才接着换，第一台走不到交回引擎换路。 */
    boolean onFirst() {
        return index == 0;
    }

    /** 已经试过（含眼下这台）几台。 */
    int tried() {
        return index + 1;
    }

    /**
     * 眼下这台没用上：记下原因，换下一台。还有下一台给 true，眼下这台随之换成下一台；
     * 已经是最后一台给 false，眼下这台不变，由调用方按 {@link #summary} 收场。
     */
    boolean skip(String why) {
        skipped.add(where(current()) + why);
        if (index + 1 >= stations.size()) return false;
        index++;
        return true;
    }

    /** 都没用上时的一句说明：试了几台、每台在哪、为什么没用上；同样的原因不合并，每台的现场不一样。 */
    String summary(String stove) {
        return "试了 " + skipped.size() + " 台" + stove + "都没用上：" + String.join("；", skipped);
    }

    private static String where(WorldPosition at) {
        return "(" + at.x() + ", " + at.y() + ", " + at.z() + ") ";
    }
}
