// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.EnchantmentMenu;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 附魔事实的生产读端：从角色上下文读身上逐格与附魔台菜单此刻的样子。
 *
 * <p>三档报价、随机种子与两格工作槽都读自原版 {@code EnchantmentMenu} 同步下来的公开读数，
 * 线索附魔的序号经注册表换算成 ID；这里只翻译现场，不算报价、不发点击。
 */
final class LiveEnchantReads implements EnchantMenuFacts {

    private final Supplier<PlayerContext> contexts;

    LiveEnchantReads(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public List<ItemFacts> carriedSlots() {
        LocalPlayer player = player();
        List<ItemFacts> slots = new ArrayList<>();
        if (player == null) return slots;
        // 只认主背包三十六格：附魔台界面的角色侧就是这三十六格，副手里的东西要先换开才装得进。
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            slots.add(ItemFacts.of(player.getInventory().items.get(slot)));
        }
        return slots;
    }

    @Override public boolean menuOpen() {
        return player() != null && player().containerMenu instanceof EnchantmentMenu;
    }

    @Override public Snapshot readMenu() {
        LocalPlayer player = player();
        if (player == null || !(player.containerMenu instanceof EnchantmentMenu menu)) return null;
        int[] costs = new int[3];
        String[] clues = new String[3];
        int[] clueLevels = new int[3];
        for (int i = 0; i < 3; i++) {
            costs[i] = menu.costs[i];
            clueLevels[i] = menu.levelClue[i];
            clues[i] = clueName(player, menu.enchantClue[i]);
        }
        return new Snapshot(costs, clues, clueLevels, menu.getEnchantmentSeed(),
                ItemFacts.of(menu.getSlot(0).getItem()), ItemFacts.of(menu.getSlot(1).getItem()),
                menu.getCarried().isEmpty(),
                player.experienceLevel,
                player.hasInfiniteMaterials());
    }

    /** 线索附魔的序号换算成注册 ID；-1 是没有线索的空档。 */
    private String clueName(LocalPlayer player, int clueId) {
        if (clueId < 0) return "";
        var holder = player.registryAccess().registryOrThrow(Registries.ENCHANTMENT).asHolderIdMap().byId(clueId);
        return holder == null ? "" : holder.unwrapKey().map(key -> key.location().toString()).orElse("");
    }

    private LocalPlayer player() {
        PlayerContext context = contexts.get();
        return context == null ? null : context.localPlayer();
    }
}
