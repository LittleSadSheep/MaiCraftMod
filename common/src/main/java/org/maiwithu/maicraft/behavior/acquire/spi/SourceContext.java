// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 问询来源时告诉它的一次现场：角色现在在哪、这次任务有哪些许可、愿意走多远。
 * 来源按位置估距离、按许可决定哪些来源根本不参与；愿意走的距离只有要"出去找"的来源用
 * （附近没有时走多远再找一片），报价太远的路仍然由引擎按它挡下。
 *
 * @param characterAt  角色此刻所在的位置
 * @param permissions  这次任务的许可
 * @param radiusBlocks 这次任务允许搜索的范围（格）；null 表示用来源自己的默认半径。
 *                     半径给了就不越界，这是"给了就冻结在范围内"的规矩
 * @param maxDistanceBlocks 愿意为此走多远（格）；null 表示不限。只有要走出半径再找一片的来源用它
 */
public record SourceContext(WorldPosition characterAt, Permissions permissions, Integer radiusBlocks,
        Double maxDistanceBlocks) {

    public SourceContext {
        if (characterAt == null) throw new IllegalArgumentException("问询来源必须带着角色的位置");
        if (permissions == null) throw new IllegalArgumentException("问询来源必须带着这次的许可");
    }

    /** 不限搜索半径与距离的问询：来源按自己的默认半径找。 */
    public SourceContext(WorldPosition characterAt, Permissions permissions) {
        this(characterAt, permissions, null, null);
    }

    /** 只限搜索半径的问询。 */
    public SourceContext(WorldPosition characterAt, Permissions permissions, Integer radiusBlocks) {
        this(characterAt, permissions, radiusBlocks, null);
    }
}
