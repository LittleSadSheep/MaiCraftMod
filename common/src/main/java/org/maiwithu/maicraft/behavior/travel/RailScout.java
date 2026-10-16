// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 已加载区铁轨的只读扫端：报价前看附近有哪些铁轨格，扫没扫完如实说——
 * 线路这一刻没读完就先不下"没有铁轨"的结论。替身测试在这里摆场景。
 */
public interface RailScout {

    /**
     * 扫一轮四种铁轨（普通、动力、探测、激活）：以角色为中心、给定范围内这一刻找到的轨道格，
     * 以及扫完没有；没扫完时命中的清单不全，调用方不得下"没有"的结论。
     *
     * @param searchDistance 范围（格）
     * @param want           最多认几格
     */
    Scan scanRails(int searchDistance, int want);

    /** 一轮扫描的收成。 */
    record Scan(List<BlockPos> hits, boolean complete) {
    }
}
