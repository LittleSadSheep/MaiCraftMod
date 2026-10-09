// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.List;
import java.util.Optional;

/**
 * 挖三填一的选点判断：脚下这一列能不能挖成一个封得住的坑，纯函数。
 *
 * <p>挖三填一是往脚下挖三格、站进坑底、把头顶那格（脚下第一格）封上，四面八方都是方块，
 * 怪碰不到人。挖之前看清楚：脚下三格都能挖（不是基岩、液体、空洞，也不是别人的东西）、
 * 第四格踩得住，三格四周的坑壁也都是实心的（没有液体会灌进来、没有缺口让怪钻进来）。
 * 有一样不满足就不挖，交代原因，由任务站定硬熬。
 */
public final class BurrowPlan {

    /** 挖坑时一格是什么样。 */
    public enum Ground {
        /** 能挖的实心方块。 */
        DIGGABLE,
        /** 实心但挖不动（基岩这类）。 */
        UNBREAKABLE,
        /** 液体（水、岩浆）。 */
        FLUID,
        /** 空的：空气、洞穴、走得过去的东西。 */
        OPEN,
        /** 受保护的方块（别人放的、记住的地盘里），不能挖。 */
        PROTECTED
    }

    /**
     * 脚下一列与坑壁的样子。
     *
     * @param below 脚下第一到第四格，自上而下
     * @param walls 脚下第一到第三格各自水平四周的格子，共十二格
     */
    public record Site(List<Ground> below, List<Ground> walls) {
        public Site {
            below = List.copyOf(below);
            walls = List.copyOf(walls);
            if (below.size() != 4) throw new IllegalArgumentException("脚下要看四格：" + below.size());
        }
    }

    /** 挖几格。 */
    static final int DEPTH = 3;

    private BurrowPlan() {}

    /** 能挖就给空；不能挖给一句原因，说的是现场是什么样，不是猜结果。 */
    public static Optional<String> refusal(Site site) {
        for (int i = 0; i < DEPTH; i++) {
            Optional<String> why = digRefusal(site.below().get(i), i + 1);
            if (why.isPresent()) return why;
        }
        Ground floor = site.below().get(DEPTH);
        if (floor == Ground.FLUID || floor == Ground.OPEN) {
            return Optional.of("坑底下面是" + (floor == Ground.FLUID ? "液体" : "空的") + "，站不住");
        }
        for (Ground wall : site.walls()) {
            if (wall == Ground.FLUID) return Optional.of("坑壁外有液体，挖开会灌进来");
            if (wall == Ground.OPEN) return Optional.of("坑壁有缺口，封不严");
        }
        return Optional.empty();
    }

    // 要挖的那一格：能挖才行，其余各说各的原因。
    private static Optional<String> digRefusal(Ground ground, int depth) {
        return switch (ground) {
            case DIGGABLE -> Optional.empty();
            case UNBREAKABLE -> Optional.of("脚下第 " + depth + " 格挖不动");
            case FLUID -> Optional.of("脚下第 " + depth + " 格是液体");
            case OPEN -> Optional.of("脚下第 " + depth + " 格是空的，挖下去会掉进洞里");
            case PROTECTED -> Optional.of("脚下第 " + depth + " 格是别人的东西，不能挖");
        };
    }
}
