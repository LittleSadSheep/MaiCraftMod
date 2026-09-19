// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;

/** 冻结附魔台已经同步的三档报价；只读菜单、成本与成品证据，不计算随机附魔，也不发送按钮或重试。 */
public final class EnchantmentQuote {
    public record Offer(int tier, int requiredLevel, int levelsSpent, int lapisCost, String clue, int clueLevel) {
        /** 等级门槛与真正扣除的等级分别展示，避免把三档要求的三十级误报成花费三十级。 */
        public Map<String, Object> describe() {
            return Map.of("tier", tier, "required_level", requiredLevel, "levels_spent", levelsSpent,
                    "lapis_cost", lapisCost, "enchantment_clue", clue, "clue_level", clueLevel,
                    "quote_available", requiredLevel > 0 && !clue.isEmpty() && clueLevel > 0);
        }
    }

    private final LocalPlayer player;
    private final Object level;
    private final EnchantmentMenu menu;
    private final ItemStack input, lapis;
    private final int seed, playerLevel;
    private final boolean creative, infiniteMaterials;
    private final int[] costs, clues, clueLevels;
    private final List<Offer> offers;

    private EnchantmentQuote(LocalPlayer player, EnchantmentMenu menu) {
        this.player = Objects.requireNonNull(player); this.menu = Objects.requireNonNull(menu);
        if (player.containerMenu != menu) throw new IllegalStateException("enchantment_menu_changed");
        level = player.level(); input = menu.getSlot(0).getItem().copy(); lapis = menu.getSlot(1).getItem().copy();
        seed = menu.getEnchantmentSeed(); playerLevel = player.experienceLevel;
        creative = player.getAbilities().instabuild; infiniteMaterials = player.hasInfiniteMaterials();
        costs = menu.costs.clone(); clues = menu.enchantClue.clone(); clueLevels = menu.levelClue.clone();
        var values = new ArrayList<Offer>();
        for (int i = 0; i < 3; i++) {
            String clue = "";
            if (clues[i] >= 0) {
                var holder = player.registryAccess().registryOrThrow(Registries.ENCHANTMENT).asHolderIdMap().byId(clues[i]);
                if (holder != null) clue = holder.unwrapKey().map(key -> key.location().toString()).orElse("");
            }
            // 原版创造模式免青金石、免等级门槛，但 onEnchantmentPerformed 仍扣档位等级并最低归零。
            values.add(new Offer(i + 1, costs[i], Math.min(Math.max(0, playerLevel), i + 1),
                    infiniteMaterials ? 0 : i + 1, clue, clueLevels[i]));
        }
        offers = List.copyOf(values);
    }

    /** 可见界面与同步时机由工作流确认；这里只冻结当前真实报价，不用本地种子推算隐藏附魔。 */
    public static EnchantmentQuote capture(LocalPlayer player, EnchantmentMenu menu) { return new EnchantmentQuote(player, menu); }
    public ItemStack input() { return input.copy(); }
    public List<Offer> offers() { return offers; }

    /** 对外只给物品、等级、材料与可见附魔线索，不泄露菜单槽号、种子或原生注册表序号。 */
    public Map<String, Object> describe() {
        return Map.of("item_id", BuiltInRegistries.ITEM.getKey(input.getItem()).toString(),
                "player_level", playerLevel, "lapis_available", lapis.is(Items.LAPIS_LAZULI) ? lapis.getCount() : 0,
                "creative_mode", creative, "offers", offers.stream().map(Offer::describe).toList());
    }

    public Offer requireChoice(int tier, int maxLevelsSpent, int maxLapis) {
        // 一次只处理一件尚未附魔的物品；未同步的线索和不满足费用的报价都不能授权发出按钮。
        if (tier < 1 || tier > 3 || maxLevelsSpent < 0 || maxLapis < 0)
            throw new IllegalArgumentException("invalid_enchantment_choice_or_budget");
        if (input.getCount() != 1 || !input.isEnchantable()
                || !input.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty())
            throw new IllegalArgumentException("enchantment_requires_one_unenchanted_item");
        Offer offer = offers.get(tier - 1);
        if (offer.requiredLevel() <= 0 || offer.clue().isEmpty() || offer.clueLevel() <= 0)
            throw new IllegalStateException("enchantment_quote_not_synchronized_or_unavailable");
        if (!creative && (playerLevel < offer.requiredLevel() || playerLevel < tier))
            throw new IllegalStateException("enchantment_insufficient_levels");
        if ((!lapis.isEmpty() && !lapis.is(Items.LAPIS_LAZULI))
                || !infiniteMaterials && lapis.getCount() < offer.lapisCost())
            throw new IllegalStateException("enchantment_insufficient_lapis");
        if (offer.levelsSpent() > maxLevelsSpent || offer.lapisCost() > maxLapis)
            throw new IllegalStateException("enchantment_cost_exceeds_authorized_budget");
        return offer;
    }

