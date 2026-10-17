// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Set;

/**
 * 一条工作站配方：角色能自己在合成台、熔炉、石切台上动手做的，从游戏真实配方管理器里读出来的只读快照。含模组配方；
 * 原料写的是游戏要的东西（具体物品或标签），产出写一次能做出几件。
 *
 * @param id           配方的注册 ID，例如 minecraft:iron_ingot_from_blasting_iron_ore
 * @param kind         在哪种设施上做（合成台、熔炉、石切台）
 * @param result       做出来的东西
 * @param resultCount  做一次出几件
 * @param ingredients  做一次要的原料；烧炼与石切台只有一条
 * @param fitsInInventory 合成配方摆得进背包的 2×2 合成格（木板、木棍、工作台这类）：不用工作台，打开背包就能做
 */
public record WorkstationRecipe(String id, Kind kind, WantedItem result, int resultCount,
        List<IngredientStack> ingredients, boolean fitsInInventory) {

    /** 不看摆不摆得进背包合成格的配方：烧炼、石切台，以及只能在工作台上做的合成。 */
    public WorkstationRecipe(String id, Kind kind, WantedItem result, int resultCount,
            List<IngredientStack> ingredients) {
        this(id, kind, result, resultCount, ingredients, false);
    }

    public WorkstationRecipe {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("配方必须有注册 ID");
        if (kind == null) throw new IllegalArgumentException("配方必须有设施种类");
        if (result == null) throw new IllegalArgumentException("配方必须有产出");
        if (resultCount < 1) throw new IllegalArgumentException("配方的产出数量至少为 1：" + resultCount);
        ingredients = List.copyOf(ingredients);
        if (ingredients.isEmpty()) throw new IllegalArgumentException("配方至少要有一种原料：" + id);
        if (fitsInInventory && kind != Kind.CRAFTING) {
            throw new IllegalArgumentException("只有合成配方能在背包的合成格里做：" + id);
        }
    }

    /**
     * 做这条配方的设施。设施在哪种方块上、叫什么、要不要燃料、角色会不会为了做东西就地摆一个、
     * 规划时按多少刻烧一件，都只在这里记一份：报价、备料、走到设施跟前、认界面都按它答。
     */
    public enum Kind {
        /** 合成：摆得进 2×2 的在背包合成格里做，摆不进的上工作台（见 fitsInInventory）。 */
        CRAFTING("minecraft:crafting_table", "工作台", 0, true),
        /** 熔炉烧炼；除了原料还要燃料。附近没有会就地摆一个。 */
        SMELTING("minecraft:furnace", "熔炉", 200, true),
        /**
         * 高炉烧炼：矿石、金属装备在高炉里烧快一倍。只用记得的现成高炉（真人不会为了快一倍先凑五块铁做一台），
         * 没有就照旧用熔炉。
         */
        BLASTING("minecraft:blast_furnace", "高炉", 100, false),
        /** 烟熏炉烤食物，快一倍；同样只用记得的现成烟熏炉。 */
        SMOKING("minecraft:smoker", "烟熏炉", 100, false),
        /** 石切台切割，机制与合成同途径：读配方、放原料、取成品。 */
        STONECUTTING("minecraft:stonecutter", "石切台", 0, true);

        private final String blockType;
        private final String displayName;
        private final int plannedCookTicks;
        private final boolean placeable;

        Kind(String blockType, String displayName, int plannedCookTicks, boolean placeable) {
            this.blockType = blockType;
            this.displayName = displayName;
            this.plannedCookTicks = plannedCookTicks;
            this.placeable = placeable;
        }

        /** 这种设施的方块类型：记忆里按它找设施，就地摆也摆它。 */
        public String blockType() {
            return blockType;
        }

        /** 给人看的名字：工作台、熔炉、高炉、烟熏炉、石切台。 */
        public String displayName() {
            return displayName;
        }

        /** 要烧：除了原料还要燃料，做法是装炉子、关着界面等、回来取。 */
        public boolean cooks() {
            return plannedCookTicks > 0;
        }

        /** 规划时按多少刻烧一件（熔炉 200、高炉烟熏炉 100），只用来比快慢；不烧的为 0。实际烧多久以配方为准。 */
        public int plannedCookTicks() {
            return plannedCookTicks;
        }

        /**
         * 燃料烧得比熔炉快几倍：原版高炉、烟熏炉的燃料时长减半（烧一件 100 刻、燃料也只顶熔炉的一半），
         * 所以一块煤在哪种炉子里都烧 8 件。燃料表给的是熔炉的时长，用在高炉、烟熏炉上要除以它。
         */
        public int fuelBurnDivisor() {
            return this == BLASTING || this == SMOKING ? 2 : 1;
        }

        /** 备燃料时按熔炉口径算一件要多少刻燃料：三种炉子都是 200（高炉烧得快，燃料也耗得快，正好抵消）。 */
        public int fuelTicksPerItem() {
            return cooks() ? 200 : 0;
        }

        /** 附近没有时角色会不会就地摆一个：工作台、熔炉、石切台会；高炉、烟熏炉只用现成的。 */
        public boolean placeable() {
            return placeable;
        }
    }

    /**
     * 做一次要的一种原料：要什么、要几件。这一格收好几样又没有一个正好对上的标签时（给床换颜色要"别的颜色的任一张床"），
     * choices 列出收的全部物品，item 是其中第一样；身上有哪样就用哪样，缺了由来源挑最省的那样去弄，不只认第一样。
     *
     * @param choices 这一格收的全部物品 ID；只收一样或写成标签时为空
     */
    public record IngredientStack(WantedItem item, int count, List<String> choices) {
        public IngredientStack {
            if (item == null) throw new IllegalArgumentException("原料必须写要什么");
            if (count < 1) throw new IllegalArgumentException("原料数量至少为 1：" + count);
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        /** 只收一样东西（或一个标签）的原料。 */
        public IngredientStack(WantedItem item, int count) {
            this(item, count, List.of());
        }

        /** 这一格收哪几样：可以互换的列全部，否则就是 item 一样。 */
        public List<WantedItem> options() {
            return choices.size() > 1 ? choices.stream().map(WantedItem::ofItem).toList() : List.of(item);
        }

        /** 一件物品放不放得进这一格。 */
        public boolean accepts(String itemId, Set<String> itemTags) {
            return item.matches(itemId, itemTags) || choices.contains(itemId);
        }

        /** 给结果看的写法：可以互换的写成"某某等几种里的任一样"。 */
        public String describe() {
            return choices.size() > 1 ? item.describe() + " 等 " + choices.size() + " 种里的任一样" : item.describe();
        }
    }
}
