// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

/**
 * 环境观察：时间、天气、光照、生物群系。
 *
 * @param timeText 时间的一句话说法，例如"夜晚，距天亮约 4 分钟"
 */
public record SceneEnvironment(String timeText, String weather, int light, String biome) {}
