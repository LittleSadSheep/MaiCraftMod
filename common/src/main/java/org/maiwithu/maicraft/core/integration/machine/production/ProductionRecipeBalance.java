// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Ingredient;
import org.maiwithu.maicraft.core.tools.ResourceAllocation;

/** 网络生产与世界内加工共用有限资源分配，避免把同一份库存重复分给重叠标签原料。 */
final class ProductionRecipeBalance {
    private ProductionRecipeBalance() {}

    static boolean covers(List<Ingredient> ingredients, Map<Resource, Long> supply, long batches) {
        if (ingredients.size() > 128 || supply.size() > 256) throw new IllegalArgumentException("Recipe balance exceeds 128 ingredients or 256 distinct resources");
        List<Resource> resources = new ArrayList<>(supply.keySet());
        long[] available = new long[resources.size()], required = new long[ingredients.size()];
        var ids = new HashSet<String>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (!ids.add(ingredient.id())) throw new IllegalArgumentException("Duplicate recipe ingredient identity");
            // 催化物只需保留一份，实际会消耗的原料才按整批需求放大；原配方规则仍由调用方提供。
            required[i] = Math.multiplyExact(ingredient.amount(), ingredient.consumed() ? batches : 1);
        }
        for (int r = 0; r < resources.size(); r++) {
            long amount = supply.get(resources.get(r));
            if (amount < 0) throw new IllegalArgumentException("Negative available ingredient quantity");
            // 数量检查保留原先语义，具体匹配分配交给共用求解器。
            available[r] = amount;
        }
        return ResourceAllocation.allocate(available, required,
                (r, i) -> ingredients.get(i).alternatives().contains(resources.get(r))) != null;
    }
}
