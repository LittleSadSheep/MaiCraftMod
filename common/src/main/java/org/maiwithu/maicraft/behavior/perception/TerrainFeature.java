// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 一处地形特征：悬崖、水体、树林这类成片地物，一个编号 f#。
 * 方位与距离以特征的大致中心计，规模说清是一片多大的。
 */
public record TerrainFeature(
        String id, String kind, WorldPosition center,
        String direction, String compass, int distance, String size) {}
