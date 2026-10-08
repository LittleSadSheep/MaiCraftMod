// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

/**
 * 听见的声音：来自原版字幕事件，只有声音种类、方位和远近，不给精确位置——
 * 墙后面的东西看不见，但听得见它就在附近。
 *
 * @param kind 声音种类，例如"苦力怕嘶嘶声""脚步声"
 */
public record HeardSound(String kind, String direction, String nearness) {}
