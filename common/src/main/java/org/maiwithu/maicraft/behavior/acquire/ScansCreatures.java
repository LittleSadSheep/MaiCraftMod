// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.UUID;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 扫猎物的只读接缝：以一处为圆心，把附近已加载的、对得上类型的活物报上来。
 * 已加载的都算候选（猎物少且会走动，按看见筛会漏掉墙后走着的羊）；出不出得了手，
 * 由执行在出手前看这一眼（射线核对），问价时不透视也不装作看不见。
 */
public interface ScansCreatures {

    /**
     * 半径内这些类型的成年活物，由近及远；幼崽不报（幼崽不掉东西，也不打幼崽）。
     *
     * @param typeIds      要找的生物类型，例如 minecraft:sheep
     * @param center       从哪里找起
     * @param radiusBlocks 半径，单位格
     */
    List<CreatureSpot> creatures(List<String> typeIds, WorldPosition center, int radiusBlocks);

    /**
     * 一只猎物候选。
     *
     * @param entityId 游戏的实体编号：走近、出手都按它找回这只
     * @param uuid     生物编号：问处境（有没有名字、驯服、拴绳、圈养）用它
     * @param type     生物类型，例如 minecraft:sheep
     * @param at       此刻所在的位置
     * @param visible  此刻视线有没有被挡住；候选不看这个，出手前才核对
     * @param woolColor 羊身上的毛色（white、black 这类）；不是羊给 null
     * @param sheared  羊是不是剪过毛；不是羊给 false
     */
    record CreatureSpot(int entityId, UUID uuid, String type, WorldPosition at,
            boolean visible, String woolColor, boolean sheared) {

        /** 给人看的一句话，例如"羊（实体 42）"。 */
        public String describe() {
            String name = type.substring(type.indexOf(':') + 1);
            return name + "（实体 " + entityId + "）";
        }
    }
}
