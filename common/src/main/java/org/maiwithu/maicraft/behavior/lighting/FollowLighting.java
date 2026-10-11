// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 随行补光的会话设置：开关与阈值，启动时创建一份，同时交给太暗这项生存需求与 light 能力，
 * 进世界重置为默认（默认开：像正常玩家一样，走暗处带火把插火把）。
 *
 * <p>太暗需求走过并实测过仍暗的路线格也记在这里，light 能力查状态时照实报；只记最近的，
 * 卸载的格不猜。只在客户端线程读写。
 */
public final class FollowLighting {

    /** 阈值上限：火把相邻格最高 13，再高永远点不满。 */
    public static final int MAX_LIGHT = 13;

    /** 默认阈值：暗到什么程度才算"太暗"。 */
    public static final int DEFAULT_LIGHT = 8;

    /** 仍暗的路线格最多记几条：状态查询报"最近的"，不是全程台账。 */
    private static final int RECENT_DARK_LIMIT = 32;

    private boolean enabled = true;
    private int minimumLight = DEFAULT_LIGHT;
    private final Deque<DarkCell> darkOnRoute = new ArrayDeque<>();

    /** 一格实测仍暗的路线格：在哪、多暗、什么时候走过的。 */
    public record DarkCell(BlockPos pos, int light, long tick) {}

    /** 进世界重置为默认：开关与阈值回默认，仍暗的路线格作废。 */
    public void reset() {
        enabled = true;
        minimumLight = DEFAULT_LIGHT;
        darkOnRoute.clear();
    }

    /** 开随行补光；阈值 1..13。 */
    public void enable(int minimumLight) {
        this.enabled = true;
        this.minimumLight = Math.min(Math.max(minimumLight, 1), MAX_LIGHT);
    }

    /** 关随行补光；已插下的灯留着，路线记录作废。 */
    public void disable() {
        enabled = false;
        darkOnRoute.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public int minimumLight() {
        return minimumLight;
    }

    /** 太暗需求实测仍暗时记一格：走过并采样过的格才进得来。 */
    public void noteDark(BlockPos pos, int light, long tick) {
        for (DarkCell cell : darkOnRoute) {
            if (cell.pos().equals(pos)) {
                darkOnRoute.remove(cell);
                break;
            }
        }
        darkOnRoute.addLast(new DarkCell(pos.immutable(), light, tick));
        while (darkOnRoute.size() > RECENT_DARK_LIMIT) {
            darkOnRoute.removeFirst();
        }
    }

    /** 最近实测仍暗的路线格，按时间先后。 */
    public List<DarkCell> darkOnRoute() {
        return List.copyOf(darkOnRoute);
    }
}
