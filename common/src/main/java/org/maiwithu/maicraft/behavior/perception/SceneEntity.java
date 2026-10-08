// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Map;

/**
 * 一只看得见或刚看见过的实体：类型、名字、方位、距离、高低差、是否敌对、是否正盯着我，
 * 以及看得出来的特征（羊的颜色、是否剪过毛、幼年、着火、手持物、盔甲）。
 *
 * <p>可见为假时这条只代表"刚才看见过、现在还在保留期内"，不冒充此刻看得见。
 */
public record SceneEntity(
        String id, String type, String name,
        String direction, String compass, int distance, int dy,
        boolean visible, boolean hostile, boolean targetingMe,
        Map<String, String> traits) {

    public SceneEntity {
        traits = Map.copyOf(traits);
    }
}
