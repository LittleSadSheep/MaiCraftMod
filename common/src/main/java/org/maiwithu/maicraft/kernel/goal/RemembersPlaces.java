// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/**
 * 接住"记住这个地点"的地方：能力的决定说要记一个地点时，目标推进把它交到这里。
 * 世界记忆在玩家行为层实现它；内核只认这个接缝，不认识任何具体的记忆实现。
 */
public interface RemembersPlaces {

    /** 记住一个地点；同名地点按实现自己的规矩处理（覆盖或并列）。 */
    void remember(String name, WorldPosition position);
}
