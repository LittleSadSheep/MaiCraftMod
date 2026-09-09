package org.maiwithu.maicraft.core.tools;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;
import java.util.function.Supplier;

/**
 * 查询配方时的小保护层，防止某个 Mod 配方的读取异常让整次查找失败。
 * 方法名中的 usable 只表示这些字段读得出来，不代表配方一定能在当前玩家和设备上完成。
 */
public final class RecipeProbe {

    private RecipeProbe() {}

    /** 展示产出——枚举配方表用它,不用空输入 assemble(那是出合同的问法)。 */
    public static ItemStack resultOf(Recipe<?> recipe, HolderLookup.Provider registries) {
        return probe(() -> recipe.getResultItem(registries));
    }

    /** 探一次产出:null 与异常一律折成 EMPTY,调用方只看 {@code isEmpty()}。 */
    // 配方读取抛运行时异常或返回 null 时按空产物处理，让一个坏配方不阻断整个查询；原因不会由此方法单独返回。
    public static ItemStack probe(Supplier<ItemStack> supplier) {
        try {
            ItemStack result = supplier.get();
            return result == null ? ItemStack.EMPTY : result;
        } catch (RuntimeException broken) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 这条配方的输入表能不能安全交给下游:列表本身与每个 ingredient 的
     * {@code getItems()} 都不为 null。craft 的摆料、盘点、缺料描述都在这道门
     * 之后,过了门就不必层层判空。
     */
    // 这里只确认材料列表及每个材料的候选数组能读取、不是 null。
    // 不保证数组非空、物品当前可获得，或本项目执行器能处理该配方的所有组件条件。
    public static boolean usableIngredients(Recipe<?> recipe) {
        try {
            List<Ingredient> ings = recipe.getIngredients();
            if (ings == null) {
                return false;
            }
            for (Ingredient ing : ings) {
                if (ing == null || ing.getItems() == null) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException broken) {
            return false;
        }
    }
}
