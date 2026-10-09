// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Objects;

/**
 * 读真实角色的背包主格：每次读取都是当下的一张快照，不缓存。
 *
 * <p>分类标记问的是游戏本身：工具、武器、护甲按物品类型认；能吃看食物组件；
 * 贵重看游戏标的稀有度或有没有附魔；整块建材看它是不是一个实心方块物品。
 * 这些判断只在这里做，别的层不重复问一遍游戏。
 */
public final class ClientBackpackView implements BackpackView {

    // 背包主格：36 格，含快捷栏；盔甲与副手各占自己的栏位，不算在里面。
    private static final int MAIN_SLOTS = 36;
    // 快捷栏：主格的前 9 格，数字键 1–9 能直接选中。
    private static final int HOTBAR_SLOTS = 9;

    private final LocalPlayer player;

    public ClientBackpackView(LocalPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public List<BackpackStack> stacks() {
        List<BackpackStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(snapshot(stack));
            }
        }
        return List.copyOf(stacks);
    }

    @Override
    public int usedSlots() {
        int used = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    @Override
    public int totalSlots() {
        return MAIN_SLOTS;
    }

    @Override
    public OptionalInt hotbarSlotOf(String itemId) {
        // 快捷栏就是主格的前 9 格：按格子顺序找第一格放着它的。
        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public int selectedHotbarSlot() {
        return player.getInventory().selected;
    }

    // 把一格物品读成事实快照：是什么、有多少、腾地方要用的几个分类标记。
    // 装备栏视图读副手与护甲时用同一张快照，分类判断不写第二份。
    public static BackpackStack snapshot(ItemStack stack) {
        var item = stack.getItem();
        boolean gear = item instanceof SwordItem || item instanceof DiggerItem || item instanceof ProjectileWeaponItem
                || item instanceof TridentItem || item instanceof ArmorItem || item instanceof ShieldItem;
        boolean food = stack.has(DataComponents.FOOD);
        // 贵重：游戏标了 uncommon 以上稀有度，或者带附魔——丢了心疼、难补的东西。
        boolean precious = stack.getRarity() != Rarity.COMMON || stack.isEnchanted();
        boolean building = item instanceof BlockItem blockItem
                && blockItem.getBlock().defaultBlockState().blocksMotion();
        return new BackpackStack(
                BuiltInRegistries.ITEM.getKey(item).toString(),
                stack.getCount(),
                stack.getMaxStackSize(),
                gear,
                food,
                precious,
                building);
    }
}
