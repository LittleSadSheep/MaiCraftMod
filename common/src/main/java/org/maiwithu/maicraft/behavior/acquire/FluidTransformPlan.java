// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;

/**
 * 往水里扔料让它变：挑哪条配方、扔几份、每样料用哪种物品，纯函数。
 *
 * <p>产物对得上要的东西的配方有几条时，先挑身上缺的料最少的，再挑一份出得多的（福鲁伊克斯水晶：
 * 充能石英加红石加下界石英一份出两个，排在充能石英加福鲁伊克斯粉一份出一个前面）。
 * 要几件就扔几份，一次最多 64 份（触发的那一样整组扔），不够的引擎再来一轮。
 * 一样料能对上几种物品时，身上哪种多用哪种，都没有用配方里列的第一种。
 */
public final class FluidTransformPlan {

    /** 一次最多扔几份：触发的那一样一组 64 件。 */
    static final int MAX_SETS = 64;

    private FluidTransformPlan() {
    }

    /**
     * 挑好的做法。
     *
     * @param recipe  用哪条配方
     * @param sets    扔几份
     * @param choices 每一样料用哪种物品，和配方的料一一对应
     * @param missing 身上还缺几件料（各样相加）
     */
    public record Plan(FluidTransform recipe, int sets, List<String> choices, int missing) {

        public Plan {
            choices = List.copyOf(choices);
        }

        /** 这几份一共能变出几件。 */
        public int yield() {
            return sets * recipe.resultCount();
        }

        /** 每种物品要几件：份数乘上它在配方里出现几次，按配方的先后排。 */
        public Map<String, Integer> needs() {
            Map<String, Integer> needs = new LinkedHashMap<>();
            for (String choice : choices) needs.merge(choice, sets, Integer::sum);
            return needs;
        }

        /** 触发的那一样用哪种物品。 */
        public String trigger() {
            return choices.get(recipe.trigger());
        }
    }

    /**
     * @param recipes 能用的配方
     * @param wanted  产物是不是要的东西
     * @param count   要几件
     * @param carried 身上某种物品有几件
     */
    public static Optional<Plan> choose(List<FluidTransform> recipes, Predicate<String> wanted, int count,
            ToIntFunction<String> carried) {
        List<Plan> plans = new ArrayList<>();
        for (FluidTransform recipe : recipes) {
            if (!wanted.test(recipe.resultId())) continue;
            int sets = Math.min(MAX_SETS, Math.max(1, (count + recipe.resultCount() - 1) / recipe.resultCount()));
            List<String> choices = new ArrayList<>();
            for (List<String> options : recipe.ingredients()) {
                choices.add(options.stream().max(Comparator.comparingInt(carried)).filter(item -> carried.applyAsInt(item) > 0)
                        .orElse(options.getFirst()));
            }
            Plan draft = new Plan(recipe, sets, choices, 0);
            int missing = 0;
            for (var need : draft.needs().entrySet()) {
                missing += Math.max(0, need.getValue() - carried.applyAsInt(need.getKey()));
            }
            plans.add(new Plan(recipe, sets, choices, missing));
        }
        return plans.stream().min(Comparator.comparingInt(Plan::missing)
                .thenComparing(plan -> -plan.recipe().resultCount())
                .thenComparing(plan -> plan.recipe().id()));
    }
}
