// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 维度路由：出发维度与目标维度之间走哪种原版门。
 *
 * <p>原版只有两对维度有门：主世界与下界走下界传送门（两头的门方块都是下界传送门），
 * 主世界与末地走末地传送门（主世界一头的门方块是末地传送门，进门落到末地）。
 * 其余组合没有原版门：下界与末地之间建议经主世界中转，怎么拆程由 LLM 决定；
 * 模组维度没有门，如实说目前只支持主世界、下界、末地。
 * 目标维度与当前相同就没有跨维度段，不经过这里。
 */
public final class DimensionRoute {

    /** 原版三个维度。 */
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";

    /** 一条路由的结论：走哪种门、还是走不通（带着原因）。 */
    public record Decision(Way way, Block portalBlock, Problem problem) {

        /** 没有跨维度段：目标就在当前维度。 */
        public boolean sameDimension() {
            return way == Way.SAME_DIMENSION;
        }
    }

    /** 跨维度怎么走。 */
    public enum Way {
        /** 目标就是当前维度，没有跨维度段。 */
        SAME_DIMENSION,
        /** 走下界传送门：主世界与下界之间。 */
        NETHER_PORTAL,
        /** 走末地传送门：主世界与末地之间。 */
        END_PORTAL,
        /** 两个维度之间没有原版门，要经主世界中转。 */
        NO_DIRECT_PORTAL,
        /** 那个维度不认识：模组维度或写错了。 */
        UNKNOWN_DIMENSION
    }

    private DimensionRoute() {}

    /** 从出发维度去目标维度怎么走；目标维度为 null 按当前维度算。 */
    public static Decision decide(String currentDimension, String targetDimension) {
        if (targetDimension == null || targetDimension.equals(currentDimension)) {
            return new Decision(Way.SAME_DIMENSION, null, null);
        }
        // 主世界与下界走下界传送门；两个方向用的都是同一种门方块。
        if (isPair(currentDimension, targetDimension, OVERWORLD, NETHER)) {
            return new Decision(Way.NETHER_PORTAL, Blocks.NETHER_PORTAL, null);
        }
        // 主世界与末地走末地传送门：主世界这一头扫的是末地传送门方块（激活后的门面）。
        if (isPair(currentDimension, targetDimension, OVERWORLD, END)) {
            return new Decision(Way.END_PORTAL, Blocks.END_PORTAL, null);
        }
        // 下界与末地之间没有原版门：建议经主世界中转，不自动串三段。
        if (isPair(currentDimension, targetDimension, NETHER, END)) {
            return new Decision(Way.NO_DIRECT_PORTAL, null, Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "下界与末地之间没有原版传送门",
                    "先回主世界（或从主世界出发）再换末地传送门；怎么拆程你来决定"));
        }
        return new Decision(Way.UNKNOWN_DIMENSION, null, Problem.of(Problem.Kind.UNSUPPORTED,
                "不认识维度「" + targetDimension + "」：目前只支持主世界、下界、末地",
                "在原版三个维度之间出行用各自的维度 id"));
    }

    /** 出发与目标是不是这一对维度的两端（不分先后）。 */
    private static boolean isPair(String from, String to, String a, String b) {
        return (a.equals(from) && b.equals(to)) || (b.equals(from) && a.equals(to));
    }
}
