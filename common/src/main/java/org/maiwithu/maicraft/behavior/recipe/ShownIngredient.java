// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import java.util.List;
import java.util.Objects;

/**
 * 配方里的一格原料（或催化剂）：这一格放什么都行的那几样，以及要几份。
 *
 * <p>配方本来就是"这一类都行"时写成标签（例如 c:ingots/iron），给一个示例名方便读；不是标签时列出全部可选的东西。
 *
 * @param tag     这一格接受的物品标签，不带 #；不是标签时为 null
 * @param options 这一格可以放的东西；不是标签时至少一样。是标签时是标签里的东西，标签还没同步过来时可能为空
 * @param amount  要几份：物品是件数，流体是毫桶
 */
public record ShownIngredient(String tag, List<ShownStack> options, long amount) {

    public ShownIngredient {
        options = List.copyOf(options);
        if (options.isEmpty() && tag == null) throw new IllegalArgumentException("一格原料至少要有一样能放的东西");
        if (tag != null && (tag.isBlank() || tag.startsWith("#"))) throw new IllegalArgumentException("标签写成不带 # 的 ID：" + tag);
        if (amount < 1) throw new IllegalArgumentException("原料至少要 1 份：" + amount);
    }

    /** 只能放这一样东西，数量取它自己的。 */
    public static ShownIngredient of(ShownStack only) {
        Objects.requireNonNull(only, "only");
        return new ShownIngredient(null, List.of(only), Math.max(1, only.amount()));
    }

    /** 这一格接不接受这件物品。 */
    public boolean accepts(String itemId) {
        return options.stream().anyMatch(stack -> stack.kind() == ShownStack.Kind.ITEM && stack.id().equals(itemId));
    }
}
