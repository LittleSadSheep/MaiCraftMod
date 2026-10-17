// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 末地的地理事实：主岛在中心，外岛离中心约一千格外才生成，中间是一圈走不过去的虚空。
 * 每打败一次末影龙，主岛外圈（离 0,0 约 96 格、高 75 的那一圈 20 个固定位置之一）开一扇折跃门；
 * 主岛的门把东西送到同方向约 1024 格外的外岛，那边生成的门再送回原来那扇。
 * 纯函数，只按坐标算，不读世界；去外岛、回主岛要不要过门，门大概送到哪，都问这里。
 */
public final class EndIslands {

    /**
     * 主岛与外岛的分界（离 0,0 的水平格数）：原版主岛半径约一百格，外岛约一千格外才有，
     * 中间除了折跃门送过去时现生成的小岛什么都没有；取中间的 512 不会把哪一边认错。类别 F。
     */
    static final int MAIN_ISLAND_RADIUS = 512;
    /** 主岛那一圈折跃门离 0,0 的水平距离与高度：原版每次龙死开门都按这一圈算位置。 */
    static final double RING_RADIUS = 96.0;
    static final int RING_Y = 75;
    /** 那一圈一共几个位置：原版最多开 20 扇，每扇占一个固定角度（18 度一格）。 */
    static final int RING_SLOTS = 20;
    /** 主岛的门送出去多远：原版沿门所在方向从 0,0 往外 1024 格找落脚处，那里没地再往外找。 */
    static final double EXIT_DISTANCE = 1024.0;
    /** 外岛落点高度的估计：只用来估走路远近，到了以实测为准。 */
    static final int OUTER_LANDING_Y = 64;

    private EndIslands() {}

    /** 末地的哪一片：主岛，还是虚空另一边的外岛。 */
    public enum Side {
        /** 中心的主岛（龙所在的那座）。 */
        MAIN_ISLAND,
        /** 虚空外圈的外岛（末地城在这边）。 */
        OUTER_ISLANDS
    }

    /** 这一格在末地的哪一片：离 0,0 水平 512 格内是主岛，外面是外岛。 */
    public static Side sideOf(BlockPos cell) {
        long x = cell.getX();
        long z = cell.getZ();
        return x * x + z * z <= (long) MAIN_ISLAND_RADIUS * MAIN_ISLAND_RADIUS ? Side.MAIN_ISLAND : Side.OUTER_ISLANDS;
    }

    /** 两头是不是隔着虚空（一头在主岛、一头在外岛）：走路过不去，只能过折跃门。 */
    public static boolean acrossVoid(BlockPos from, BlockPos to) {
        return sideOf(from) != sideOf(to);
    }

    /**
     * 主岛那一圈折跃门的 20 个位置：和原版开门用的同一个算式，第 i 扇在 i×18 度方向、离 0,0 约 96 格、高 75。
     * 哪几扇开了要读世界才知道；这里只给可能开门的位置。
     */
    public static List<BlockPos> ringSlots() {
        List<BlockPos> slots = new ArrayList<>(RING_SLOTS);
        for (int i = 0; i < RING_SLOTS; i++) {
            double angle = 2.0 * (-Math.PI + Math.PI / RING_SLOTS * i);
            slots.add(new BlockPos((int) Math.floor(RING_RADIUS * Math.cos(angle)), RING_Y,
                    (int) Math.floor(RING_RADIUS * Math.sin(angle))));
        }
        return slots;
    }

    /**
     * 过这扇门大概落在哪：主岛的门送到同方向约 1024 格外的外岛；外岛的门送回同方向主岛那一圈。
     * 只是估计，用来比哪扇门朝着目的地；实际落点以传过去后的实测为准。门正在 0,0 上（没有方向）时原样给回。
     */
    public static BlockPos exitEstimate(BlockPos gate) {
        double length = Math.sqrt((double) gate.getX() * gate.getX() + (double) gate.getZ() * gate.getZ());
        if (length < 1.0) return gate;
        boolean fromMain = sideOf(gate) == Side.MAIN_ISLAND;
        double scale = (fromMain ? EXIT_DISTANCE : RING_RADIUS) / length;
        return new BlockPos((int) Math.round(gate.getX() * scale), fromMain ? OUTER_LANDING_Y : RING_Y,
                (int) Math.round(gate.getZ() * scale));
    }

    /** 给人看的一片的叫法。 */
    public static String describe(Side side) {
        return side == Side.MAIN_ISLAND ? "主岛" : "外岛";
    }
}
