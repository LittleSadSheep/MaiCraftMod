// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 字幕接缝：游戏接口层把原版的字幕事件（声音提示）转进感知的窄入口。
 *
 * <p>字幕事件天生只有声音种类和大致来源，感知只整理出方位和远近，
 * 不给精确位置——听见的和看见的分开，墙后有什么靠它知道"就在附近"。
 * 实现留给游戏接口层，测试用替身。
 */
public interface SubtitleEar {

    /** 最近一小会儿的字幕事件，每次刷新取一批。 */
    List<Event> recent();

    /**
     * 一条字幕事件。
     *
     * @param kind 声音种类，取字幕的文字，例如"苦力怕嘶嘶声""脚步声"
     * @param approximatePosition 声音来源的大致位置，只用来算方位，不对外给坐标
     */
    record Event(String kind, WorldPosition approximatePosition) {}
}
