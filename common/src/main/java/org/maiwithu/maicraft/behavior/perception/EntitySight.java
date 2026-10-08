// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 实体视线接缝：读角色附近实体的原始观察，整理方位与编号之前的事实。
 *
 * <p>是否看得见按"在渲染范围内且视线没被挡住"判断，由游戏接口层用与观察同一套
 * 视线遮挡实现；墙后的实体不在这份观察里冒充看见。实现留给游戏接口层，测试用替身。
 */
public interface EntitySight {

    /** 现在能看见（或刚看见还在保留范围）的实体观察，每个实体一条。 */
    List<Observation> nearby();

    /**
     * 一只实体的原始观察。
     *
     * @param entityId 游戏的实体编号，同一只实体各次观察保持一致
     * @param type 实体类型注册 ID，例如 minecraft:zombie
     * @param name 名字牌或自定义名，没有为 null
     * @param visible 此刻视线没有被挡住
     * @param hostile 是敌对生物
     * @param targetingMe 正把攻击目标放在角色身上
     * @param traits 看得出来的特征：羊的颜色、是否剪过毛、幼年、着火、手持物、盔甲
     */
    record Observation(
            int entityId, String type, String name, WorldPosition position,
            boolean visible, boolean hostile, boolean targetingMe,
            Map<String, String> traits) {

        public Observation {
            traits = Map.copyOf(traits);
        }
    }
}
