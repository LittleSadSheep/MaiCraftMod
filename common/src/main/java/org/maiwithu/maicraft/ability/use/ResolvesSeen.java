// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 观察编号解析：把一个观察编号读成它对应的那样东西此刻在哪、是什么。
 *
 * <p>实现接在感知侧；接缝没接上时给空，用观察编号点名的目标按"编号对应的东西不在了"结束，
 * 不冒充找到了。位置带维度；方块与设施给格子，实体给代表格并带游戏实体编号。
 */
public interface ResolvesSeen {

    /** 编号对应的东西此刻的位置与类型 ID；已经不在了、或感知侧没接上时给空。 */
    Optional<Resolved> resolve(String seenId);

    /**
     * 解析出的那样东西。
     *
     * @param position     它此刻所在的位置（实体取中心所在格）
     * @param typeId       方块、设施、地形特征或实体类型的说法；实体这一轮场景没列出时为 null，
     *                     交互前按游戏实体编号从世界里读
     * @param gameEntityId 实体的游戏实体编号；地形特征与设施为 null
     */
    record Resolved(WorldPosition position, String typeId, Integer gameEntityId) {
        public Resolved {
            if (position == null) throw new IllegalArgumentException("观察编号解析出的位置不能为空");
            if (gameEntityId == null && (typeId == null || typeId.isBlank())) {
                throw new IllegalArgumentException("观察编号解析出的地点或设施必须说清是什么");
            }
        }

        /** 解析出的格子。 */
        public BlockPos blockPos() {
            return new BlockPos(position.x(), position.y(), position.z());
        }
    }
}
