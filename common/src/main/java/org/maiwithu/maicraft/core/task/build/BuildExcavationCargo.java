package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 只自动存放新增加的普通土石，保留开工时的物品、后续建筑材料和垫脚方块。 */
final class BuildExcavationCargo {
    private Map<Item, Integer> initial;
    private final Set<Item> candidates = new LinkedHashSet<>();
    void begin(LocalPlayer player) { if (initial == null) initial = inventory(player); }

    void observedTerrain(BlockState state) {
        // 根据实际挖到的地层记住可能拾起的材料，例如石头掉圆石、草方块掉泥土。
        Item item = state.getBlock().asItem();
        if (item != Items.AIR && item.getDefaultInstance().getMaxStackSize() >= 16) candidates.add(item);
        if (state.is(Blocks.STONE)) candidates.add(Items.COBBLESTONE);
        if (state.is(Blocks.DEEPSLATE)) candidates.add(Items.COBBLED_DEEPSLATE);
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)) candidates.add(Items.DIRT);
    }

    boolean capacityLow(LocalPlayer player) {
        int empty = 0;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) empty++;
        return empty < 4;
    }

    Map<ResourceLocation, Integer> unloadable(LocalPlayer player, Map<Item, Integer> materialNeeds) {
        // 比较开工背包和当前背包，再扣除建房所需；有自定义名称或数据的同类物品整类留下，避免误存珍藏。
        Map<Item, Integer> current = inventory(player);
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (Item item : candidates) {
            boolean custom = false;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(item) && !stack.getComponentsPatch().isEmpty()) { custom = true; break; }
            }
            if (custom) continue;
            int keep = Math.max(initial.getOrDefault(item, 0), materialNeeds.getOrDefault(item, 0));
            if (item == Items.COBBLESTONE) keep = Math.max(keep, 64);
            int surplus = Math.max(0, current.getOrDefault(item, 0) - keep);
            if (surplus > 0) result.put(BuiltInRegistries.ITEM.getKey(item), surplus);
        }
        return Map.copyOf(result);
    }

    private static Map<Item, Integer> inventory(LocalPlayer player) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }
}