    /** 提交前重新确认身体、菜单、三档报价和两叠物品；报价重滚或经验变化后必须重新征询，而不是照旧点击。 */
    public boolean stillMatches(LocalPlayer player, EnchantmentMenu menu) {
        return sameSession(player, menu) && player.experienceLevel == playerLevel
                && menu.getEnchantmentSeed() == seed && Arrays.equals(costs, menu.costs)
                && Arrays.equals(clues, menu.enchantClue) && Arrays.equals(clueLevels, menu.levelClue)
                && ItemStack.matches(input, menu.getSlot(0).getItem()) && ItemStack.matches(lapis, menu.getSlot(1).getItem());
    }

    public MenuConfirmation confirmation(Offer offer) {
        // 成品、青金石和经验可能分包抵达；任何一项仍是原值时继续等，绝不在确认回调中补发操作。
        Objects.requireNonNull(offer);
        if (!offer.equals(requireChoice(offer.tier(), Integer.MAX_VALUE, Integer.MAX_VALUE)))
            throw new IllegalArgumentException("enchantment_offer_does_not_belong_to_quote");
        ItemStack expectedLapis = lapis.copy(); expectedLapis.shrink(offer.lapisCost());
        int expectedLevel = playerLevel - offer.levelsSpent();
        return (context, receipt) -> {
            LocalPlayer observed = context.player();
            if (!sameSession(observed, menu) || receipt != null && receipt.containerId() != menu.containerId)
                return MenuConfirmation.Verdict.DIVERGED;
            ItemStack actual = menu.getSlot(0).getItem(), actualLapis = menu.getSlot(1).getItem();
            boolean lapisAfter = ItemStack.matches(actualLapis, expectedLapis), levelAfter = observed.experienceLevel == expectedLevel;
            if (!lapisAfter && !ItemStack.matches(actualLapis, lapis)
                    || !levelAfter && observed.experienceLevel != playerLevel)
                return MenuConfirmation.Verdict.DIVERGED;
            if (ItemStack.matches(actual, input)) return MenuConfirmation.Verdict.PENDING;
            if (!matchesEnchantedResult(actual, offer)) return MenuConfirmation.Verdict.DIVERGED;
            return lapisAfter && levelAfter ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
        };
    }

    private boolean sameSession(LocalPlayer player, EnchantmentMenu menu) {
        return this.player == player && this.menu == menu && player.containerMenu == menu && player.level() == level
                && player.getAbilities().instabuild == creative && player.hasInfiniteMaterials() == infiniteMaterials;
    }

    private boolean matchesEnchantedResult(ItemStack actual, Offer offer) {
        // 书按原版 transmuteCopy 转成附魔书并使用储存附魔；普通装备只允许附魔组件变化，损伤、名字和其他组件必须原样保留。
        if (actual.getCount() != 1) return false;
        boolean book = input.is(Items.BOOK);
        ItemStack expectedBase = book ? input.transmuteCopy(Items.ENCHANTED_BOOK) : input.copy();
        DataComponentType<ItemEnchantments> type = book ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS;
        ItemEnchantments enchantments = actual.getOrDefault(type, ItemEnchantments.EMPTY);
        boolean visibleCluePresent = enchantments.entrySet().stream().anyMatch(entry -> entry.getIntValue() == offer.clueLevel()
                && entry.getKey().unwrapKey().map(key -> key.location().toString().equals(offer.clue())).orElse(false));
        if (!visibleCluePresent) return false;
        ItemStack actualBase = actual.copy(); expectedBase.remove(type); actualBase.remove(type);
        return ItemStack.matches(expectedBase, actualBase);
    }
}
