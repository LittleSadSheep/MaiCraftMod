// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 常驻任务：等条件、跟着人这类没有"做完"时刻的任务实现它。
 *
 * <p>内核给每一步的子任务留了一个兜底时限，防某个任务永远不结束；常驻任务等的就是时间本身，
 * 按那个时限收尾等于"等满十分钟就被赶下场"。标记为常驻后，内核不再按兜底时限把它截停，
 * 卡没卡住仍由任务自己的进度跟踪负责——等与跟都有各自的进展判断，不会真的无限磨蹭。
 */
public interface Standing {
}
