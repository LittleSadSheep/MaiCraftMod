// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.List;

/**
 * 探索的目标与留意清单：任务输入里"要做什么"的部分。
 *
 * <p>探索没有预设终点：目标只说"看什么"，不说"去哪个坐标"。群系目标带选择器
 * （ID 或 # 标签）；留意清单是途中顺带记下的东西，不改路线、不算抵达。
 */
public final class ExploreObjective {

    /** 探索目标的四种写法。 */
    public enum Kind { SURVEY, BIOME, FLAT_GROUND, SURFACE }

    private final Kind target;
    private final List<String> selectors;

    public ExploreObjective(Kind target, List<String> selectors) {
        this.target = target;
        this.selectors = List.copyOf(selectors);
    }

    public static ExploreObjective survey() {
        return new ExploreObjective(Kind.SURVEY, List.of());
    }

    /** 探索目标：自由跑图、找群系（带选择器）、找平台、回露天。 */
    public Kind target() {
        return target;
    }

    /** 定向目标的选择器（群系 ID 或 # 标签）；其余目标为空。 */
    public List<String> selectors() {
        return selectors;
    }

    /** 途中留意清单：地形要素、结构 ID、方块 ID/标签、实体类型。 */
    public record Interests(List<String> blocks, List<String> entities,
            List<String> structures, List<String> terrainKinds) {

        public static final Interests NONE = new Interests(List.of(), List.of(), List.of(), List.of());

        public Interests {
            blocks = List.copyOf(blocks);
            entities = List.copyOf(entities);
            structures = List.copyOf(structures);
            terrainKinds = List.copyOf(terrainKinds);
        }

        /** 什么都没留意：路上只记群系与地形，不额外找东西。 */
        public boolean isEmpty() {
            return blocks.isEmpty() && entities.isEmpty() && structures.isEmpty()
                    && terrainKinds.isEmpty();
        }
    }
}
