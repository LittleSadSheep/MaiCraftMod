// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.filter.AbstractFilterMenu;
import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.filter.FilterScreenPacket;
import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
import com.simibubi.create.foundation.gui.menu.IClearableMenu;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import org.maiwithu.maicraft.compat.create.CreateFilters;

/**
 * 读写机械动力过滤器的读写端：规则读物品数据组件（FILTER_ITEMS、FILTER_ITEMS_BLACKLIST、FILTER_ITEMS_RESPECT_NBT、
 * ATTRIBUTE_FILTER_WHITELIST_MODE、ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES）；改的时候发界面自己的 FilterScreenPacket，
 * 清空调界面的 sendClearPacket。只翻译，不判断。
 */
public final class CreateFilterReads implements CreateFilters {

    private static final int SLOTS = 18;

    @Override public Optional<FilterRules> filter(ItemStack stack) {
        if (!is(stack, "create:filter")) return Optional.empty();
        NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        stack.getOrDefault(AllDataComponents.FILTER_ITEMS, ItemContainerContents.EMPTY).copyInto(items);
        List<String> ids = new ArrayList<>();
        for (ItemStack item : items) ids.add(item.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
        return Optional.of(new FilterRules(ids, stack.getOrDefault(AllDataComponents.FILTER_ITEMS_BLACKLIST, false),
                stack.getOrDefault(AllDataComponents.FILTER_ITEMS_RESPECT_NBT, false)));
    }

    @Override public Optional<AttributeRules> attributeFilter(ItemStack stack) {
        if (!is(stack, "create:attribute_filter")) return Optional.empty();
        AttributeFilterWhitelistMode mode = stack.getOrDefault(AllDataComponents.ATTRIBUTE_FILTER_WHITELIST_MODE,
                AttributeFilterWhitelistMode.WHITELIST_DISJ);
        List<String> attributes = new ArrayList<>();
        for (ItemAttribute.ItemAttributeEntry entry : stack.getOrDefault(AllDataComponents.ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES,
                List.<ItemAttribute.ItemAttributeEntry>of())) {
            attributes.add(entry.attribute().format(entry.inverted()).getString());
        }
        String name = switch (mode) {
            case WHITELIST_CONJ -> "all";
            case BLACKLIST -> "deny";
            default -> "any";
        };
        return Optional.of(new AttributeRules(name, attributes));
    }

    @Override public boolean filterMenuOpen(LocalPlayer player) {
        return player.containerMenu instanceof AbstractFilterMenu;
    }

    @Override public void setFilterSlot(int slot, String itemId) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack item = itemId.isEmpty() ? ItemStack.EMPTY
                : BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).map(ItemStack::new).orElse(ItemStack.EMPTY);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Slot", slot);
        tag.put("Item", item.saveOptional(minecraft.level.registryAccess()));
        CatnipServices.NETWORK.sendToServer(new FilterScreenPacket(FilterScreenPacket.Option.UPDATE_FILTER_ITEM, tag));
    }

    @Override public void pressFilterButton(String button) {
        FilterScreenPacket.Option option = switch (button) {
            case "deny" -> FilterScreenPacket.Option.BLACKLIST;
            case "match_components" -> FilterScreenPacket.Option.RESPECT_DATA;
            case "ignore_components" -> FilterScreenPacket.Option.IGNORE_DATA;
            case "all" -> FilterScreenPacket.Option.WHITELIST2;
            default -> FilterScreenPacket.Option.WHITELIST;
        };
        CatnipServices.NETWORK.sendToServer(new FilterScreenPacket(option));
    }

    // 照界面那样：样本物品的全部属性里挑文字包含片段的第一条，发 ADD_TAG / ADD_INVERTED_TAG。
    @Override public Optional<List<String>> addAttribute(String sampleItem, String fragment, boolean inverted) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack sample = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(sampleItem))
                .map(ItemStack::new).orElse(ItemStack.EMPTY);
        List<String> options = new ArrayList<>();
        if (sample.isEmpty()) return Optional.of(options);
        String wanted = fragment.toLowerCase(Locale.ROOT);
        for (ItemAttribute attribute : ItemAttribute.getAllAttributes(sample, minecraft.level)) {
            String text = attribute.format(false).getString();
            options.add(text);
            if (!text.toLowerCase(Locale.ROOT).contains(wanted)) continue;
            CompoundTag tag = ItemAttribute.saveStatic(attribute, minecraft.level.registryAccess());
            CatnipServices.NETWORK.sendToServer(new FilterScreenPacket(inverted
                    ? FilterScreenPacket.Option.ADD_INVERTED_TAG : FilterScreenPacket.Option.ADD_TAG, tag));
            return Optional.empty();
        }
        return Optional.of(options);
    }

    @Override public void clearFilter() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof IClearableMenu clearable) {
            clearable.clearContents();
            clearable.sendClearPacket();
        }
    }

    private static boolean is(ItemStack stack, String id) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id);
    }
}
