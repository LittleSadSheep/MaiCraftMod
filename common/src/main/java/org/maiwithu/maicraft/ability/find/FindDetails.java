// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 寻找的结果细节：找到了哪几样（各带观察编号）、查了多大范围、按类型各找到几个。
 * 字段名转下划线后就是 LLM 看到的 found、searched_radius、search_complete、count_by_type。
 *
 * @param found           命中列表，由近到远；每条带观察编号，可直接拿去下达别的指令
 * @param searchedRadius  实际查过的半径（格）
 * @param searchComplete  愿意找的范围扫完了没有；没扫完时"没找到"不作数
 * @param countByType     给了多种类型时，按类型分别找到几个
 */
public record FindDetails(List<Found> found, int searchedRadius, boolean searchComplete,
        Map<String, Integer> countByType) implements ResultDetails {

    public FindDetails {
        found = List.copyOf(found);
        countByType = Map.copyOf(countByType);
    }

    /**
     * 一个命中。
     *
     * @param id               观察编号：方块 b#、实体 e#、结构线索 f#
     * @param type             方块或实体类型注册 ID；结构线索是要找的结构 ID
     * @param position         位置；结构线索是线索记的大概位置，到场还要再找
     * @param distance         与角色的三维距离（格）
     * @param dy               高低差（格）
     * @param direction        相对角色朝向的方位说法
     * @param compass          东南西北方位
     * @param creatureProtected 是不是受保护的生物；照常报告，但能不能动归许可
     * @param protectionReason 受保护的具体原因（有名字、被驯服、拴着绳、圈养着）
     */
    public record Found(String id, String type, WorldPosition position, int distance, int dy,
            String direction, String compass, boolean creatureProtected, String protectionReason) {}
}
