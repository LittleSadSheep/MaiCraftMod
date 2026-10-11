// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import net.minecraft.world.phys.Vec3;

/**
 * 飞艇的落点与前方地形（纯函数，地面怎么读交进来）：
 * 落点——目的地前后左右 32 格内每 8 格一个候选，离目的地近的先试；候选要盖得下船身再加 4 格余量，
 * 那一片地面都加载着、不是液体、高低差不超过 1 格。不是全世界找场地。
 * 前方——沿航线往前看一段，船身宽度两侧也看，找最高的地面；有没加载的格就说不知道，不当畅通。
 */
final class LandingSites {

    /** 候选在目的地多远以内、隔几格一个（格）。 */
    static final int RANGE = 32;
    static final int STEP = 8;
    /** 船身外再留几格。 */
    static final int MARGIN = 4;
    /** 一片地面隔几格量一次。 */
    static final int SAMPLE = 2;
    /** 一片地面的高低差最多几格。 */
    static final int ROUGHNESS = 1;

    /** 读地面：某一柱地面的顶面高度（站上去脚底的 y），没加载为 null；这一柱顶上是不是液体。 */
    interface Ground {
        Integer top(int x, int z);

        boolean liquid(int x, int z);
    }

    private LandingSites() {}

    /**
     * 找落点：给落点（那一片最高地面的顶面、候选中心）；一个都不行为空。
     *
     * @param halfWidth 船身水平方向最长那一边的一半（格）
     */
    static Optional<Vec3> find(Vec3 destination, double halfWidth, Ground ground) {
        List<Vec3> candidates = new ArrayList<>();
        for (int x = -RANGE; x <= RANGE; x += STEP) {
            for (int z = -RANGE; z <= RANGE; z += STEP) candidates.add(destination.add(x, 0, z));
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(destination)));
        double reach = halfWidth + MARGIN;
        for (Vec3 candidate : candidates) {
            OptionalDouble top = flat(candidate, reach, ground);
            if (top.isPresent()) {
                return Optional.of(new Vec3(Math.floor(candidate.x) + 0.5, top.getAsDouble(), Math.floor(candidate.z) + 0.5));
            }
        }
        return Optional.empty();
    }

    // 一片地面平不平：都加载着、不是液体、高低差不超过 1 格就给最高的顶面。
    private static OptionalDouble flat(Vec3 center, double reach, Ground ground) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (double dx = -reach; dx <= reach; dx += SAMPLE) {
            for (double dz = -reach; dz <= reach; dz += SAMPLE) {
                int x = (int) Math.floor(center.x + dx);
                int z = (int) Math.floor(center.z + dz);
                Integer top = ground.top(x, z);
                if (top == null || ground.liquid(x, z)) return OptionalDouble.empty();
                lowest = Math.min(lowest, top);
                highest = Math.max(highest, top);
                if (highest - lowest > ROUGHNESS) return OptionalDouble.empty();
            }
        }
        return highest == Integer.MIN_VALUE ? OptionalDouble.empty() : OptionalDouble.of(highest);
    }

    /**
     * 沿航线往前一段（含船身两侧）最高的地面顶面；有没加载的格为空（不当畅通）。
     *
     * @param ahead 往前看多远（格）
     */
    static OptionalDouble highestAhead(Vec3 from, Vec3 to, double halfWidth, double ahead, Ground ground) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double length = Math.hypot(dx, dz);
        if (length < 1e-6) return OptionalDouble.of(Integer.MIN_VALUE);
        double ux = dx / length;
        double uz = dz / length;
        double highest = Integer.MIN_VALUE;
        for (double along = 0; along <= Math.min(ahead, length); along += 4) {
            for (double across = -halfWidth; across <= halfWidth; across += Math.max(1, halfWidth)) {
                int x = (int) Math.floor(from.x + ux * along - uz * across);
                int z = (int) Math.floor(from.z + uz * along + ux * across);
                Integer top = ground.top(x, z);
                if (top == null) return OptionalDouble.empty();
                highest = Math.max(highest, top);
            }
        }
        return OptionalDouble.of(highest);
    }
}
